package apk.harness.bootstrap

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the install is doing, for something to draw. */
sealed interface BootstrapProgress {
    data class Downloading(val bytes: Long, val total: Long) : BootstrapProgress
    data class Extracting(val entries: Int, val total: Int) : BootstrapProgress
    data class Linking(val made: Int, val total: Int) : BootstrapProgress
    data class Relocating(val pair: Int, val of: Int) : BootstrapProgress
    data object Configuring : BootstrapProgress
    data object Done : BootstrapProgress
    data class Failed(val reason: String) : BootstrapProgress
}

/**
 * Installs a Termux userland into the app's own data directory.
 *
 * The tree is extracted still naming `com.termux` and relocated in place against
 * this app's id, so one archive serves every channel. The relocator is a program
 * in the native library directory, which is the only directory the app may
 * execute from whatever it targets.
 *
 * Every step is repeatable, which is what stands in for a retry: a verified
 * archive is not fetched twice, extraction and the symlink replay overwrite what
 * they find, the relocator finds nothing left to rewrite on a second pass, and
 * the manifest pass leaves a manifest already fixed alone. So the answer to a
 * [BootstrapProgress.Failed] is another call to [install], which is what the next
 * launch makes.
 */
class Bootstrap(private val context: Context) {

    // Spelled from the package name rather than taken from `dataDir`, which
    // reports the same directory as `/data/user/0/...`. That spelling is two
    // bytes longer, and the tree's prefix has to fit inside the one Termux
    // compiled into it, so `/data/data` is the only spelling it can carry. It is
    // also the spelling `Agent` looks for a userland under.
    private val data = File("/data/data/${context.packageName}")
    private val rootfs = File(data, "root")
    private val prefix = File(rootfs, "usr")

    fun isInstalled(): Boolean = File(prefix, BASH_PATH).canExecute()

    suspend fun install(onProgress: (BootstrapProgress) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val abi = Build.SUPPORTED_ABIS.first()
            val release = releaseFor(abi) ?: error("no bootstrap for $abi")
            val pairs = relocationPairs(context.packageName)

            val archive = fetchVerified(
                source = { openRelease(release) },
                release = release,
                into = context.cacheDir,
            ) { onProgress(BootstrapProgress.Downloading(it, release.bytes)) }

            prefix.mkdirs()
            val entries = extractArchive(archive.inputStream(), prefix) {
                onProgress(BootstrapProgress.Extracting(it, EXPECTED_ENTRIES))
            }
            // Checked here rather than at the end: an archive that unpacked to
            // nothing relocates and configures without complaint, and the only
            // symptom left to report would be a missing bash.
            check(entries > 0) { "${archive.name} unpacked no entries" }

            val links = parseSymlinks(readSymlinks(archive))
            onProgress(BootstrapProgress.Linking(0, links.size))
            val made = replaySymlinks(links, prefix)
            onProgress(BootstrapProgress.Linking(made, links.size))

            // The relocator's symlink pass repoints the absolute targets, and a
            // target is a string in the link's own inode rather than bytes in a
            // file -- so the links have to exist before it runs. `SYMLINKS.txt`
            // names them relative to the prefix, and names some of their targets
            // absolutely under `com.termux`, apt's keyring among them.
            pairs.forEachIndexed { index, pair ->
                onProgress(BootstrapProgress.Relocating(index + 1, pairs.size))
                relocate(rootfs, pair)
            }
            rewriteManifests(File(prefix, DPKG_INFO), context.packageName)

            onProgress(BootstrapProgress.Configuring)
            configure()
            onProgress(
                if (isInstalled()) BootstrapProgress.Done
                else BootstrapProgress.Failed("$BASH_PATH is not in the installed tree")
            )
        } catch (cancelled: CancellationException) {
            // An abandoned install, not a failed one: drawing an error over a
            // screen that is going away is noise, and swallowing this leaves the
            // coroutine that asked to stop still running.
            throw cancelled
        } catch (e: Exception) {
            onProgress(BootstrapProgress.Failed(e.message ?: e.toString()))
        }
    }

    /** The archive's symlink list, read on a second pass over the zip. */
    private fun readSymlinks(archive: File): String {
        // The entry is found and the rest of the archive abandoned; `use` closes
        // the stream on the way out of the function, this return included.
        ZipInputStream(archive.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.name == SYMLINKS_NAME) return stream.readBytes().decodeToString()
                stream.closeEntry()
            }
        }
        error("$SYMLINKS_NAME is not in ${archive.name}")
    }

    /**
     * Runs one substitution over [tree].
     *
     * The tree given is the rootfs rather than the app's data directory: the pass
     * reads every file it walks whole, and the directories beside the rootfs hold
     * a staged agent binary and the archive still in the cache -- some ninety
     * megabytes with nothing in them to rewrite.
     */
    private fun relocate(tree: File, pair: RelocationPair) {
        val binary = File(context.applicationInfo.nativeLibraryDir, RELOCATE_NAME)
        check(binary.canExecute()) { "$RELOCATE_NAME is not executable" }
        val process = ProcessBuilder(binary.absolutePath, tree.absolutePath, pair.old, pair.new)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val status = process.waitFor()
        // A non-zero status is a file the relocator could not rewrite, which is a
        // half-moved tree. Reported rather than installed.
        check(status == 0) { "relocate exited $status: $output" }
    }

    /**
     * The parts apt needs that are not files in the archive: a cache directory
     * outside the prefix under the name the tree was rewritten to expect, a home
     * and a temporary directory, and the repack shim installed as apt's dpkg.
     *
     * Without the shim a Termux `.deb` unpacks into `/data/data/com.termux/...`
     * and fails on a permission error, because its payload is rooted at an
     * absolute path rather than at the prefix.
     */
    private fun configure() {
        File(data, "cach/apt/archives/partial").mkdirs()
        for (path in listOf("var/lib/apt/lists/partial", "tmp")) File(prefix, path).mkdirs()
        File(rootfs, "home").mkdirs()

        // Under `libexec` because no package owns that directory, so an upgrade
        // of dpkg cannot overwrite the shim. The shim reads its own prefix from
        // the path it was invoked by, so it belongs one directory below it.
        val libexec = File(prefix, "libexec").apply { mkdirs() }
        val shim = File(libexec, SHIM_NAME)
        context.assets.open(SHIM_NAME).use { input ->
            shim.outputStream().use { output -> input.copyTo(output) }
        }
        shim.setExecutable(true, false)

        // Absolute, which apt executes as written rather than resolving under
        // `Dir` the way it would a bare name.
        val confd = File(prefix, "etc/apt/apt.conf.d").apply { mkdirs() }
        File(confd, "99-repack").writeText("Dir::Bin::dpkg \"${shim.absolutePath}\";\n")
    }

    private companion object {
        const val BASH_PATH = "bin/bash"
        const val DPKG_INFO = "var/lib/dpkg/info"
        const val SHIM_NAME = "repack-deb"

        // A program, shipped as a library because that directory stays executable
        // whatever the app targets.
        const val RELOCATE_NAME = "librelocate.so"

        // For the progress bar only: an entry count that is one archive out of
        // date makes a bar slightly wrong, not a tree.
        const val EXPECTED_ENTRIES = 3766
    }
}

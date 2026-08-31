package apk.harness.bootstrap

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
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
    data object Resolving : BootstrapProgress
    data class FetchingAgent(val bytes: Long, val total: Long) : BootstrapProgress
    data object StagingAgent : BootstrapProgress
    data object Done : BootstrapProgress
    data class Failed(val reason: String) : BootstrapProgress
}

/** True while [progress] is a phase of the agent half rather than the userland's. */
fun installingAgent(progress: BootstrapProgress?): Boolean = when (progress) {
    BootstrapProgress.Resolving, BootstrapProgress.StagingAgent -> true
    is BootstrapProgress.FetchingAgent -> true
    else -> false
}

/**
 * Installs what the app runs: a Termux userland, and the agent on top of it.
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

    private val agent = AgentStage(context)

    // One spelling of these paths, shared with the half that runs the agent:
    // the tree is relocated to what the scripts will name, and two spellings of
    // one directory is the comparison risk the byte budget exists to avoid.
    private val data = agent.data
    private val rootfs = agent.rootfs
    private val prefix = agent.prefix

    fun isInstalled(): Boolean = hasUserland() && agent.isStaged()

    private fun hasUserland(): Boolean = File(prefix, BASH_PATH).canExecute()

    suspend fun install(onProgress: (BootstrapProgress) -> Unit) = withContext(Dispatchers.IO) {
        try {
            if (!hasUserland()) installUserland(onProgress)
            agent.install(onProgress)
            onProgress(
                if (isInstalled()) BootstrapProgress.Done
                else BootstrapProgress.Failed("the install finished without a runnable agent")
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

    private fun installUserland(onProgress: (BootstrapProgress) -> Unit) {
        val abi = Build.SUPPORTED_ABIS.first()
        val release = releaseFor(abi) ?: error("no bootstrap for $abi")
        val pairs = relocationPairs(context.packageName)

        val archive = fetchVerified(
            source = { openRelease(release) },
            name = release.asset,
            checksum = release.sha256,
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
        check(hasUserland()) { "$BASH_PATH is not in the installed tree" }
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
     * outside the prefix under the name the tree was rewritten to expect, a
     * temporary directory, a rootfs home pointing at the app's own, and the
     * repack shim installed as apt's dpkg.
     *
     * Without the shim a Termux `.deb` unpacks into `/data/data/com.termux/...`
     * and fails on a permission error, because its payload is rooted at an
     * absolute path rather than at the prefix.
     */
    private fun configure() {
        File(data, "cach/apt/archives/partial").mkdirs()
        for (path in listOf("var/lib/apt/lists/partial", "tmp")) File(prefix, path).mkdirs()
        stageRootfsHome(File(rootfs, "home"), agent.home)

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

    /**
     * Points [link] at [home], so that a dotfile in the app home is reachable by a
     * binary that reads its own from the rootfs.
     *
     * `Os.symlink` because `java.io` cannot make a link. Whether one is already
     * there is read with `lstat`, since `exists` follows a link and answers about
     * its target -- which for a link made on an earlier run is the app home, and
     * would report a directory rather than the link.
     */
    private fun stageRootfsHome(link: File, home: File) {
        val mode = runCatching { Os.lstat(link.path).st_mode }.getOrNull()
        val isLink = mode != null && OsConstants.S_ISLNK(mode)
        val entries = if (mode != null && !isLink) link.list()?.size ?: 0 else 0
        when (homeAction(exists = mode != null, isLink = isLink, entries = entries)) {
            // Reported rather than emptied: whatever is in there was written by a
            // binary reading the stamped-in path, and where it belongs is not a
            // choice this can make silently.
            HomeAction.KEEP, HomeAction.REPORT -> return
            HomeAction.REPLACE -> link.delete()
            HomeAction.LINK -> Unit
        }
        link.parentFile?.mkdirs()
        Os.symlink(home.absolutePath, link.path)
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

/** What staging should do with the rootfs home it finds. */
enum class HomeAction { LINK, REPLACE, KEEP, REPORT }

/**
 * What to do with a rootfs home in the state described.
 *
 * A relocated Termux binary reads dotfiles from the rootfs home rather than from
 * `$HOME`: the prefix rewrite stamps the path into it, and there is no
 * `/etc/passwd` in the rootfs to say otherwise -- `strings` on `usr/bin/bash`
 * yields a literal `<data>/root/home`. So the path has to resolve to the app home,
 * or a key in `$HOME/.ssh` is invisible to the binary that needs it, and the
 * failure names the wrong cause: a key never offered reads as a key rejected.
 *
 * The archive carries no `home` entry, which is why this has to be made at all, so
 * a first install answers [LINK]. [REPLACE] is for a repeat: `installUserland` runs
 * again whenever the prefix has no executable `bash`, and an earlier build left a
 * directory here. A directory holding anything answers [REPORT] instead -- its
 * contents were written by a binary reading the stamped-in path, and where they
 * belong is not a choice staging can make on its own.
 */
fun homeAction(exists: Boolean, isLink: Boolean, entries: Int): HomeAction = when {
    !exists -> HomeAction.LINK
    isLink -> HomeAction.KEEP
    entries == 0 -> HomeAction.REPLACE
    else -> HomeAction.REPORT
}

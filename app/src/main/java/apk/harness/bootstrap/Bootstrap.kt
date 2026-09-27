package apk.harness.bootstrap

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
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

    private val libraries = File(context.applicationInfo.nativeLibraryDir)

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

        val archive = fetchBootstrap(release, context.cacheDir) {
            onProgress(BootstrapProgress.Downloading(it, release.bytes))
        }

        prefix.mkdirs()
        val entries = extractArchive(archive.inputStream(), prefix) {
            onProgress(BootstrapProgress.Extracting(it, release.entries))
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
        //
        // The tree given is the rootfs rather than the app's data directory: the
        // pass reads every file it walks whole, and the directories beside the
        // rootfs hold a staged agent binary and the archive still in the cache --
        // some ninety megabytes with nothing in them to rewrite.
        onProgress(BootstrapProgress.Relocating(1, 1))
        val (status, output) = runRelocator(
            listOf(
                "--content", "--links", "--tls",
                "--md5sums", File(prefix, DPKG_INFO).path,
                rootfs.absolutePath,
            ) + pairArguments(pairs)
        )
        // A non-zero status is a file the relocator could not rewrite, which is a
        // half-moved tree. Reported rather than installed.
        check(status == 0) { "relocate exited $status: $output" }
        rewriteManifests(File(prefix, DPKG_INFO), context.packageName)
        // This relocation covers what [correctOnce] does, so its stamp is written
        // here. Written by Kotlin rather than passed as `--stamp`, which would
        // skip the relocation of a tree extracted again over a stamped one.
        File(prefix, STAMP_PATH).let {
            it.parentFile?.mkdirs()
            writeReplacing(it, "$CORRECTION_VERSION\n")
        }

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
     * Runs the relocator from the native library directory with [arguments], and
     * returns its exit status and output.
     */
    private fun runRelocator(arguments: List<String>): Pair<Int, String> {
        val binary = File(libraries, RELOCATE_NAME)
        check(binary.canExecute()) { "$RELOCATE_NAME is not executable" }
        val process = ProcessBuilder(listOf(binary.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to output
    }

    private fun pairArguments(pairs: List<RelocationPair>) = pairs.flatMap { listOf(it.old, it.new) }

    /**
     * Brings an installed userland up to what this build expects, once per
     * process: the files [configure] writes, then the one-time correction.
     */
    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (!hasUserland() || !started.compareAndSet(false, true)) return@withContext
        try {
            configure()
            correctOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "the userland was not brought up to date", e)
        }
    }

    /**
     * The parts apt needs that are not files in the archive, written at every
     * start: apt's cache directory under the name the tree was rewritten to
     * expect, a temporary directory, a rootfs home pointing at the app's own,
     * the rootfs's link to the native library directory, `bin/proot`, and the
     * repack shim installed as apt's dpkg. Entries a dead proot left in the
     * temporary directory are removed.
     *
     * Without the shim a Termux `.deb` unpacks into `/data/data/com.termux/...`
     * and fails on a permission error, because its payload is rooted at an
     * absolute path rather than at the prefix.
     */
    private fun configure() {
        stageAptCache(File(data, "cach"))
        for (path in listOf("var/lib/apt/lists/partial", "tmp")) File(prefix, path).mkdirs()
        stageRootfsHome(File(rootfs, "home"), agent.home)
        // Ahead of the shim, which runs the relocator through it.
        stageAppLib(File(rootfs, APPLIB_NAME))
        stageProot()

        // Under `libexec` because no package owns that directory, so an upgrade
        // of dpkg cannot overwrite the shim. The shim reads its own prefix from
        // the path it was invoked by, so it belongs one directory below it. It is
        // app-owned and replaced whole through a rename, so a run in progress
        // keeps the copy it started with.
        val libexec = File(prefix, "libexec").apply { mkdirs() }
        val shim = File(libexec, SHIM_NAME)
        val staged = File(libexec, "$SHIM_NAME.tmp")
        context.assets.open(SHIM_NAME).use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        staged.setExecutable(true, false)
        Os.rename(staged.path, shim.path)

        // Absolute, which apt executes as written rather than resolving under
        // `Dir` the way it would a bare name.
        val confd = File(prefix, "etc/apt/apt.conf.d").apply { mkdirs() }
        // Replaced through a rename, since an apt in an open session may read them.
        writeReplacing(File(confd, "99-repack"), "Dir::Bin::dpkg \"${shim.absolutePath}\";\n")
        // The cache is in the data directory, so an installed archive is removed
        // rather than kept.
        writeReplacing(File(confd, "99-cache"), "APT::Keep-Downloaded-Packages \"false\";\n")

        removeProotLeftovers(File(prefix, "tmp"))
    }

    /**
     * Corrects a userland whose stamp does not hold [CORRECTION_VERSION]: the
     * symlink targets that name `com.termux`, the x86_64 TLS alignment, and the
     * `md5sums` entries of the files that pass selects. The relocator
     * takes the lock `repack-deb` takes and checks the stamp again under it, and
     * gives up with [LOCK_BUSY] while an `apt` in another tab holds it, so the
     * pass runs again at the next start.
     */
    private fun correctOnce() {
        val stamp = File(prefix, STAMP_PATH)
        if (stamp.isFile && stamp.readText().trim() == CORRECTION_VERSION) return
        stamp.parentFile?.mkdirs()

        // Work directories a shim left in the cache directory.
        agent.tmp.listFiles()?.filter { it.name.startsWith("repack-deb.") }?.forEach(::removeTree)

        val started = System.nanoTime()
        val (status, output) = runRelocator(
            listOf(
                "--lock", File(prefix, LOCK_PATH).path,
                "--stamp", stamp.path, CORRECTION_VERSION,
                "--links", "--tls",
                "--md5sums", File(prefix, DPKG_INFO).path,
                prefix.path,
            ) + pairArguments(relocationPairs(context.packageName))
        )
        val elapsed = (System.nanoTime() - started) / 1_000_000
        when (status) {
            0 -> Log.i(TAG, "the userland correction took $elapsed ms: $output")
            LOCK_BUSY -> Log.w(TAG, "the userland correction waits for the next start: $output")
            else -> Log.w(TAG, "the userland correction exited $status after $elapsed ms: $output")
        }
    }

    /**
     * Points [link] at the native library directory, so that a path under the
     * rootfs reaches the packaged programs: `repack-deb` runs the relocator
     * through it, and termux.net's `proot` finds the packaged loader by it.
     * A directory in its place is renamed aside rather than removed.
     */
    private fun stageAppLib(link: File) {
        val mode = runCatching { Os.lstat(link.path).st_mode }.getOrNull()
        if (mode != null && OsConstants.S_ISDIR(mode)) {
            val aside = File(link.parentFile, "${link.name}.${System.currentTimeMillis()}")
            Os.rename(link.path, aside.path)
            Log.w(TAG, "${link.path} was a directory, and is now ${aside.path}")
        }
        replaceLink(link, libraries.absolutePath)
    }

    /**
     * Points `bin/proot` at the packaged proot, unless the `proot` package owns
     * that path.
     */
    private fun stageProot() {
        val proot = File(prefix, "bin/proot")
        val list = File(prefix, "$DPKG_INFO/proot.list")
        if (list.isFile && list.readLines().any { it == proot.path }) return
        proot.parentFile?.mkdirs()
        replaceLink(proot, File(rootfs, "$APPLIB_NAME/$PROOT_NAME").path)
    }

    /**
     * Makes [link] a symlink to [target] through a temporary link renamed over
     * it, so no reader finds it missing.
     */
    private fun replaceLink(link: File, target: String) {
        if (runCatching { Os.readlink(link.path) }.getOrNull() == target) return
        val staged = File(link.parentFile, "${link.name}.tmp")
        removeTree(staged)
        Os.symlink(target, staged.path)
        Os.rename(staged.path, link.path)
    }

    /** Writes [text] to [file] through a temporary file renamed over it. */
    private fun writeReplacing(file: File, text: String) {
        val staged = File(file.parentFile, "${file.name}.tmp")
        staged.writeText(text)
        Os.rename(staged.path, file.path)
    }

    /**
     * Points [link] at [home], so that a dotfile in the app home is reachable by a
     * binary that reads its own from the rootfs.
     *
     * `Os.symlink` because `java.io` cannot make a link. Whether one is already
     * there is read with `lstat`, since `exists` follows a link and answers about
     * its target -- which for a link already in place is the app home, and would
     * report a directory rather than the link.
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

    /**
     * Makes [dir] apt's cache: a directory in the data directory, which the
     * system does not empty under a download. A link found in its place is
     * replaced by an empty directory.
     */
    private fun stageAptCache(dir: File) {
        val mode = runCatching { Os.lstat(dir.path).st_mode }.getOrNull()
        if (mode != null && OsConstants.S_ISLNK(mode)) dir.delete()
        File(dir, "apt/archives/partial").mkdirs()
    }

    private companion object {
        const val TAG = "Bootstrap"
        const val BASH_PATH = "bin/bash"
        const val DPKG_INFO = "var/lib/dpkg/info"
        const val SHIM_NAME = "repack-deb"
        const val APPLIB_NAME = "applib"
        const val PROOT_NAME = "libproot.so"

        // Programs, shipped as libraries because that directory stays executable
        // whatever the app targets.
        const val RELOCATE_NAME = "librelocate.so"

        // The lock `repack-deb` takes, which its cleanup of `repack-deb.*` does
        // not match.
        const val LOCK_PATH = "tmp/.repack-deb-lock"

        // What the relocator exits with when the lock stays busy for 30 s.
        const val LOCK_BUSY = 75

        // A userland whose stamp holds any other value is corrected at start.
        const val STAMP_PATH = "var/lib/harness/corrected"
        const val CORRECTION_VERSION = "1"

        // Once per process, whichever activity asks.
        val started = AtomicBoolean(false)
    }
}

/**
 * The pid in the name of an entry proot leaves in its temporary directory, or
 * null for a name proot does not make.
 */
fun prootEntryPid(name: String): Int? = PROOT_ENTRY_PREFIXES.firstNotNullOfOrNull { prefix ->
    if (!name.startsWith(prefix)) return@firstNotNullOfOrNull null
    val rest = name.substring(prefix.length)
    val digits = rest.substringBefore('-', missingDelimiterValue = "")
    if (digits.isEmpty() || !digits.all(Char::isDigit)) null else digits.toIntOrNull()
}

private val PROOT_ENTRY_PREFIXES =
    listOf("proot-", "proot-tmpfs--", "mountinfo-", "auxv-", "prootseq-", "prootshm-")

/**
 * Removes each entry in [tmp] that proot made for a pid no process has. A pid
 * reused since keeps its entry until that process ends.
 */
fun removeProotLeftovers(tmp: File) {
    for (entry in tmp.listFiles().orEmpty()) {
        val pid = prootEntryPid(entry.name) ?: continue
        if (File("/proc/$pid").exists()) continue
        removeTree(entry)
    }
}

/**
 * Deletes [file] and everything under it without following links. Each
 * directory is made `0700` before it is read, since glue can carry modes that
 * forbid listing it or deleting from it.
 */
fun removeTree(file: File) {
    val path = file.toPath()
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
        file.listFiles()?.forEach(::removeTree)
    }
    file.delete()
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")

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
 * again whenever the prefix has no executable `bash`, and finds an empty directory
 * here. A directory holding anything answers [REPORT] instead -- its
 * contents were written by a binary reading the stamped-in path, and where they
 * belong is not a choice staging can make on its own.
 */
fun homeAction(exists: Boolean, isLink: Boolean, entries: Int): HomeAction = when {
    !exists -> HomeAction.LINK
    isLink -> HomeAction.KEEP
    entries == 0 -> HomeAction.REPLACE
    else -> HomeAction.REPORT
}

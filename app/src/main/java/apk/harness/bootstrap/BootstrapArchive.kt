package apk.harness.bootstrap

import android.system.Os
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A line of the archive's `SYMLINKS.txt`: a zip carries no symlinks. */
data class SymlinkEntry(val target: String, val linkPath: String)

/** The entry that lists the links, which describes the tree rather than belonging to it. */
const val SYMLINKS_NAME = "SYMLINKS.txt"

/**
 * Whether an archive entry has to come out executable.
 *
 * A zip records a Unix mode in its external attributes and `ZipInputStream` does
 * not expose it, so every entry arrives at the default and a tree extracted
 * without this has no runnable bash. These three prefixes are the rule Termux's
 * own installer applies to the same archives, rather than a guess about which
 * files matter.
 */
private fun isProgram(name: String): Boolean =
    name.startsWith("bin/") ||
        name.startsWith("libexec/") ||
        name.startsWith("lib/apt/methods/")

// The separator is one U+2190, not the two ASCII characters it resembles.
private const val ARROW = '←'

/**
 * The links [text] declares, in the order it declares them.
 *
 * A line the format does not fit -- no arrow, an arrow with nothing on one side
 * of it, the blank line the file ends with -- is dropped. A link whose target or
 * path had to be guessed at would be a link pointing somewhere nobody asked for.
 */
fun parseSymlinks(text: String): List<SymlinkEntry> =
    text.lineSequence()
        .mapNotNull { line ->
            val at = line.indexOf(ARROW)
            if (at <= 0 || at == line.length - 1) return@mapNotNull null
            SymlinkEntry(
                target = line.substring(0, at),
                linkPath = line.substring(at + 1),
            )
        }
        .toList()

/**
 * Unpacks [zip] into [into], calling [onProgress] once per entry, and returns the
 * number of entries written. [SYMLINKS_NAME] is skipped and not counted.
 */
fun extractArchive(zip: InputStream, into: File, onProgress: (Int) -> Unit): Int {
    var written = 0
    val root = into.canonicalFile
    ZipInputStream(zip).use { stream ->
        while (true) {
            val entry = stream.nextEntry ?: break
            if (entry.name == SYMLINKS_NAME) {
                stream.closeEntry()
                continue
            }
            val target = File(root, entry.name).canonicalFile
            // An archive naming ../ would write outside the destination.
            check(target.path.startsWith(root.path + File.separator)) {
                "entry escapes the destination: ${entry.name}"
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> stream.copyTo(out) }
                if (isProgram(entry.name)) target.setExecutable(true, true)
            }
            stream.closeEntry()
            written++
            onProgress(written)
        }
    }
    return written
}

/**
 * Creates each entry of [entries] under [into], replacing anything already there,
 * and returns how many were created.
 *
 * `Os.symlink` rather than anything in `java.io`, which cannot make a link at all;
 * that is what keeps this one function of the file off the JVM and puts its
 * coverage on the device.
 */
fun replaySymlinks(entries: List<SymlinkEntry>, into: File): Int {
    var made = 0
    for (entry in entries) {
        val link = File(into, entry.linkPath)
        link.parentFile?.mkdirs()
        // Deleted without asking first: `delete` unlinks the link itself rather
        // than what it points at, and a link left over from an earlier run points
        // at a target that does not exist yet, which `exists` reports as absent.
        // A false return is the path being clear, which is what was wanted.
        link.delete()
        Os.symlink(entry.target, link.path)
        made++
    }
    return made
}

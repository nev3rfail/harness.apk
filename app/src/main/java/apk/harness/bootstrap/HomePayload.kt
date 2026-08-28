package apk.harness.bootstrap

import java.io.File
import java.io.InputStream

/**
 * What the app tells the agent about itself, carried in the APK as two trees of
 * assets and copied into the agent's home.
 *
 * The trees are walked rather than named file by file, so adding a skill is
 * adding a file to the assets and nothing else. Paths are relative to the tree
 * root, which is what lets one tree be copied under one rule and another under
 * another: the app's own statements are replaced on install, and anything the
 * operator or the agent owns is left where it is.
 *
 * Nothing here takes a `Context`, which is what keeps its tests plain JUnit.
 */

/**
 * Every file under [tree], as paths relative to it.
 *
 * A directory answers [list] with its children and a file answers with nothing,
 * which is how the walk tells them apart. An empty directory would read as a
 * file, and none can exist: the packaging drops them.
 */
fun assetEntries(list: (String) -> Array<String>?, tree: String): List<String> {
    val found = mutableListOf<String>()

    fun walk(relative: String) {
        val path = if (relative.isEmpty()) tree else "$tree/$relative"
        val children = list(path) ?: emptyArray()
        if (children.isEmpty()) {
            if (relative.isNotEmpty()) found += relative
            return
        }
        for (child in children) {
            walk(if (relative.isEmpty()) child else "$relative/$child")
        }
    }

    walk("")
    return found
}

/**
 * Copies [entries] out of [tree] into [into], and reports how many were written.
 *
 * With [overwrite] false an entry already on the device is left exactly as it is,
 * which is what makes a file the agent or the operator owns survive an install.
 */
fun copyAssets(
    entries: List<String>,
    open: (String) -> InputStream,
    tree: String,
    into: File,
    overwrite: Boolean,
): Int {
    var written = 0
    for (entry in entries) {
        val destination = File(into, entry)
        if (destination.exists() && !overwrite) continue
        destination.parentFile?.mkdirs()
        open("$tree/$entry").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        written++
    }
    return written
}

/**
 * Writes each of [names] into [into] from the assets root.
 *
 * A name already on the device is left exactly as it is, so an edit made there
 * stands and deleting the file is how the device asks for the shipped one back.
 * The execute bit is set whichever way the file got there: a copy carries
 * content and not permissions, and an editor on the device can take the bit off
 * a file this leaves alone.
 */
fun stageScripts(names: List<String>, open: (String) -> InputStream, into: File) {
    for (name in names) {
        val script = File(into, name)
        if (!script.isFile) {
            open(name).use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
        }
        script.setExecutable(true, true)
    }
}

/**
 * Whether the payload has yet to be staged for [installed].
 *
 * The stamp holds the installed APK's own timestamp rather than a version name,
 * so a reinstall of one build delivers its content again -- which is what a
 * device being iterated on needs -- while a launch that changes nothing does no
 * work. An unreadable stamp is a stamp that does not match, so the answer to one
 * is another copy.
 */
fun payloadDue(stamp: File, installed: String): Boolean =
    stamp.takeIf { it.isFile }?.runCatching { readText().trim() }?.getOrNull() != installed

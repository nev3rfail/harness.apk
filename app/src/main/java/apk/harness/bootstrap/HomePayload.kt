package apk.harness.bootstrap

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 * Writes each of [names] into [into] from the assets root, replacing what is
 * there.
 *
 * The shipped bytes go to a temporary file that is made executable and renamed
 * over the script, so a shell in another tab reading the old copy keeps reading
 * it, and a launcher starting the script by path never finds it absent or
 * without its execute bit.
 *
 * A copy on the device that differs from the shipped one and from its `.bak`
 * is copied to `.bak` first and reported to [onBackup], so an edit survives one
 * update. Local changes belong in `launcher.local.sh`, which nothing writes.
 */
fun stageScripts(
    names: List<String>,
    open: (String) -> InputStream,
    into: File,
    onBackup: (File) -> Unit,
) {
    for (name in names) {
        val shipped = open(name).use { it.readBytes() }
        val script = File(into, name)
        val backup = File(into, "$name.bak")
        if (script.isFile) {
            val current = script.readBytes()
            val kept = backup.isFile && backup.readBytes().contentEquals(current)
            if (!current.contentEquals(shipped) && !kept) {
                script.copyTo(backup, overwrite = true)
                onBackup(backup)
            }
        }
        val temporary = File(into, "$name.tmp")
        temporary.writeBytes(shipped)
        temporary.setExecutable(true, true)
        Files.move(
            temporary.toPath(),
            script.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
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

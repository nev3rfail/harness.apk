package apk.harness.bootstrap

import java.io.File

/** A substitution the relocator can make: the two sides are the same length. */
data class RelocationPair(val old: String, val new: String)

/**
 * The substitutions that move a Termux tree under [applicationId].
 *
 * The replacement is written into stripped binaries, so it cannot change length.
 * `/data/data/com.termux/files` is 27 bytes, which an eleven-character id affords
 * by naming the directory below it in four -- `root` for the tree, `cach` for the
 * cache apt keeps outside it. One pair per subtree rather than per directory,
 * because `files/usr`, `files/home` and `files/usr/tmp` all sit under `files`.
 */
fun relocationPairs(applicationId: String): List<RelocationPair> {
    require(applicationId.length == ID_LENGTH) {
        "an application id of $ID_LENGTH characters is needed to relocate a " +
            "bootstrap; '$applicationId' has ${applicationId.length}"
    }
    val data = "$ANDROID_DATA/$applicationId"
    return listOf(
        RelocationPair("$TERMUX_DATA/files", "$data/root"),
        RelocationPair("$TERMUX_DATA/cache", "$data/cach"),
    )
}

/**
 * Rewrites the bare `/data/data/com.termux` in dpkg's file manifests and returns
 * how many files changed.
 *
 * dpkg lists the directories a package owns as well as its files, and the app data
 * directory is one of them. That path is 21 bytes against 22 for an
 * eleven-character id, so no equal-length substitution reaches it and the byte
 * pass leaves every manifest claiming a directory that does not exist. A manifest
 * is text, so length does not bind here.
 *
 * This runs after the byte pass. Run before it, the longer paths would become
 * `/data/data/<id>/files/...`, which match neither pair.
 */
fun rewriteManifests(dpkgInfo: File, applicationId: String): Int {
    val replacement = "$ANDROID_DATA/$applicationId"
    var changed = 0
    val lists = dpkgInfo.listFiles { file -> file.isFile && file.name.endsWith(".list") }
    for (list in lists ?: emptyArray()) {
        val text = list.readText()
        // A manifest with nothing left to fix is not rewritten, so a second run
        // neither touches the tree nor reports work it did not do.
        if (!text.contains(TERMUX_DATA)) continue
        list.writeText(text.replace(TERMUX_DATA, replacement))
        changed++
    }
    return changed
}

private const val TERMUX_DATA = "/data/data/com.termux"
private const val ANDROID_DATA = "/data/data"
private const val ID_LENGTH = 11

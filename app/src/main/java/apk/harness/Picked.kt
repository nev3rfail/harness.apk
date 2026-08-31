package apk.harness

import java.io.File

// What the platform's pickers hand back, read as what the app needs.
//
// A picker answers with a document, and an agent takes a working directory. The
// derivation here is a string rule about one provider, so what it produces is
// proved against the filesystem before anything is started in it.

/** What a picked folder turned out to be. */
sealed interface Picked {
    /** A directory that can be worked in, spelled the one way. */
    data class Folder(val path: String) : Picked

    /** Not one, in the words the operator is shown. */
    data class Refused(val reason: String) : Picked
}

/** The one document authority that describes a real filesystem mount. */
const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

/** The volume name the primary external storage answers to. */
private const val PRIMARY = "primary"

/**
 * The second root that authority publishes, which is the primary volume's
 * documents directory and the folder the picker offers first.
 */
private const val HOME = "home"

/** What [HOME] resolves to, under the primary volume. */
private const val HOME_DIRECTORY = "Documents"

/** Where the platform mounts every other volume. */
private const val VOLUMES = "/storage"

/**
 * The directory a tree document id is relative to, or null for an id that names
 * none.
 *
 * [primary] is the primary volume's root, passed in because the platform is the
 * only thing that knows it. The volumes beside it are mounted at
 * `/storage/<volume>`, which is where removable media appears.
 *
 * Only [EXTERNAL_STORAGE] describes a mount. Downloads and media hand out opaque
 * ids over a database and a cloud provider has no local path at all, so a path
 * derived from either would name nothing.
 */
fun documentRoot(authority: String, documentId: String, primary: String): String? {
    if (authority != EXTERNAL_STORAGE) return null
    val colon = documentId.indexOf(':')
    if (colon <= 0) return null

    return when (val volume = documentId.substring(0, colon)) {
        PRIMARY -> primary
        HOME -> "$primary/$HOME_DIRECTORY"
        else -> "$VOLUMES/$volume"
    }
}

/**
 * The filesystem path a tree document id names, or null for one that names none.
 *
 * The answer is contained in the root it is relative to: a document id climbing
 * out of it answers null rather than a path elsewhere on the device. That is the
 * first of two containment checks and the cheaper one -- the caller
 * canonicalises and checks again, which is what resolves a link this cannot see.
 */
fun documentPath(authority: String, documentId: String, primary: String): String? {
    val root = documentRoot(authority, documentId, primary) ?: return null
    val relative = documentId.substringAfter(':')
    val joined = normalise(if (relative.isEmpty()) root else "$root/$relative")
    return joined.takeIf { it == root || it.startsWith("$root/") }
}

/**
 * [path] with its `.` and `..` segments resolved, or the empty string for one
 * that climbs above the root.
 *
 * The empty string fails every containment check, which is the answer a path
 * that leaves its volume wants.
 */
private fun normalise(path: String): String {
    val segments = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return "" else segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

/** What the probe file's name begins with, and the text written into it. */
private const val PROBE = ".harness-probe"

/**
 * [path] as a directory that can be worked in, or a refusal saying why not.
 *
 * Canonicalising is the first thing done and the answer is the canonical
 * spelling: one directory reached by several names is one project, and a path
 * spelled a second way is a second row for one place. It is also the containment
 * check that a rule about text cannot make, since it is what resolves the links
 * the platform keeps.
 *
 * The last step writes. External storage is a FUSE mount and what may be done
 * there varies by directory, so `canWrite` is a claim and a byte out and back is
 * an answer -- and writing is the agent's first act in a workspace anyway.
 *
 * [under] is the directory the answer has to stay inside, which is the root the
 * document id was relative to.
 */
fun openFolder(path: String, under: String): Picked {
    val canonical = runCatching { File(path).canonicalFile }.getOrNull()
        ?: return Picked.Refused("That folder cannot be resolved.")
    val root = runCatching { File(under).canonicalFile }.getOrNull()
        ?: return Picked.Refused("Shared storage is not mounted.")

    if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
        return Picked.Refused("That folder leads outside ${root.path}.")
    }
    if (!canonical.isDirectory) return Picked.Refused("That is not a directory.")

    // Named for this attempt, so a probe left behind by one that died is not
    // written over and a folder holding a file of that name keeps it.
    val probe = File(canonical, PROBE + "-" + System.nanoTime())
    val written = runCatching {
        probe.writeText(PROBE)
        probe.readText() == PROBE
    }.getOrDefault(false)
    runCatching { probe.delete() }
    if (!written) return Picked.Refused("That folder cannot be written to.")

    return Picked.Folder(canonical.path)
}

/**
 * [name] as something a directory can hold.
 *
 * A provider is another app, and the name it offers decides where bytes land, so
 * what comes back is one path segment and nothing else: what [FOLDED] holds
 * becomes a dash, a name that is only dots or only space names the directory
 * rather than a file in it and is replaced, and what is left is cut to what a
 * filesystem takes.
 *
 * The cut is by encoded length rather than by characters, since that is what the
 * limit is counted in, and it leaves room for the number [freeName] may add.
 */
fun plainName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.all { it == '.' }) return UNNAMED
    return cut(trimmed.map { if (it in FOLDED) '-' else it }.joinToString(""), NAME_BYTES)
}

/**
 * What a name may not carry into a directory.
 *
 * Both separators, because the name is what decides where the bytes land. A
 * space as well, because the name is written into the prompt as an `@` reference
 * and a reference ends at one.
 */
private const val FOLDED = """/\ """

/**
 * [name] cut to at most [bytes] once encoded, on a character boundary.
 *
 * The walk steps by code unit, and a character outside the basic plane is two of
 * them. Half of one encodes to less than the whole does, so a cut landing
 * between the halves fits and would stop there; the last step is what puts it
 * back onto a character.
 */
private fun cut(name: String, bytes: Int): String {
    if (name.toByteArray().size <= bytes) return name
    var end = name.length
    while (end > 0 && name.substring(0, end).toByteArray().size > bytes) end--
    if (end > 0 && name[end - 1].isHighSurrogate()) end--
    return name.substring(0, end)
}

/** What a document nothing names is called once it is in a directory. */
private const val UNNAMED = "document"

/**
 * The room a name is given, under the 255 bytes a filesystem allows, so that a
 * number and an extension still fit.
 */
private const val NAME_BYTES = 200

/**
 * [name], or the first numbered variant of it that [taken] does not claim.
 *
 * Nothing is ever overwritten, so a name in use gains a number: `shot.png`, then
 * `shot-1.png`, then `shot-2.png`.
 *
 * The number goes before the extension, which is the text after the last dot
 * that is not the name's first character. `README` becomes `README-1` and
 * `.bashrc` becomes `.bashrc-1`, rather than either growing a suffix in front of
 * what is not an extension.
 */
fun freeName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name

    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""

    var number = 1
    while (true) {
        val candidate = "$stem-$number$extension"
        if (!taken(candidate)) return candidate
        number++
    }
}

package apk.harness

import java.io.File
import org.json.JSONObject

/**
 * Telling the agent that a directory the operator chose is trusted.
 *
 * Claude Code asks whether a working directory is trusted and records the answer
 * under `projects[<path>].hasTrustDialogAccepted` in `~/.claude.json`. A tab is
 * opened by tapping a row, which is the operator trusting that directory, so
 * asking again in the terminal is a question with no content.
 */

private const val PROJECTS = "projects"
private const val ACCEPTED = "hasTrustDialogAccepted"

/**
 * [config] with [path] marked trusted, or null when it already is.
 *
 * Null rather than an identical string, so a caller can skip the write. That
 * matters more than it looks: the file belongs to the agent, which rewrites it
 * as it runs, and every write from this side is a chance to lose whatever the
 * agent wrote in between.
 *
 * Text that does not parse is left alone. The file is the agent's, and the
 * agent rewrites it by truncating and writing, so text that is not a document
 * is a document caught halfway. Replacing it would cost the account the agent
 * is signed in with; refusing costs the trust dialog being asked.
 */
fun withTrustedProject(config: String, path: String): String? {
    val root = runCatching { JSONObject(config) }.getOrNull() ?: return null
    val projects = root.optJSONObject(PROJECTS) ?: JSONObject()
    val project = projects.optJSONObject(path) ?: JSONObject()

    if (project.optBoolean(ACCEPTED)) return null

    project.put(ACCEPTED, true)
    projects.put(path, project)
    root.put(PROJECTS, projects)
    return root.toString()
}

/**
 * Marks [path] trusted in the agent's config file.
 *
 * Written through a temporary file and a rename, so a config the agent reads
 * while this runs is either the old one or the new one and never half of
 * either. A failure is swallowed: the cost is the dialog being asked, which is
 * a great deal less than the cost of a tab that does not open.
 *
 * A rename that fails is given up on rather than written in place: a write
 * straight onto the live file is the halfway document the reader above refuses
 * to act on, handed to the agent instead.
 */
fun trustProject(config: File, path: String) {
    runCatching {
        // An absent file is a config with nothing in it, which is a document.
        // Empty text is not, and reading it as one would make a home with no
        // config indistinguishable from a config caught halfway through a write.
        val current = if (config.isFile) config.readText() else "{}"
        val updated = withTrustedProject(current, path) ?: return
        val temporary = File(config.parentFile, "${config.name}.trust")
        temporary.writeText(updated)
        if (!temporary.renameTo(config)) temporary.delete()
    }
}

/**
 * Telling git that a directory the operator chose may be worked in.
 *
 * Shared storage synthesises ownership as `media_rw` rather than as the app's
 * uid, and git refuses a repository owned by someone other than the user running
 * it. `safe.directory` is the exception list.
 *
 * It is read from the global config alone. The guard exists so that a repository
 * cannot vouch for itself, and a value arriving through the environment is
 * ignored for the same reason.
 */

private const val SAFE = "safe"
private const val DIRECTORY = "directory"

/**
 * [config] with [path] named safe, or null when it already is.
 *
 * One path per entry rather than a wildcard: the operator picked a directory,
 * and that directory is what is vouched for.
 *
 * The key is multi-valued and repeated sections merge, so this appends and never
 * parses. It reads only far enough to answer whether the entry is already there,
 * matching the value whole so that one path is not read as the prefix of a
 * longer one.
 *
 * The value is quoted. A directory name may hold a `#` or a `;`, which begin a
 * comment in this format and would otherwise cut the path short -- into a path
 * git cannot find, and into one this never recognises again, so that a second
 * entry is appended for it every time a session starts there.
 */
fun withSafeDirectory(config: String, path: String): String? {
    if (namesDirectory(config, path)) return null
    val gap = if (config.isEmpty() || config.endsWith("\n")) "" else "\n"
    return "$config$gap[$SAFE]\n\t$DIRECTORY = ${quoted(path)}\n"
}

/**
 * Names [path] safe in the agent's git config.
 *
 * Written through a temporary file and a rename, so a git reading it while this
 * runs sees the old config or the new one and never half of either. A failure is
 * swallowed: the cost is git asking about ownership, which is a great deal less
 * than the cost of a folder that does not open.
 */
fun markSafeDirectory(config: File, path: String) {
    runCatching {
        // An absent file is a config holding no entries, which is what empty
        // text is here -- unlike the agent's own config, this format has no
        // document that has to be there before anything can be added to it.
        val current = if (config.isFile) config.readText() else ""
        val updated = withSafeDirectory(current, path) ?: return
        val temporary = File(config.parentFile, "${config.name}.safe")
        temporary.writeText(updated)
        if (!temporary.renameTo(config)) temporary.delete()
    }
}

/**
 * [path] as a value this format reads back as the path and nothing else.
 *
 * Quoted, and the characters this format spells with a backslash spelled that
 * way. A line break inside a value is not a value but a parse error, and a
 * config that does not parse is one every git command in the home fails on
 * rather than only the ones in this directory.
 */
private fun quoted(path: String): String = buildString {
    append('"')
    for (character in path) {
        when (character) {
            '"', '\\' -> append('\\').append(character)
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

/**
 * True when [config] already names [path] safe.
 *
 * The section is tracked rather than the key alone, so a `directory` belonging
 * to something else is not read as this one. A header may carry a comment after
 * it, and an entry may follow it on the same line; both are read the way git
 * reads them, because a config this fails to recognise is one that gains a
 * second entry for the same directory every time a session starts in it.
 */
private fun namesDirectory(config: String, path: String): Boolean {
    var inSection = false
    for (raw in config.lineSequence()) {
        var line = raw.trim()
        if (line.startsWith("[")) {
            inSection = line.substringAfter('[').substringBefore(']').trim()
                .equals(SAFE, ignoreCase = true)
            // What follows the header on its own line belongs to the section it
            // opened.
            line = line.substringAfter(']', "").trim()
        }
        if (line.startsWith("#") || line.startsWith(";")) continue
        if (!inSection || '=' !in line) continue
        if (!line.substringBefore('=').trim().equals(DIRECTORY, ignoreCase = true)) continue
        if (unquoted(line.substringAfter('=')) == path) return true
    }
    return false
}

/**
 * The path a value holds, whether it is quoted or bare.
 *
 * A bare value ends where a comment begins and carries no surrounding space. A
 * quoted one ends at its closing quote whatever follows that, and keeps the
 * space inside it with this format's escapes undone.
 */
private fun unquoted(value: String): String {
    val text = value.trim()
    if (!text.startsWith('"')) return text.substringBefore('#').substringBefore(';').trim()

    val path = StringBuilder()
    var index = 1
    while (index < text.length && text[index] != '"') {
        val character = text[index]
        if (character != '\\' || index + 1 == text.length) {
            path.append(character)
            index++
            continue
        }
        index++
        path.append(
            when (val escaped = text[index]) {
                'n' -> '\n'
                't' -> '\t'
                else -> escaped
            }
        )
        index++
    }
    return path.toString()
}


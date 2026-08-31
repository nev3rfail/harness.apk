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
        val current = if (config.isFile) config.readText() else ""
        val updated = withTrustedProject(current, path) ?: return
        val temporary = File(config.parentFile, "${config.name}.trust")
        temporary.writeText(updated)
        if (!temporary.renameTo(config)) temporary.delete()
    }
}

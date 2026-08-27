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
 * Text that does not parse is replaced rather than merged into. The file is
 * seeded by the app and owned by the agent; text that is neither is not
 * something to preserve half of.
 */
fun withTrustedProject(config: String, path: String): String? {
    val root = runCatching { JSONObject(config) }.getOrDefault(JSONObject())
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
 */
fun trustProject(config: File, path: String) {
    runCatching {
        val current = if (config.isFile) config.readText() else ""
        val updated = withTrustedProject(current, path) ?: return
        val temporary = File(config.parentFile, "${config.name}.trust")
        temporary.writeText(updated)
        if (!temporary.renameTo(config)) {
            config.writeText(updated)
            temporary.delete()
        }
    }
}

package apk.harness.agents

import apk.harness.chats.Chat
import apk.harness.chats.Project
import apk.harness.chats.byRecency
import java.io.File

/**
 * The agent CLIs the app can run, and what each of them needs said differently.
 *
 * Everything Claude-specific about reading history and spelling flags is named
 * here.
 *
 * [home] throughout is the agent's home directory -- the one the app hands the
 * process -- and not a config directory inside it. Where a backend keeps its
 * files under that home is the backend's own business.
 *
 * This package reads files and returns data, so it runs on the JVM the tests
 * use.
 *
 * The caller binds Claude's editor protocol for every tab it opens -- the
 * lockfile, the port in the environment, the authorization header -- whichever
 * backend the tab runs. That protocol is outside this interface.
 */
interface AgentBackend {
    /** What a chat carries in [Chat.backendId] to say which backend read it. */
    val id: String

    /** The script under `HARNESS_HOME` that launches it. */
    val script: String

    /** This backend's history under [home]. */
    fun projects(home: File): List<Project>

    /** The processes of this backend that are running, as they report themselves. */
    fun running(home: File): List<RunningSession>

    /** Arguments that reopen the conversation [sessionId] names. */
    fun resume(sessionId: String): List<String>

    /** Arguments that begin a conversation under an id the app chose. */
    fun start(sessionId: String): List<String>

    /** Arguments that point the CLI at the MCP servers [file] describes. */
    fun mcpConfig(file: File): List<String>

    /**
     * A line typed into an agent already running that moves it to [sessionId],
     * or null for a CLI that takes no such command.
     */
    fun switch(sessionId: String): String?

    /**
     * What is typed into this CLI's prompt and not sent, read off [viewport] --
     * the terminal's visible grid as plain text.
     *
     * The empty string says the prompt is there and holds nothing. Null says the
     * prompt is not on the viewport, which a scrollback scrolled up looks like
     * and an unrecognised prompt looks like too. A caller about to destroy the
     * prompt's contents acts on the empty string alone.
     */
    fun promptText(viewport: String): String?

    /** The conversation a fresh tab in [directory] lands in, or null. */
    fun mostRecent(home: File, directory: File): String?
}

/**
 * One running agent, as its own process describes it.
 *
 * [status] is `busy`, `idle`, or null for a record that omits it -- which a
 * process writes before it has anything to say about itself.
 */
data class RunningSession(
    val pid: Int,
    val sessionId: String,
    val status: String?,
)

/** Every backend the app knows. The single place a second one is added. */
val BACKENDS: List<AgentBackend> = listOf(ClaudeBackend)

/**
 * The conversation a tab opens on: [mostRecent] when it is free, and [newId]
 * otherwise.
 *
 * The newest transcript is the one being written, and [taken] -- the sessions
 * the backend's roster names -- says who is writing it. A conversation another
 * process is in gets a fresh id, because two agents appending to one transcript
 * is a conversation with two authors and no way to read it back.
 */
fun freshSession(mostRecent: String?, taken: Set<String>, newId: () -> String): String =
    mostRecent?.takeIf { it !in taken } ?: newId()

/** A tab the app is running, as the drawer needs to know it. */
data class OpenTab(
    val sessionId: String,
    val directory: File,
    val label: String,
    /** The backend running in the tab, which the tab's synthetic chat carries. */
    val backendId: String,
)

/**
 * Every backend's history, merged by project path, most recently touched first.
 *
 * A directory two agents have both been run in is one project holding both sets
 * of chats rather than two rows naming the same place, and reachable when any of
 * them found it so. Every backend's chats naming no directory fold into the one
 * project that names none.
 */
fun mergedProjects(backends: List<AgentBackend>, home: File): List<Project> =
    backends.flatMap { it.projects(home) }
        .groupBy { it.path }
        .map { (path, found) ->
            Project(
                path = path,
                reachable = found.any { it.reachable },
                chats = found.flatMap { it.chats }.sortedByDescending { it.modified },
            )
        }
        .sortedWith(byRecency)

/**
 * [projects] with a row for every tab that has no transcript to be read from.
 *
 * A tab opened on a conversation the app named has nothing on disk until the
 * first message is sent, and a row is the only way to reach a tab or close it.
 * Each such tab joins the project its working directory names, which is added
 * when the history holds none. A tab has a working directory, so the project
 * naming none is passed over rather than keyed.
 */
fun withOpenTabs(projects: List<Project>, tabs: List<OpenTab>): List<Project> {
    val listed = projects.flatMapTo(HashSet()) { project -> project.chats.map { it.sessionId } }
    val extra = tabs.filter { it.sessionId !in listed }
        .groupBy({ directoryKey(it.directory.path) }, ::placeholder)
    if (extra.isEmpty()) return projects

    val known = projects.mapNotNullTo(HashSet()) { it.path?.let(::directoryKey) }
    val merged = projects.map { project ->
        project.path?.let { path -> extra[directoryKey(path)] }
            ?.let { project.copy(chats = it + project.chats) }
            ?: project
    }
    val added = extra.filterKeys { it !in known }.map { (path, chats) ->
        // An agent is running in the directory, so it is one this process can
        // enter, and the path came from the tab rather than from anything read.
        Project(path = path, reachable = true, chats = chats)
    }
    return (merged + added).sortedWith(byRecency)
}

/**
 * The row a tab with no transcript is drawn as.
 *
 * [Chat.transcript] serves as a list key and nothing else here, so the file the
 * tab will write names the row whether or not it exists yet.
 */
private fun placeholder(tab: OpenTab) = Chat(
    sessionId = tab.sessionId,
    title = tab.label,
    lastPrompt = null,
    modified = System.currentTimeMillis(),
    transcript = File(tab.directory, tab.sessionId),
    backendId = tab.backendId,
)

/**
 * One spelling of a directory, so a tab and a project name it the same way.
 *
 * A project's path is the string a transcript gave it and a tab's comes from a
 * [File], and the two disagree about a trailing separator. Both sides of the
 * lookup pass through here, so one directory is one row however it was spelled.
 */
private fun directoryKey(path: String) = File(path).path

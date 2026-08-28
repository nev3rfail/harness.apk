package apk.harness.agents

import apk.harness.chats.Project
import java.io.File
import org.json.JSONObject

/**
 * Claude Code: its `.claude` layout, its flags, and its roster of processes.
 *
 * Everything it reads is a file the CLI writes for itself. There is no protocol
 * here, nothing to attach to and no token: a directory listing and a handful of
 * small JSON documents say all of it.
 */
object ClaudeBackend : AgentBackend {
    override val id: String = "claude"
    override val script: String = "agent.sh"

    /** The history under `.claude`, with every chat tagged as this backend's. */
    override fun projects(home: File): List<Project> =
        apk.harness.chats.projects(config(home)).map { project ->
            project.copy(chats = project.chats.map { it.copy(backendId = id) })
        }

    override fun running(home: File): List<RunningSession> = roster(home, File(PROC))

    /**
     * The roster under `sessions/`, checked against the process table in
     * [procDirectory].
     *
     * A process writes `sessions/<pid>.json` for itself and does not always
     * remove it, so a killed agent leaves its record behind. [stillRunning] is
     * what decides which records are about a process that exists.
     */
    fun roster(home: File, procDirectory: File): List<RunningSession> {
        val sessions = File(config(home), "sessions")
        val files = sessions.listFiles { file -> file.name.endsWith(ROSTER_SUFFIX) }.orEmpty()
        return files.mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val record = runCatching { JSONObject(text) }.getOrNull() ?: return@mapNotNull null
            val entry = rosterEntry(record) ?: return@mapNotNull null
            entry.takeIf { stillRunning(procDirectory, it.pid, record.string("procStart")) }
        }
    }

    override fun resume(sessionId: String): List<String> = listOf("--resume", sessionId)

    override fun start(sessionId: String): List<String> = listOf("--session-id", sessionId)

    override fun mcpConfig(file: File): List<String> = listOf("--mcp-config", file.absolutePath)

    override fun switch(sessionId: String): String = "/resume $sessionId"

    /**
     * The newest transcript filed under [directory], by its name.
     *
     * A transcript is named after the conversation in it, so this answers
     * without opening a byte of one. It is the conversation `--continue` would
     * pick, resolved before the agent starts.
     */
    override fun mostRecent(home: File, directory: File): String? {
        val project = File(config(home), "projects/${flatten(directory.absolutePath)}")
        return project.listFiles { file -> file.name.endsWith(TRANSCRIPT_SUFFIX) }
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }
            ?.name
            ?.removeSuffix(TRANSCRIPT_SUFFIX)
    }

    /** The CLI's own directory inside an agent home. */
    private fun config(home: File) = File(home, ".claude")

    private const val PROC = "/proc"
    private const val ROSTER_SUFFIX = ".json"
    private const val TRANSCRIPT_SUFFIX = ".jsonl"
}

/**
 * The name a project's directory takes under `projects/`.
 *
 * Every character that divides a path becomes a dash: the separator of either
 * host family, a drive's colon, and a dot. The app's own working directory
 * `/data/user/0/dev.harness/files` is filed as `-data-user-0-dev-harness-files`,
 * and a desktop's `D:\Users\nev3rfail` as `D--Users-nev3rfail`.
 */
fun flatten(path: String): String =
    path.map { if (it in SEPARATORS) '-' else it }.joinToString("")

/**
 * One roster record, or null when it names no session or no process.
 *
 * The desktop CLI adds `messagingSocketPath` for its cross-session channel and
 * the device CLI does not; both write everything read here.
 */
fun rosterEntry(record: JSONObject): RunningSession? {
    val sessionId = record.string("sessionId") ?: return null
    val pid = record.optInt("pid", 0).takeIf { it > 0 } ?: return null
    return RunningSession(pid = pid, sessionId = sessionId, status = record.string("status"))
}

/**
 * True when [pid] names a process that started at [procStart].
 *
 * The start time is field 22 of `<procDirectory>/<pid>/stat`, and it is what
 * separates the process the record was written about from a later one that
 * happens to hold the same pid. The `comm` field before it is parenthesised and
 * may itself hold spaces and brackets, so the fields are counted from after the
 * last `)` in the line.
 *
 * A record carrying no start time is true whenever the directory is there,
 * since there is nothing to disagree with.
 */
fun stillRunning(procDirectory: File, pid: Int, procStart: String?): Boolean {
    val directory = File(procDirectory, pid.toString())
    if (!directory.isDirectory) return false
    if (procStart == null) return true

    val line = runCatching { File(directory, "stat").readText() }.getOrNull() ?: return false
    val fields = line.substringAfterLast(')', "").trim().split(' ')
    return fields.getOrNull(START_TIME) == procStart
}

/**
 * A string field, or null when it is absent, empty, or not a string.
 *
 * Read with [JSONObject.opt] rather than `optString` for the reason
 * `apk.harness.chats` gives: the two implementations of that method disagree
 * about a value that is not a string, and the disagreement is between the
 * device runtime and the test classpath.
 */
private fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotEmpty() }

/** What [flatten] folds into a dash. */
private const val SEPARATORS = "/\\.:"

/** Field 22 of `stat`, counted from the field after the `comm` field. */
private const val START_TIME = 19

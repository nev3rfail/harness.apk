package apk.harness.agents

import apk.harness.chats.Project
import apk.harness.chats.flatten
import java.io.File
import java.io.OutputStream
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

    override fun rosterDirectory(home: File): File = File(config(home), "sessions")

    override fun parseRecord(text: String): RecordRead {
        val record = runCatching { JSONObject(text) }.getOrNull() ?: return RecordRead.Unparsable
        val entry = rosterEntry(record) ?: return RecordRead.Empty
        return RecordRead.Entry(entry.pid, entry.sessionId, entry.status, record.string("procStart"))
    }

    /**
     * The roster under `sessions/`, checked against the process table in
     * [procDirectory], as [readRoster] reads one.
     */
    fun roster(home: File, procDirectory: File): List<RunningSession> =
        readRoster(rosterDirectory(home), ::parseRecord, ProcTable(procDirectory))

    override fun resume(sessionId: String): List<String> = listOf("--resume", sessionId)

    override fun start(sessionId: String): List<String> = listOf("--session-id", sessionId)

    override val headlessScript: String = "headless.sh"

    override fun headless(sessionId: String, resume: Boolean): List<String> =
        listOf(
            "-p",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--verbose",
            "--include-partial-messages",
        ) + if (resume) resume(sessionId) else start(sessionId)

    override fun parseEvent(line: String): HeadlessEvent = parseClaudeEvent(line)

    override val provider: String = "Anthropic"

    override fun writeMessage(out: OutputStream, texts: List<String>, images: List<HeadlessImage>) =
        writeClaudeMessage(out, texts, images)

    override fun interrupt(requestId: String): String = claudeInterrupt(requestId)

    override fun mcpConfig(file: File): List<String> = listOf("--mcp-config", file.absolutePath)

    override fun switch(sessionId: String): String = "/resume $sessionId"

    override fun promptText(viewport: String): String? = promptLine(viewport)

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
            ?.filter { it.isFile && isSessionId(it.name.removeSuffix(TRANSCRIPT_SUFFIX)) }
            ?.maxByOrNull { it.lastModified() }
            ?.name
            ?.removeSuffix(TRANSCRIPT_SUFFIX)
    }

    override fun hasTranscript(home: File, sessionId: String): Boolean =
        transcript(home, sessionId) != null

    override fun transcriptModified(home: File, sessionId: String): Long =
        transcript(home, sessionId)?.lastModified() ?: 0L

    /**
     * The transcript of [sessionId] in any project directory, or null.
     *
     * Every project, because a chat whose transcript names no directory opens in
     * the home while its transcript is filed elsewhere.
     */
    private fun transcript(home: File, sessionId: String): File? {
        if (!isSessionId(sessionId)) return null
        val projects = File(config(home), "projects").listFiles().orEmpty()
        return projects.asSequence()
            .map { File(it, sessionId + TRANSCRIPT_SUFFIX) }
            .firstOrNull { it.isFile }
    }

    /** The CLI's own directory inside an agent home. */
    private fun config(home: File) = File(home, ".claude")

    private const val PROC = "/proc"
    private const val TRANSCRIPT_SUFFIX = ".jsonl"
}

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
 * A string field, or null when it is absent, empty, or not a string.
 *
 * Read with [JSONObject.opt] rather than `optString` for the reason
 * `apk.harness.chats` gives: the two implementations of that method disagree
 * about a value that is not a string, and the disagreement is between the
 * device runtime and the test classpath.
 */
internal fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotEmpty() }

/**
 * What the prompt on [viewport] holds, or null when the viewport carries no
 * prompt.
 *
 * The CLI draws its prompt as [PROMPT] and a non-breaking space at the head of a
 * line, with what is typed from the third column on. The transcript above it
 * echoes every sent message behind the same glyph, so the prompt is the last
 * such line on the viewport -- and a viewport scrolled up off the prompt answers
 * null rather than answering for a message sent long ago.
 *
 * The grid is padded to its full width, and the space after the glyph is a
 * non-breaking one, which the JVM does not count as whitespace. Both come off
 * the end of the answer.
 */
fun promptLine(viewport: String): String? = viewport.lineSequence()
    .map { it.trimStart() }
    .lastOrNull { it.startsWith(PROMPT) }
    ?.removePrefix(PROMPT)
    ?.trim { it.isWhitespace() || it == NBSP }

/** The glyph at the head of the CLI's prompt, and of every message it echoes. */
private const val PROMPT = "\u276F"

/** The space the CLI puts between that glyph and what is typed. */
private const val NBSP = '\u00A0'

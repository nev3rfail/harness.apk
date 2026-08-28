package apk.harness.chats

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The projects and chats an agent home holds.
 *
 * The agent keeps one directory per project under `projects/`, named after the
 * project's path with the separators flattened, and one `<session-id>.jsonl`
 * transcript per session inside it. That is the whole index: no database, no
 * daemon, nothing to attach to. This reads it.
 *
 * `spike/chat-tree.py` is the same reading, done from a desktop where a whole
 * file can be parsed. Here it cannot: see [WINDOW_BYTES].
 */

/** How much of a transcript's end is read. */
const val WINDOW_BYTES: Int = 256 * 1024

/** One conversation. */
data class Chat(
    val sessionId: String,
    /** The name someone or the agent gave it, or null for one nobody named. */
    val title: String?,
    /**
     * Something typed into it, or null for a transcript with nothing. The most
     * recent prompt when the transcript records one, and otherwise the earliest
     * the window holds -- which for a short conversation is the first thing
     * said in it.
     */
    val lastPrompt: String?,
    /** The transcript's modification time, which is when the chat last moved. */
    val modified: Long,
    val transcript: File,
    /**
     * The [apk.harness.agents.AgentBackend] this was read from, empty for a chat
     * no backend has claimed. Reading fills it in: this package knows the layout
     * and not whose it is.
     */
    val backendId: String = "",
) {
    /**
     * What to call this in a list. A named chat is named; an unnamed one is what
     * was last said in it; a chat that yielded neither is its own id, which is
     * at least unique.
     */
    val label: String get() = title ?: lastPrompt ?: sessionId.take(ID_PREFIX)
}

/** One project, and the chats filed under it. */
data class Project(
    /** The working directory these chats were held in. */
    val path: String,
    /**
     * False when [path] does not name a directory this process can enter. Such a
     * project is history: its chats can be listed and not resumed.
     */
    val reachable: Boolean,
    /** True when [path] was guessed from the directory name rather than read. */
    val guessed: Boolean,
    /** Most recently touched first. */
    val chats: List<Chat>,
) {
    val modified: Long get() = chats.maxOfOrNull { it.modified } ?: 0L

    /**
     * A short name for the project, for a tab with no room for a path.
     *
     * The last two segments rather than the last one: every project on this
     * device is a `files` directory under an application id, so one segment
     * names them all the same thing. A drawer row draws the path instead --
     * there is room for it there, and a folded name beside the thing it was
     * folded from says one thing twice.
     */
    val name: String
        get() = path.split('/').filter { it.isNotEmpty() }
            .takeLast(NAME_SEGMENTS)
            .joinToString("/")
            .ifEmpty { path }
}

/**
 * Every project under [agentHome], most recently touched first.
 *
 * [agentHome] is the agent's config directory -- `~/.claude` -- not the home it
 * sits in. A missing or unreadable `projects` directory is an empty list rather
 * than a failure: it is what a home with no history looks like.
 *
 * Two directories can flatten to the same real path, so projects are keyed by
 * the path rather than by the directory, and chats from both arrive in one list.
 */
fun projects(agentHome: File): List<Project> {
    val root = File(agentHome, "projects")
    val directories = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()

    val byPath = LinkedHashMap<String, MutableList<Chat>>()
    val guessedPaths = HashSet<String>()

    for (directory in directories) {
        val transcripts = directory.listFiles { file -> file.name.endsWith(SUFFIX) }
            ?.filter { it.isFile }
            .orEmpty()
        if (transcripts.isEmpty()) continue

        val summaries = transcripts.map(::readTranscript)

        // Every turn in a transcript shares one working directory, so any of
        // them names the project. Flattening is lossy, so the directory name
        // answers only when no transcript does.
        val read = summaries.firstNotNullOfOrNull { it.cwd }
        val path = read ?: unflatten(directory.name).also { guessedPaths += it }

        byPath.getOrPut(path) { mutableListOf() } += summaries.map { it.chat }
    }

    return byPath.map { (path, chats) ->
        Project(
            path = path,
            reachable = File(path).isDirectory,
            guessed = path in guessedPaths,
            chats = chats.sortedByDescending { it.modified },
        )
    }.sortedByDescending { it.modified }
}

/** A transcript's chat, and the working directory it names. */
class Transcript(val chat: Chat, val cwd: String?)

/**
 * What one transcript says about itself, read from its last [WINDOW_BYTES].
 *
 * A transcript is appended to for as long as the conversation lasts and has no
 * bound; the largest in this project's own history is twenty-five megabytes, and
 * a list of fifty of them is not a gigabyte of reading on a phone.
 *
 * The window is enough because the records that name a chat are small and are
 * rewritten on almost every turn -- `custom-title`, `agent-name` and
 * `last-prompt` -- and because every conversational record carries the working
 * directory. What the window cannot hold is a chat whose last quarter-megabyte
 * is one enormous record, which arrives with no name and is listed by its id.
 *
 * The time is the file's own, which costs a `stat` rather than a parse.
 */
fun readTranscript(file: File): Transcript {
    val sessionId = file.name.removeSuffix(SUFFIX)
    var title: String? = null
    var agentName: String? = null
    var lastPrompt: String? = null
    var firstPrompt: String? = null
    var cwd: String? = null

    for (line in tail(file)) {
        // Cheap enough to skip a parse on: most of a transcript's bytes are
        // records this reads nothing out of.
        if (!line.startsWith("{")) continue
        val record = runCatching { JSONObject(line) }.getOrNull() ?: continue

        when (record.optString("type")) {
            "custom-title" -> record.text("customTitle")?.let { title = it }
            "agent-name" -> record.text("agentName")?.let { agentName = it }
            "last-prompt" -> record.text("lastPrompt")?.let { lastPrompt = clean(it) }
            // A turn dispatched to a subagent is the parent's work rather than a
            // conversation of its own, so it never supplies a label.
            "user" -> if (firstPrompt == null && !record.optBoolean("isSidechain")) {
                firstPrompt = prompt(record.optJSONObject("message"))
            }
        }
        record.text("cwd")?.let { cwd = it }
    }

    return Transcript(
        chat = Chat(
            sessionId = sessionId,
            title = title ?: agentName,
            lastPrompt = lastPrompt ?: firstPrompt,
            modified = file.lastModified(),
            transcript = file,
        ),
        cwd = cwd,
    )
}

/**
 * The lines of the end of a file.
 *
 * A window into the middle of a file starts in the middle of a record and in the
 * middle of a character, so the first line is dropped whenever the window is not
 * the whole file. A file that cannot be opened yields nothing, which is the same
 * answer as a file that says nothing.
 */
private fun tail(file: File): List<String> = runCatching {
    RandomAccessFile(file, "r").use { handle ->
        val length = handle.length()
        val from = maxOf(0L, length - WINDOW_BYTES)
        handle.seek(from)
        val bytes = ByteArray((length - from).toInt())
        handle.readFully(bytes)
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        if (from == 0L) lines else lines.drop(1)
    }
}.getOrDefault(emptyList())

/**
 * A guess at the path a directory name was flattened from.
 *
 * Flattening maps a separator and a literal dash to the same character, so it
 * cannot be undone. What comes back is right for a plain POSIX path and wrong
 * for one holding a dash, which is why a path recovered this way is marked as a
 * guess rather than shown as fact.
 */
fun unflatten(name: String): String = name.replace('-', '/')

/**
 * The typed text of a user turn, or null for a turn that carried none.
 *
 * Content is a string when the turn is plain text and a list of blocks when it
 * is not, and a turn whose blocks are all tool results carries nothing a person
 * typed.
 *
 * The field is read with [JSONObject.opt] and type-checked rather than with
 * `optString`, because the two implementations of that method disagree about a
 * value that is not a string: one answers with an empty string and the other
 * with the array's own rendering. The disagreement is between the runtime on the
 * device and the one on the test classpath, which is the worst place for it.
 */
private fun prompt(message: JSONObject?): String? {
    when (val content = message?.opt("content")) {
        is String -> return clean(content)
        !is JSONArray -> return null
        else -> for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") != "text") continue
            clean(block.optString("text"))?.let { return it }
        }
    }
    return null
}

/** A string field, or null when it is absent or empty. */
private fun JSONObject.text(key: String): String? =
    optString(key).takeIf { it.isNotEmpty() }

/**
 * One line of a prompt, with the machinery a prompt arrives wrapped in dropped.
 *
 * Reminders, hook output and command wrappers are injected around what was
 * typed, and a label built from them names the harness rather than the chat.
 */
private fun clean(text: String): String? = text.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() && !it.startsWith("<") && !it.startsWith("Caveat:") }

private const val SUFFIX = ".jsonl"
private const val NAME_SEGMENTS = 2
private const val ID_PREFIX = 8

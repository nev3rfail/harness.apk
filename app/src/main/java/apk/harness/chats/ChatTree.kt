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

/**
 * How much of a transcript's start is read.
 *
 * A working directory rides on every conversational record, so a transcript that
 * ends in one enormous record can end without naming one. It cannot begin
 * without one: the first conversational record is at the front of the file, and
 * the field sits near the front of that record -- across transcripts from
 * hundreds of bytes to tens of megabytes, within the first five kilobytes.
 */
const val HEAD_BYTES: Int = 64 * 1024

/**
 * What a project with no directory is called, on its row and in [Project.name].
 *
 * A transcript that names no working directory belongs to no place anyone can
 * name, and the chats of every such transcript are listed under this one word.
 */
const val UNATTRIBUTED = "unattributed"

/**
 * The name a project's directory takes under `projects/`.
 *
 * Every character that divides a path becomes a dash: the separator of either
 * host family, a drive's colon, and a dot. The app's own working directory
 * `/data/user/0/dev.harness/files` is filed as `-data-user-0-dev-harness-files`,
 * and a desktop's `D:\Users\nev3rfail` as `D--Users-nev3rfail`.
 *
 * The mapping loses which character a dash stood for, so it runs one way only.
 * Run forwards against a path a transcript names, it says whether that path is
 * the one the transcript is filed under, which is what [readTranscript] asks.
 */
fun flatten(path: String): String =
    path.map { if (it in SEPARATORS) '-' else it }.joinToString("")

/** What [flatten] folds into a dash. */
private const val SEPARATORS = "/\\.:"

/**
 * True when [path] is the directory named [directory] is filed under.
 *
 * A trailing separator is dropped first: it names the same directory and
 * flattens to a dash the directory's name does not carry.
 */
private fun names(path: String, directory: String?): Boolean =
    directory != null && flatten(path.trimEnd('/', '\\')) == directory

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

    /**
     * The line under the name: what was last said here, or null for a chat whose
     * name is already that.
     *
     * A row says one thing once. An unnamed chat is labelled by its last prompt,
     * so it has nothing left to put underneath; a named chat with no prompt has
     * nothing to put there either.
     */
    val lastLine: String? get() = if (title != null) lastPrompt else null
}

/** One project, and the chats filed under it. */
data class Project(
    /**
     * The working directory these chats were held in, or null for chats whose
     * transcripts name none. There is one project with no path, holding all of
     * them: the directories are exactly what cannot be named, so dividing by
     * them divides by nothing anyone can read.
     */
    val path: String?,
    /**
     * False when [path] does not name a directory this process can enter, and
     * for the project with no path. Such a project is history: its chats can be
     * listed and not started in.
     */
    val reachable: Boolean,
    /** Most recently touched first. */
    val chats: List<Chat>,
) {
    val modified: Long get() = chats.maxOfOrNull { it.modified } ?: 0L

    /** The identity a row and the set of open projects use. No path answers to `""`. */
    val key: String get() = path ?: ""

    /**
     * A short name for the project, for a tab with no room for a path.
     *
     * The last two segments rather than the last one: every project on this
     * device is a `files` directory under an application id, so one segment
     * names them all the same thing. A drawer row draws the path instead --
     * there is room for it there, and a folded name beside the thing it was
     * folded from says one thing twice. A project with no path answers with
     * [UNATTRIBUTED], the word its row draws.
     */
    val name: String
        get() {
            // A local, so the fallback is reached without a null check on every
            // step of the chain.
            val path = path ?: return UNATTRIBUTED
            return path.split('/').filter { it.isNotEmpty() }
                .takeLast(NAME_SEGMENTS)
                .joinToString("/")
                .ifEmpty { path }
        }
}

/**
 * [path] with the agent's home folded to `~`, for a row with no room for it.
 *
 * A drawer row is one line wide and every project on this device sits under one
 * home, so the shared head of the path is the part worth losing: drawn whole and
 * ellipsised, `/data/user/0/dev.harness/files` fills the row and cuts off the
 * segment that says which project this is. Shortening by meaning rather than by
 * measurement keeps that segment, and this Compose version can only ellipsise a
 * tail anyway.
 *
 * [homes] is every spelling the home answers to -- the same list the app hands
 * its path checks -- because a transcript records whichever spelling the agent
 * was run under, and a row should fold either.
 *
 * A path that is not under any of them is drawn whole. So is one that merely
 * shares a prefix: `<home>omething` is a sibling, not a child, which is why the
 * separator is part of the test.
 */
fun foldHome(path: String, homes: List<String>): String {
    for (home in homes) {
        if (path == home) return HOME_MARK
        if (path.startsWith("$home/")) return HOME_MARK + path.removePrefix(home)
    }
    return path
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
 *
 * A directory whose transcripts name no working directory contributes to the one
 * project with no path, and a transcript that is not a chat contributes nothing.
 */
fun projects(agentHome: File): List<Project> {
    val root = File(agentHome, "projects")
    val directories = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()

    val byPath = LinkedHashMap<String?, MutableList<Chat>>()

    for (directory in directories) {
        val transcripts = directory.listFiles { file -> file.name.endsWith(SUFFIX) }
            ?.filter { it.isFile }
            .orEmpty()
        val summaries = transcripts.mapNotNull(::readTranscript)
        if (summaries.isEmpty()) continue

        // Every turn in a transcript shares one working directory, so any of
        // them names the project. A directory whose transcripts name none is
        // not named by its own flattened name, which cannot be undone.
        val path = summaries.firstNotNullOfOrNull { it.cwd }?.let(::oneSpelling)

        byPath.getOrPut(path) { mutableListOf() } += summaries.map { it.chat }
    }

    return byPath.map { (path, chats) ->
        Project(
            path = path,
            reachable = path != null && File(path).isDirectory,
            chats = chats.sortedByDescending { it.modified },
        )
    }.sortedWith(byRecency)
}

/**
 * Most recently touched first, with the project naming no directory last
 * whatever its chats say: it is a remainder rather than a place.
 */
val byRecency: Comparator<Project> =
    compareBy<Project> { it.path == null }.thenByDescending { it.modified }

/** A transcript's chat, and the working directory it names. */
class Transcript(val chat: Chat, val cwd: String?)

/**
 * What one transcript says about itself, read from its last [WINDOW_BYTES]
 * and, where those leave a question open, its first [HEAD_BYTES].
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
 * Such a window can also hold no working directory and no turn of conversation,
 * and both are questions the first [HEAD_BYTES] answer.
 *
 * Null for a transcript whose two windows hold no conversational record: the CLI
 * writes bookkeeping of its own at startup and at exit, and a file holding
 * nothing else names no conversation anyone had.
 *
 * The time is the file's own, which costs a `stat` rather than a parse.
 */
fun readTranscript(file: File): Transcript? {
    val sessionId = file.name.removeSuffix(SUFFIX)
    var title: String? = null
    var agentName: String? = null
    var aiTitle: String? = null
    var lastPrompt: String? = null
    var firstPrompt: String? = null
    var cwd: String? = null
    var filed: String? = null
    var spoke = false

    // The directory the transcript sits in, which is the one working directory
    // it was filed under. A transcript can name several: a record carries the
    // directory of the moment it was written, and an agent that walks into a
    // subdirectory leaves records naming that. The name settles which of them
    // is the project's.
    val directory = file.parentFile?.name

    for (line in tail(file)) {
        // Cheap enough to skip a parse on: most of a transcript's bytes are
        // records this reads nothing out of.
        if (!line.startsWith("{")) continue
        val record = runCatching { JSONObject(line) }.getOrNull() ?: continue

        val type = record.optString("type")
        when (type) {
            "custom-title" -> record.text("customTitle")?.let { title = it }
            "agent-name" -> record.text("agentName")?.let { agentName = it }
            // The name the model publishes for its own conversation. It is
            // rewritten on most turns and is small, so the window holds one
            // whenever the transcript carries any, and the last is the current.
            "ai-title" -> record.text("aiTitle")?.let { aiTitle = it }
            "last-prompt" -> record.text("lastPrompt")?.let { lastPrompt = clean(it) }
            // A turn dispatched to a subagent is the parent's work rather than a
            // conversation of its own, so it never supplies a label.
            "user" -> if (firstPrompt == null && !record.optBoolean("isSidechain")) {
                firstPrompt = prompt(record.optJSONObject("message"))
            }
        }
        if (type in CONVERSATIONAL) spoke = true
        record.text("cwd")?.let {
            cwd = it
            if (names(it, directory)) filed = it
        }
    }

    // The head answers what the tail left open, and nothing else: every other
    // field is a name or a prompt, for which the end of the transcript is the
    // authority. A file no larger than the tail window was read whole, so a
    // second read would answer with what the first already did.
    if ((filed == null || !spoke) && file.length() > WINDOW_BYTES) {
        for (line in head(file)) {
            if (!line.startsWith("{")) continue
            val record = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (record.optString("type") in CONVERSATIONAL) spoke = true
            record.text("cwd")?.let {
                if (cwd == null) cwd = it
                if (filed == null && names(it, directory)) filed = it
            }
            if (spoke && filed != null) break
        }
    }

    if (!spoke) return null

    return Transcript(
        chat = Chat(
            sessionId = sessionId,
            title = title ?: agentName ?: aiTitle,
            lastPrompt = lastPrompt ?: firstPrompt,
            modified = file.lastModified(),
            transcript = file,
        ),
        // The one that names this transcript's own directory, and otherwise the
        // last recorded. A name that confirms nothing leaves the last, which is
        // the only evidence there is; it cannot be checked, so it is not treated
        // as though it had been.
        cwd = filed ?: cwd,
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
 * The lines of the start of a file.
 *
 * The last line is dropped whenever the window is not the whole file, because a
 * window ending in the middle of the file ends in the middle of a record.
 */
private fun head(file: File): List<String> = runCatching {
    RandomAccessFile(file, "r").use { handle ->
        val length = handle.length()
        val bytes = ByteArray(minOf(length, HEAD_BYTES.toLong()).toInt())
        handle.readFully(bytes)
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        if (length <= HEAD_BYTES) lines else lines.dropLast(1)
    }
}.getOrDefault(emptyList())

/**
 * One spelling for a directory the system offers under two names.
 *
 * `/data/user/0` is the primary user's data, mounted over `/data/data`, so the
 * two name one directory -- a transcript written under either records what its
 * process was given, and keying by the string alone files one project as two.
 * Only user zero: `/data/user/10` is a work profile and is a different place.
 */
fun oneSpelling(path: String): String =
    if (path.startsWith(PRIMARY_USER)) DATA + path.removePrefix(PRIMARY_USER) else path

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

/** The record types that carry a turn of conversation. */
private val CONVERSATIONAL = setOf("user", "assistant")

private const val HOME_MARK = "~"
private const val SUFFIX = ".jsonl"
private const val NAME_SEGMENTS = 2
private const val PRIMARY_USER = "/data/user/0/"
private const val DATA = "/data/data/"
private const val ID_PREFIX = 8

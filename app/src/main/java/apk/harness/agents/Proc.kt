package apk.harness.agents

import java.io.File

/**
 * True when [text] is a session id: a UUID in its canonical spelling.
 *
 * A session id reaches the CLI's command line through a variable the launcher
 * splits on spaces, and arrives from file names, roster records and intents that
 * anything on the device can write. So an id is refused where it enters rather
 * than trusted on the way to a flag.
 */
fun isSessionId(text: String): Boolean = SESSION_ID.matches(text)

private val SESSION_ID =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** The variable a tab's processes carry, naming the tab they belong to. */
const val TAB_VARIABLE = "HARNESS_TAB"

/** What a process's environment says about the tab it belongs to. */
sealed interface TagRead {
    data class Tag(val value: String) : TagRead

    /** The environment was read and names no tab. */
    data object Absent : TagRead

    /** The environment could not be read. */
    data object Unreadable : TagRead
}

/**
 * One tab as the processes it starts know it: the [tag] they carry in
 * [TAB_VARIABLE], and the pid of its terminal's session leader once the terminal
 * has a process. [sessionId] and [label] are what a notification about the tab
 * names and opens.
 */
data class TabIdentity(
    val tag: String,
    val leader: Int?,
    val sessionId: String = "",
    val label: String = "",
    /** The tab's key, which numbers its notification. */
    val key: Long = 0,
)

/**
 * The fields of `/proc/<pid>/stat` the app decides by.
 *
 * [session] is the pid of the terminal's session leader, [tpgid] the process
 * group holding the terminal in the foreground, and [cpu] the process's own
 * time plus that of its reaped children, in clock ticks.
 */
data class ProcStat(
    val state: Char,
    val pgrp: Int,
    val session: Int,
    val tpgid: Int,
    val cpu: Long,
    val procStart: Long,
)

/**
 * The process table under [root], which a test replaces with a directory of its
 * own.
 */
class ProcTable(private val root: File = File("/proc")) {

    /** Every pid with a directory, in no order. */
    fun pids(): List<Int> =
        root.list()?.mapNotNull { it.toIntOrNull() }.orEmpty()

    /** [TAB_VARIABLE] as [pid]'s environment carries it, in one read. */
    fun environTag(pid: Int): TagRead {
        val bytes = runCatching { File(root, "$pid/environ").readBytes() }.getOrNull()
            ?: return TagRead.Unreadable
        return tagIn(bytes)?.let(TagRead::Tag) ?: TagRead.Absent
    }

    /** [pid]'s stat line, parsed, or null when it is gone or unreadable. */
    fun stat(pid: Int): ProcStat? {
        val line = runCatching { File(root, "$pid/stat").readText() }.getOrNull() ?: return null
        return parseStat(line)
    }
}

/**
 * [TAB_VARIABLE]'s value in an environment block: `NAME=value` entries, each
 * ended by a NUL.
 */
fun tagIn(environ: ByteArray): String? {
    val prefix = "$TAB_VARIABLE="
    var start = 0
    for (index in 0..environ.size) {
        if (index < environ.size && environ[index] != 0.toByte()) continue
        if (index > start) {
            val entry = String(environ, start, index - start, Charsets.UTF_8)
            if (entry.startsWith(prefix)) return entry.removePrefix(prefix).takeIf { it.isNotEmpty() }
        }
        start = index + 1
    }
    return null
}

/**
 * A stat line, read from after the last `)`: the `comm` field before it is
 * parenthesised and may itself hold spaces and brackets.
 */
fun parseStat(line: String): ProcStat? {
    val fields = line.substringAfterLast(')', "").trim().split(' ')
    // Counted from field 3, the state.
    fun long(field: Int): Long? = fields.getOrNull(field - 3)?.toLongOrNull()
    val state = fields.getOrNull(0)?.singleOrNull() ?: return null
    val cpu = listOf(14, 15, 16, 17).sumOf { long(it) ?: return null }
    return ProcStat(
        state = state,
        pgrp = long(5)?.toInt() ?: return null,
        session = long(6)?.toInt() ?: return null,
        tpgid = long(8)?.toInt() ?: return null,
        cpu = cpu,
        procStart = long(22) ?: return null,
    )
}

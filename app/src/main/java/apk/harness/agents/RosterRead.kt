package apk.harness.agents

import java.io.File

/**
 * The running agents [directory]'s record files describe, checked against the
 * process table [proc].
 *
 * A process writes `<pid>.json` for itself and does not always remove it, so a
 * killed agent leaves its record behind: a record counts only while its pid is
 * alive and started when the record says it did. A record naming an id that is
 * not a session id counts for nothing.
 *
 * [lastGood] carries the last record each file parsed to, across reads. A read
 * can land while the process is rewriting its file and see it empty or cut
 * short, and dropping the record then would say the agent has gone; with a map
 * given, such a file keeps the record it last had. A file that is gone is gone.
 */
fun readRoster(
    directory: File?,
    parse: (String) -> RecordRead,
    proc: ProcTable,
    lastGood: MutableMap<String, RecordRead.Entry>? = null,
): List<RunningSession> {
    val files = directory?.listFiles { file -> file.name.endsWith(RECORD_SUFFIX) }.orEmpty()
    lastGood?.keys?.retainAll(files.mapTo(HashSet()) { it.name })

    val entries = files.mapNotNull { file ->
        val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
        when (val read = parse(text)) {
            is RecordRead.Entry -> read.also { lastGood?.put(file.name, it) }
            RecordRead.Empty -> {
                lastGood?.remove(file.name)
                null
            }
            RecordRead.Unparsable -> lastGood?.get(file.name)
        }
    }

    return entries.mapNotNull { entry ->
        if (!isSessionId(entry.sessionId)) return@mapNotNull null
        val stat = proc.stat(entry.pid) ?: return@mapNotNull null
        if (entry.claimedStart != null && entry.claimedStart != stat.procStart.toString()) {
            return@mapNotNull null
        }
        RunningSession(
            pid = entry.pid,
            sessionId = entry.sessionId,
            status = entry.status,
            procStart = stat.procStart,
            tag = proc.environTag(entry.pid),
        )
    }
}

private const val RECORD_SUFFIX = ".json"

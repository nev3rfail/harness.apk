package apk.harness.agents

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RosterReadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val id = "da24d711-40d4-44c3-85b4-9512de5a54c5"

    private fun record(pid: Int, status: String, start: String = "900") =
        """{"pid":$pid,"sessionId":"$id","procStart":"$start","status":"$status"}"""

    private fun alive(proc: File, pid: Int, start: String = "900") {
        File(proc, "$pid").mkdirs()
        File(proc, "$pid/stat").writeText("$pid (claude) S 1 $pid $pid 0 $pid 0 0 0 0 0 1 1 0 0 20 0 1 0 $start 0 0\n")
    }

    @Test
    fun `a file caught mid-rewrite keeps the record it last had`() {
        val sessions = folder.newFolder("sessions")
        val proc = folder.newFolder("proc")
        alive(proc, 7)
        val file = File(sessions, "7.json")
        val lastGood = mutableMapOf<String, RecordRead.Entry>()

        file.writeText(record(7, "busy"))
        readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc), lastGood)
        file.writeText("""{"pid":7,"sess""")
        val during = readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc), lastGood)

        assertEquals(listOf("busy"), during.map { it.status })
    }

    @Test
    fun `with no memory a file that does not parse counts for nothing`() {
        val sessions = folder.newFolder("sessions")
        val proc = folder.newFolder("proc")
        alive(proc, 7)
        File(sessions, "7.json").writeText("half a fi")

        assertEquals(emptyList<RunningSession>(), readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc)))
    }

    @Test
    fun `a file that is gone takes its last record with it`() {
        val sessions = folder.newFolder("sessions")
        val proc = folder.newFolder("proc")
        alive(proc, 7)
        val file = File(sessions, "7.json").apply { writeText(record(7, "busy")) }
        val lastGood = mutableMapOf<String, RecordRead.Entry>()
        readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc), lastGood)

        file.delete()
        readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc), lastGood)
        file.writeText("half")

        assertEquals(
            emptyList<RunningSession>(),
            readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc), lastGood),
        )
    }

    @Test
    fun `a record whose process started at another time is a stale record`() {
        val sessions = folder.newFolder("sessions")
        val proc = folder.newFolder("proc")
        alive(proc, 7, start = "901")
        File(sessions, "7.json").writeText(record(7, "busy", start = "900"))

        assertEquals(emptyList<RunningSession>(), readRoster(sessions, ClaudeBackend::parseRecord, ProcTable(proc)))
    }

    @Test
    fun `no directory is no roster`() {
        assertEquals(
            emptyList<RunningSession>(),
            readRoster(null, ClaudeBackend::parseRecord, ProcTable(folder.newFolder("proc"))),
        )
    }
}

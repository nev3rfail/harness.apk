package apk.harness.agents

import apk.harness.chats.flatten
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClaudeBackendTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val device =
        """{"pid":22740,"sessionId":"da24d711-40d4-44c3-85b4-9512de5a54c5","cwd":"/data/user/0/dev.harness/files","startedAt":1787928871087,"procStart":"6577951","version":"2.1.246","peerProtocol":1,"peerFeatures":["notify_idle","artifact_yield"],"kind":"interactive","entrypoint":"cli","pidDomain":"linux::","name":"files-4b","nameSource":"derived","status":"idle","updatedAt":1787928891016,"statusUpdatedAt":1787928891016}"""

    private val desktop =
        """{"pid":63752,"sessionId":"5895396e-af88-40d5-b1aa-0921c54d106f","cwd":"F:\\fun\\harness","startedAt":1787857433511,"procStart":"134323310317641419","version":"2.1.226","peerProtocol":1,"kind":"interactive","entrypoint":"cli","messagingSocketPath":"\\\\.\\pipe\\cc-msg-dac782e7b5cf1de049fd023f9177165f","name":"harness-63","nameSource":"derived","status":"busy","updatedAt":1787928917194,"statusUpdatedAt":1787928917194}"""

    /** A `<pid>/stat` line whose field 22 is [start], with [comm] as the second field. */
    private fun stat(directory: File, pid: Int, start: String, comm: String = "node") {
        val fields = MutableList(50) { "0" }
        fields[21] = start
        val tail = fields.drop(2).joinToString(" ")
        File(directory, "$pid").apply { mkdirs() }
            .let { File(it, "stat").writeText("$pid ($comm) $tail\n") }
    }

    /** A roster file called [name] under [home], holding [text]. */
    private fun record(home: File, name: String, text: String) {
        val sessions = File(home, ".claude/sessions").apply { mkdirs() }
        File(sessions, name).writeText(text)
    }

    @Test
    fun `a device path flattens the way the CLI files it`() {
        assertEquals(
            "-data-user-0-dev-harness-files",
            flatten("/data/user/0/dev.harness/files"),
        )
    }

    @Test
    fun `a path with no dots only loses its separators`() {
        assertEquals("-home-nev-work", flatten("/home/nev/work"))
    }

    @Test
    fun `a desktop path loses its drive colon and its backslashes`() {
        assertEquals("D--Users-nev3rfail", flatten("D:\\Users\\nev3rfail"))
    }

    @Test
    fun `a trailing separator becomes a trailing dash`() {
        assertEquals("-home-nev-", flatten("/home/nev/"))
    }

    @Test
    fun `the device record names its session and its status`() {
        val entry = rosterEntry(JSONObject(device))!!

        assertEquals(22740, entry.pid)
        assertEquals("da24d711-40d4-44c3-85b4-9512de5a54c5", entry.sessionId)
        assertEquals("idle", entry.status)
    }

    @Test
    fun `a record carrying a messaging socket reads like any other`() {
        val entry = rosterEntry(JSONObject(desktop))!!

        assertEquals(63752, entry.pid)
        assertEquals("5895396e-af88-40d5-b1aa-0921c54d106f", entry.sessionId)
        assertEquals("busy", entry.status)
    }

    @Test
    fun `a record with no status reads without one`() {
        assertNull(rosterEntry(JSONObject("""{"pid":7,"sessionId":"s","cwd":"/w"}"""))!!.status)
    }

    @Test
    fun `a record naming no session is no entry`() {
        assertNull(rosterEntry(JSONObject("""{"pid":22740,"cwd":"/w","status":"idle"}""")))
    }

    @Test
    fun `a record naming no pid is no entry`() {
        assertNull(rosterEntry(JSONObject("""{"sessionId":"s","cwd":"/w"}""")))
    }

    /** The one session id [ClaudeBackend.roster] names under [home] against [proc]. */
    private fun running(home: File, proc: File) = ClaudeBackend.roster(home, proc).map { it.sessionId }

    @Test
    fun `a matching start time is a running process`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 22740, "6577951")
        record(home, "22740.json", device)

        assertEquals(listOf(DEVICE_SESSION), running(home, proc))
    }

    @Test
    fun `a reused pid started at another time is not the same process`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 22740, "9999999")
        record(home, "22740.json", device)

        assertEquals(emptyList<String>(), running(home, proc))
    }

    @Test
    fun `a pid with no directory is gone`() {
        val home = folder.newFolder("home")
        record(home, "22740.json", device)

        assertEquals(emptyList<String>(), running(home, folder.newFolder("proc")))
    }

    @Test
    fun `a comm field holding a space and a bracket keeps the fields aligned`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 22740, "6577951", comm = "the (odd) name")
        record(home, "22740.json", device)

        assertEquals(listOf(DEVICE_SESSION), running(home, proc))
    }

    @Test
    fun `a record with no start time is running while its process is there`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 41, "6577951")
        record(home, "41.json", """{"pid":41,"sessionId":"$DEVICE_SESSION"}""")
        record(home, "42.json", """{"pid":42,"sessionId":"$OLDER"}""")

        assertEquals(listOf(DEVICE_SESSION), running(home, proc))
    }

    @Test
    fun `a pid directory with no stat is not a running process`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        File(proc, "22740").mkdirs()
        record(home, "22740.json", device)

        assertEquals(emptyList<String>(), running(home, proc))
    }
    @Test
    fun `the roster keeps the records whose processes are alive`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 22740, "6577951")
        record(home, "22740.json", device)
        record(home, "63752.json", desktop)
        record(home, "22740.txt", device)
        record(home, "broken.json", "half a fi")

        assertEquals(
            listOf("da24d711-40d4-44c3-85b4-9512de5a54c5"),
            ClaudeBackend.roster(home, proc).map { it.sessionId },
        )
    }

    @Test
    fun `a running record carries the kernel's start time and the tab its process names`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 22740, "6577951")
        File(proc, "22740/environ").writeBytes("PATH=/bin\u0000HARNESS_TAB=tab-1\u0000".toByteArray())
        record(home, "22740.json", device)

        val entry = ClaudeBackend.roster(home, proc).single()
        assertEquals(6577951L, entry.procStart)
        assertEquals("tab-1", entry.tag)
    }

    @Test
    fun `a record naming an id that is not a session id is not running`() {
        val home = folder.newFolder("home")
        val proc = folder.newFolder("proc")
        stat(proc, 22740, "6577951")
        record(home, "22740.json", device.replace(DEVICE_SESSION, "x --settings evil"))

        assertEquals(emptyList<RunningSession>(), ClaudeBackend.roster(home, proc))
    }

    @Test
    fun `the roster directory is the CLI's sessions directory`() {
        val home = File("/h")
        assertEquals(File(home, ".claude/sessions"), ClaudeBackend.rosterDirectory(home))
    }

    @Test
    fun `a home with no roster holds no running agents`() {
        assertEquals(
            emptyList<RunningSession>(),
            ClaudeBackend.roster(folder.newFolder("home"), folder.newFolder("proc")),
        )
    }

    /** The project directory [directory]'s transcripts are filed in under [home]. */
    private fun projectDirectory(home: File, directory: File) =
        File(home, ".claude/projects/${flatten(directory.absolutePath)}").apply { mkdirs() }

    @Test
    fun `the newest transcript names the conversation a fresh tab lands in`() {
        val home = folder.newFolder("home")
        val work = File(folder.root, "work")
        val project = projectDirectory(home, work)
        val older = File(project, "$OLDER.jsonl").apply { writeText("{}\n") }
        val newer = File(project, "$NEWER.jsonl").apply { writeText("{}\n") }
        // Write order alone would leave the newer file newest, so the test says
        // nothing about the rule unless the host let both times be set.
        assertTrue(older.setLastModified(1_000_000))
        assertTrue(newer.setLastModified(2_000_000))

        assertEquals(NEWER, ClaudeBackend.mostRecent(home, work))
    }

    @Test
    fun `a transcript whose name is not a session id is never the most recent`() {
        val home = folder.newFolder("home")
        val work = File(folder.root, "work")
        val project = projectDirectory(home, work)
        File(project, "$OLDER.jsonl").apply { writeText("{}\n") }.setLastModified(1_000_000)
        File(project, "notes.jsonl").apply { writeText("{}\n") }.setLastModified(2_000_000)

        assertEquals(OLDER, ClaudeBackend.mostRecent(home, work))
    }

    @Test
    fun `a project directory holding no transcript names nothing`() {
        val home = folder.newFolder("home")
        val work = File(folder.root, "work")
        projectDirectory(home, work)

        assertNull(ClaudeBackend.mostRecent(home, work))
    }

    @Test
    fun `a home with no history for the directory names nothing`() {
        assertNull(ClaudeBackend.mostRecent(folder.newFolder("home"), File(folder.root, "work")))
    }

    @Test
    fun `a transcript filed under any project counts as the conversation's`() {
        val home = folder.newFolder("home")
        File(projectDirectory(home, File(folder.root, "elsewhere")), "$OLDER.jsonl").writeText("{}\n")

        assertTrue(ClaudeBackend.hasTranscript(home, OLDER))
        assertFalse(ClaudeBackend.hasTranscript(home, NEWER))
        assertFalse(ClaudeBackend.hasTranscript(home, "../$OLDER"))
    }

    @Test
    fun `a chat read by this backend is tagged with it`() {
        val home = folder.newFolder("home")
        val work = File(folder.root, "work")
        File(projectDirectory(home, work), "$OLDER.jsonl").writeText("""{"type":"user","cwd":"/work"}""")

        assertEquals(
            listOf("claude"),
            ClaudeBackend.projects(home).flatMap { project ->
                project.chats.map { it.backendId }
            },
        )
    }

    @Test
    fun `resuming a conversation asks for it by id`() {
        assertEquals(listOf("--resume", "s"), ClaudeBackend.resume("s"))
    }

    @Test
    fun `starting a conversation names it up front`() {
        assertEquals(listOf("--session-id", "s"), ClaudeBackend.start("s"))
    }

    @Test
    fun `an MCP config is passed by absolute path`() {
        // Relative, so the flag carries a path the CLI resolves from any
        // working directory rather than the one the app was started in.
        val config = File("panels.json")
        assertEquals(
            listOf("--mcp-config", config.absolutePath),
            ClaudeBackend.mcpConfig(config),
        )
    }

    @Test
    fun `switching an agent already running is a typed command`() {
        assertEquals("/resume s", ClaudeBackend.switch("s"))
    }

    // The CLI's prompt as the grid holds it: the glyph, a non-breaking space,
    // and the row padded out to the terminal's width. The rules above and below
    // it are what the CLI draws around the prompt.
    private fun viewport(vararg lines: String) = lines.joinToString("\n")

    private fun prompt(typed: String) = "\u276F\u00A0$typed".padEnd(40)

    private val rule = "─".repeat(40)

    @Test
    fun `an empty prompt holds nothing`() {
        assertEquals("", promptLine(viewport(rule, prompt(""), rule)))
    }

    @Test
    fun `a prompt with text holds what was typed`() {
        assertEquals("hello", promptLine(viewport(rule, prompt("hello"), rule)))
    }

    @Test
    fun `the prompt is read past the messages echoed above it`() {
        // Every sent message is drawn behind the same glyph, so the prompt is
        // the last such line rather than the first.
        val screen = viewport(
            "\u276F what I asked a while ago".padEnd(40),
            "an answer".padEnd(40),
            rule,
            prompt(""),
            rule,
        )
        assertEquals("", promptLine(screen))
    }

    @Test
    fun `a viewport scrolled off the prompt reads as text rather than as empty`() {
        // What is on screen is an old message, and answering "" for it would
        // put a typed command on the end of whatever is really in the prompt.
        val screen = viewport("\u276F what I asked a while ago".padEnd(40), "an answer".padEnd(40))
        assertEquals("what I asked a while ago", promptLine(screen))
    }

    @Test
    fun `a viewport with no prompt on it answers nothing`() {
        assertNull(promptLine(viewport("$ ls".padEnd(40), "agent.sh".padEnd(40))))
    }

    @Test
    fun `an empty viewport answers nothing`() {
        assertNull(promptLine(""))
    }

    private companion object {
        const val DEVICE_SESSION = "da24d711-40d4-44c3-85b4-9512de5a54c5"
        const val OLDER = "11111111-1111-4111-8111-111111111111"
        const val NEWER = "22222222-2222-4222-8222-222222222222"
    }
}

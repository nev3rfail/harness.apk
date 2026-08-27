package apk.harness.chats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatTreeTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A transcript in a project directory, from lines given in order. */
    private fun transcript(project: String, session: String, vararg lines: String): File {
        val directory = File(folder.root, "projects/$project").apply { mkdirs() }
        return File(directory, "$session.jsonl").apply {
            writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    private fun user(cwd: String) =
        """{"type":"user","cwd":${quote(cwd)},"message":{"content":"hi"}}"""

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\") + "\""

    @Test
    fun `a custom title names the chat`() {
        val file = transcript(
            "p", "s",
            user("/work"),
            """{"type":"custom-title","customTitle":"the drawer"}""",
        )

        val read = readTranscript(file)

        assertEquals("the drawer", read.chat.title)
        assertEquals("the drawer", read.chat.label)
        assertEquals("/work", read.cwd)
    }

    @Test
    fun `the last title wins`() {
        val file = transcript(
            "p", "s",
            """{"type":"custom-title","customTitle":"first"}""",
            """{"type":"custom-title","customTitle":"second"}""",
        )

        assertEquals("second", readTranscript(file).chat.title)
    }

    @Test
    fun `an agent name serves when nobody set a title`() {
        val file = transcript("p", "s", """{"type":"agent-name","agentName":"zspike"}""")

        assertEquals("zspike", readTranscript(file).chat.title)
    }

    @Test
    fun `a title outranks an agent name`() {
        val file = transcript(
            "p", "s",
            """{"type":"agent-name","agentName":"zspike"}""",
            """{"type":"custom-title","customTitle":"named"}""",
        )

        assertEquals("named", readTranscript(file).chat.title)
    }

    @Test
    fun `an unnamed chat is labelled by its last prompt`() {
        val file = transcript(
            "p", "s",
            """{"type":"last-prompt","lastPrompt":"ship the drawer"}""",
        )

        val chat = readTranscript(file).chat
        assertNull(chat.title)
        assertEquals("ship the drawer", chat.lastPrompt)
        assertEquals("ship the drawer", chat.label)
    }

    @Test
    fun `a prompt is stripped of the machinery it arrives wrapped in`() {
        val file = transcript(
            "p", "s",
            """{"type":"last-prompt","lastPrompt":"<system-reminder>x</system-reminder>\nreal one"}""",
        )

        assertEquals("real one", readTranscript(file).chat.lastPrompt)
    }

    @Test
    fun `a chat with no prompt record falls back to what was typed`() {
        val file = transcript("p", "s", user("/work"))

        assertEquals("hi", readTranscript(file).chat.lastPrompt)
    }

    @Test
    fun `a prompt arriving as content blocks is read`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","message":{"content":[""" +
                """{"type":"tool_result","content":"x"},""" +
                """{"type":"text","text":"typed this"}]}}""",
        )

        assertEquals("typed this", readTranscript(file).chat.lastPrompt)
    }

    @Test
    fun `a turn carrying only a tool result is not a prompt`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"x"}]}}""",
        )

        assertNull(readTranscript(file).chat.lastPrompt)
    }

    @Test
    fun `a subagent's turn never labels the chat`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","isSidechain":true,"message":{"content":"subagent work"}}""",
            """{"type":"user","message":{"content":"what a person typed"}}""",
        )

        assertEquals("what a person typed", readTranscript(file).chat.lastPrompt)
    }

    @Test
    fun `a recorded last prompt outranks what the window happens to start with`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","message":{"content":"an old turn"}}""",
            """{"type":"last-prompt","lastPrompt":"the recent one"}""",
        )

        assertEquals("the recent one", readTranscript(file).chat.lastPrompt)
    }

    @Test
    fun `a chat that says nothing is labelled by its id`() {
        val file = transcript("p", "0123456789abcdef", """{"type":"assistant"}""")

        val chat = readTranscript(file).chat
        assertNull(chat.title)
        assertNull(chat.lastPrompt)
        assertEquals("01234567", chat.label)
    }

    @Test
    fun `a line that does not parse is skipped rather than fatal`() {
        val file = transcript(
            "p", "s",
            """{"type":"custom-title","customTitle":"kept"}""",
            """{"type":"user","cwd":"/work",""",
        )

        assertEquals("kept", readTranscript(file).chat.title)
    }

    @Test
    fun `only the end of a long transcript is read`() {
        val filler = """{"type":"assistant","text":"${"x".repeat(4096)}"}"""
        val lines = buildList {
            add("""{"type":"custom-title","customTitle":"too early to see"}""")
            repeat(WINDOW_BYTES / 4096 + 8) { add(filler) }
            add("""{"type":"custom-title","customTitle":"still here"}""")
        }
        val file = transcript("p", "s", *lines.toTypedArray())

        assertTrue(file.length() > WINDOW_BYTES)
        assertEquals("still here", readTranscript(file).chat.title)
    }

    @Test
    fun `a window starting mid-record drops the partial line`() {
        // The window lands inside the filler, so its first line is a fragment
        // that would parse as nothing. What matters is that it is not counted.
        val filler = """{"type":"custom-title","customTitle":"${"y".repeat(4096)}"}"""
        val lines = buildList {
            repeat(WINDOW_BYTES / 4096 + 4) { add(filler) }
            add("""{"type":"custom-title","customTitle":"last"}""")
        }
        val file = transcript("p", "s", *lines.toTypedArray())

        assertEquals("last", readTranscript(file).chat.title)
    }

    @Test
    fun `a project is named by what its transcripts say, not by its directory`() {
        transcript("this-name-is-a-guess", "s", user("/real/path"))

        val projects = projects(folder.root)

        assertEquals(1, projects.size)
        assertEquals("/real/path", projects[0].path)
        assertFalse(projects[0].guessed)
    }

    @Test
    fun `a project whose transcripts name nothing falls back to its directory`() {
        transcript("-data-work", "s", """{"type":"assistant"}""")

        val projects = projects(folder.root)

        assertEquals("/data/work", projects[0].path)
        assertTrue(projects[0].guessed)
    }

    @Test
    fun `two directories naming one path become one project`() {
        transcript("a", "one", user("/same"))
        transcript("b", "two", user("/same"))

        val projects = projects(folder.root)

        assertEquals(1, projects.size)
        assertEquals(setOf("one", "two"), projects[0].chats.map { it.sessionId }.toSet())
    }

    @Test
    fun `chats and projects are ordered by when they last moved`() {
        val old = transcript("a", "old", user("/a"))
        val recent = transcript("b", "recent", user("/b"))
        val newest = transcript("b", "newest", user("/b"))
        old.setLastModified(1_000_000L)
        recent.setLastModified(2_000_000L)
        newest.setLastModified(3_000_000L)

        val projects = projects(folder.root)

        assertEquals(listOf("/b", "/a"), projects.map { it.path })
        assertEquals(listOf("newest", "recent"), projects[0].chats.map { it.sessionId })
    }

    @Test
    fun `a project directory that does not exist is unreachable`() {
        val here = folder.newFolder("here")
        transcript("a", "s", user(here.absolutePath))
        transcript("b", "s", user("/no/such/place"))

        val projects = projects(folder.root).associateBy { it.path }

        assertTrue(projects.getValue(here.absolutePath).reachable)
        assertFalse(projects.getValue("/no/such/place").reachable)
    }

    @Test
    fun `a directory holding no transcripts is not a project`() {
        File(folder.root, "projects/empty").mkdirs()
        transcript("real", "s", user("/work"))

        assertEquals(listOf("/work"), projects(folder.root).map { it.path })
    }

    @Test
    fun `a project is named by its last two path segments`() {
        transcript("a", "s", user("/data/user/0/dev.harness/files"))

        assertEquals("dev.harness/files", projects(folder.root)[0].name)
    }

    @Test
    fun `a project at the root is named by its path`() {
        transcript("a", "s", user("/"))

        assertEquals("/", projects(folder.root)[0].name)
    }

    @Test
    fun `a home with no history is an empty list`() {
        assertEquals(emptyList<Project>(), projects(folder.root))
    }
}

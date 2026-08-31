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

    /**
     * A record that makes a transcript a conversation and carries nothing else.
     * A transcript holding no turn of conversation is not a chat, so a test
     * about a field some other record holds says a turn was taken.
     */
    private val said = """{"type":"assistant"}"""

    /** What a transcript holding a conversation says about itself. */
    private fun read(file: File) = readTranscript(file)!!

    @Test
    fun `a custom title names the chat`() {
        val file = transcript(
            "p", "s",
            user("/work"),
            """{"type":"custom-title","customTitle":"the drawer"}""",
        )

        val read = read(file)

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
            said,
        )

        assertEquals("second", read(file).chat.title)
    }

    @Test
    fun `an agent name serves when nobody set a title`() {
        val file = transcript(
            "p", "s",
            """{"type":"agent-name","agentName":"zspike"}""",
            said,
        )

        assertEquals("zspike", read(file).chat.title)
    }

    @Test
    fun `a title outranks an agent name`() {
        val file = transcript(
            "p", "s",
            """{"type":"agent-name","agentName":"zspike"}""",
            """{"type":"custom-title","customTitle":"named"}""",
            said,
        )

        assertEquals("named", read(file).chat.title)
    }

    @Test
    fun `an unnamed chat is labelled by its last prompt`() {
        val file = transcript(
            "p", "s",
            """{"type":"last-prompt","lastPrompt":"ship the drawer"}""",
            said,
        )

        val chat = read(file).chat
        assertNull(chat.title)
        assertEquals("ship the drawer", chat.lastPrompt)
        assertEquals("ship the drawer", chat.label)
    }

    @Test
    fun `a prompt is stripped of the machinery it arrives wrapped in`() {
        val file = transcript(
            "p", "s",
            """{"type":"last-prompt","lastPrompt":"<system-reminder>x</system-reminder>\nreal one"}""",
            said,
        )

        assertEquals("real one", read(file).chat.lastPrompt)
    }

    @Test
    fun `a chat with no prompt record falls back to what was typed`() {
        val file = transcript("p", "s", user("/work"))

        assertEquals("hi", read(file).chat.lastPrompt)
    }

    @Test
    fun `a prompt arriving as content blocks is read`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","message":{"content":[""" +
                """{"type":"tool_result","content":"x"},""" +
                """{"type":"text","text":"typed this"}]}}""",
        )

        assertEquals("typed this", read(file).chat.lastPrompt)
    }

    @Test
    fun `a turn carrying only a tool result is not a prompt`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"x"}]}}""",
        )

        assertNull(read(file).chat.lastPrompt)
    }

    @Test
    fun `a subagent's turn never labels the chat`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","isSidechain":true,"message":{"content":"subagent work"}}""",
            """{"type":"user","message":{"content":"what a person typed"}}""",
        )

        assertEquals("what a person typed", read(file).chat.lastPrompt)
    }

    @Test
    fun `a recorded last prompt outranks what the window happens to start with`() {
        val file = transcript(
            "p", "s",
            """{"type":"user","message":{"content":"an old turn"}}""",
            """{"type":"last-prompt","lastPrompt":"the recent one"}""",
        )

        assertEquals("the recent one", read(file).chat.lastPrompt)
    }

    @Test
    fun `a chat that says nothing is labelled by its id`() {
        val file = transcript("p", "0123456789abcdef", """{"type":"assistant"}""")

        val chat = read(file).chat
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
            said,
        )

        assertEquals("kept", read(file).chat.title)
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
        assertEquals("still here", read(file).chat.title)
    }

    @Test
    fun `a window starting mid-record drops the partial line`() {
        // The window lands inside the filler, so its first line is a fragment
        // that would parse as nothing. What matters is that it is not counted.
        val filler = """{"type":"custom-title","customTitle":"${"y".repeat(4096)}"}"""
        val lines = buildList {
            repeat(WINDOW_BYTES / 4096 + 4) { add(filler) }
            add("""{"type":"custom-title","customTitle":"last"}""")
            add(said)
        }
        val file = transcript("p", "s", *lines.toTypedArray())

        assertEquals("last", read(file).chat.title)
    }

    @Test
    fun `a project is named by what its transcripts say, not by its directory`() {
        transcript("this-name-is-a-guess", "s", user("/real/path"))

        val projects = projects(folder.root)

        assertEquals(1, projects.size)
        assertEquals("/real/path", projects[0].path)
    }

    @Test
    fun `a transcript whose tail holds no working directory is read from its head`() {
        val padding = "x".repeat(WINDOW_BYTES)
        transcript(
            "-anything",
            "s",
            user("/read/from/the/head"),
            """{"type":"assistant","message":{"content":"$padding"}}""",
        )

        assertEquals("/read/from/the/head", projects(folder.root).single().path)
    }

    @Test
    fun `the directory a transcript is filed under settles which cwd is the project's`() {
        val file = transcript(
            "-work-thing", "s",
            user("/work/thing"),
            user("/work/thing/.claude/projects"),
            user("/work/thing/sub"),
        )

        assertEquals("/work/thing", read(file).cwd)
    }

    @Test
    fun `a cwd naming the directory counts wherever it is recorded`() {
        val file = transcript(
            "-work-thing", "s",
            user("/work/thing/sub"),
            user("/work/thing"),
            user("/work/thing/other"),
        )

        assertEquals("/work/thing", read(file).cwd)
    }

    @Test
    fun `a trailing separator still names the directory`() {
        val file = transcript("-work-thing", "s", user("/work/thing/"))

        assertEquals("/work/thing/", read(file).cwd)
    }

    @Test
    fun `a name that confirms nothing leaves the last recorded cwd`() {
        val file = transcript("p", "s", user("/one"), user("/two"))

        assertEquals("/two", read(file).cwd)
    }

    @Test
    fun `chats naming a subdirectory do not become a project of their own`() {
        transcript("-work-thing", "a", user("/work/thing"), user("/work/thing/sub"))
        transcript("-work-thing", "b", user("/work/thing/sub"), user("/work/thing"))

        val projects = projects(folder.root)

        assertEquals(1, projects.size)
        assertEquals("/work/thing", projects[0].path)
        assertEquals(2, projects[0].chats.size)
    }

    @Test
    fun `a conversation naming no working directory is unattributed`() {
        transcript("-data-work", "s", said)

        val projects = projects(folder.root)

        assertEquals(1, projects.size)
        assertNull(projects[0].path)
        assertFalse(projects[0].reachable)
    }

    @Test
    fun `a project with no directory is named by the word its row draws`() {
        transcript("-data-work", "s", said)

        assertEquals(UNATTRIBUTED, projects(folder.root).single().name)
    }

    @Test
    fun `a transcript with nothing said in it is not a chat`() {
        transcript(
            "-data-work",
            "s",
            """{"type":"mode","mode":"normal"}""",
            """{"type":"permission-mode","permissionMode":"default"}""",
            """{"type":"cost-state","totalCostUSD":0}""",
        )

        assertEquals(emptyList<Project>(), projects(folder.root))
    }

    @Test
    fun `a subagent turn is something said`() {
        transcript(
            "-data-work",
            "s",
            """{"type":"user","isSidechain":true,"cwd":"/work","message":{"content":"go"}}""",
        )

        assertEquals("/work", projects(folder.root).single().path)
    }

    @Test
    fun `every chat naming no directory lands in one group`() {
        transcript("-one", "a", said)
        transcript("-two", "b", said)

        val projects = projects(folder.root)

        assertEquals(1, projects.size)
        assertNull(projects[0].path)
        assertEquals(2, projects[0].chats.size)
    }

    @Test
    fun `the group with no directory sorts behind a project with older chats`() {
        transcript("-old", "a", user("/work"))
        transcript("-none", "b", said)
        File(folder.root, "projects/-old/a.jsonl").setLastModified(2_000L)
        File(folder.root, "projects/-none/b.jsonl").setLastModified(1_000L)

        assertEquals(listOf("/work", null), projects(folder.root).map { it.path })
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
    fun `both spellings of the primary user's data are one project`() {
        transcript("a", "one", user("/data/user/0/dev.harness/files"))
        transcript("b", "two", user("/data/data/dev.harness/files"))

        val found = projects(folder.root)
        assertEquals(1, found.size)
        assertEquals("/data/data/dev.harness/files", found[0].path)
        assertEquals(2, found[0].chats.size)
    }

    @Test
    fun `another user's data is another place`() {
        transcript("a", "one", user("/data/user/10/dev.harness/files"))
        transcript("b", "two", user("/data/data/dev.harness/files"))

        assertEquals(2, projects(folder.root).size)
    }

    @Test
    fun `a home with no history is an empty list`() {
        assertEquals(emptyList<Project>(), projects(folder.root))
    }

    @Test
    fun `the home itself folds to a tilde`() {
        assertEquals("~", foldHome(USER_HOME, HOMES))
    }

    @Test
    fun `a directory under the home keeps everything below it`() {
        assertEquals("~/projects/foo", foldHome("$USER_HOME/projects/foo", HOMES))
    }

    @Test
    fun `either spelling of the home folds, because a transcript may record either`() {
        assertEquals("~", foldHome(DATA_HOME, HOMES))
        assertEquals("~/projects/foo", foldHome("$DATA_HOME/projects/foo", HOMES))
    }

    @Test
    fun `a path outside the home is drawn whole`() {
        assertEquals("/sdcard/Download", foldHome("/sdcard/Download", HOMES))
    }

    @Test
    fun `a sibling that merely shares the prefix is not folded`() {
        // The separator is part of the test, or `filesomething` reads as a
        // child of `files`.
        assertEquals("${USER_HOME}omething", foldHome("${USER_HOME}omething", HOMES))
    }

    @Test
    fun `the model's own title names a chat nobody else named`() {
        val file = transcript(
            "p", "s",
            """{"type":"ai-title","aiTitle":"Audit and package the repository"}""",
            said,
        )

        val chat = read(file).chat
        assertEquals("Audit and package the repository", chat.title)
        assertEquals("Audit and package the repository", chat.label)
    }

    @Test
    fun `a custom title outranks the model's own`() {
        val file = transcript(
            "p", "s",
            """{"type":"ai-title","aiTitle":"what the model called it"}""",
            """{"type":"custom-title","customTitle":"what a person called it"}""",
            said,
        )

        assertEquals("what a person called it", read(file).chat.title)
    }

    @Test
    fun `an agent name outranks the model's own title`() {
        val file = transcript(
            "p", "s",
            """{"type":"ai-title","aiTitle":"what the model called it"}""",
            """{"type":"agent-name","agentName":"zspike"}""",
            said,
        )

        assertEquals("zspike", read(file).chat.title)
    }

    @Test
    fun `the last of several model titles wins`() {
        val file = transcript(
            "p", "s",
            """{"type":"ai-title","aiTitle":"an early guess"}""",
            """{"type":"ai-title","aiTitle":"the settled one"}""",
            said,
        )

        assertEquals("the settled one", read(file).chat.title)
    }

    @Test
    fun `the model's own title outranks the last prompt`() {
        val file = transcript(
            "p", "s",
            """{"type":"last-prompt","lastPrompt":"ship the file tree root"}""",
            """{"type":"ai-title","aiTitle":"The drawers"}""",
            said,
        )

        val chat = read(file).chat
        assertEquals("The drawers", chat.title)
        assertEquals("The drawers", chat.label)
        assertEquals("ship the file tree root", chat.lastPrompt)
    }

    @Test
    fun `a chat with no title of any kind is still labelled by its prompt`() {
        val file = transcript(
            "p", "s",
            """{"type":"last-prompt","lastPrompt":"run the suite"}""",
            said,
        )

        val chat = read(file).chat
        assertNull(chat.title)
        assertEquals("run the suite", chat.label)
    }

    /** A chat built directly, because [Chat.lastLine] is about its fields alone. */
    private fun chat(title: String?, lastPrompt: String?) = Chat(
        sessionId = "0123456789abcdef",
        title = title,
        lastPrompt = lastPrompt,
        modified = 0L,
        transcript = File("/transcripts/s.jsonl"),
    )

    @Test
    fun `an unnamed chat has no second line, because its name is already that`() {
        assertNull(chat(title = null, lastPrompt = "ship the file tree root").lastLine)
    }

    @Test
    fun `a chat that has said nothing and been named nothing has no second line`() {
        assertNull(chat(title = null, lastPrompt = null).lastLine)
    }

    @Test
    fun `a named chat's second line is its last prompt`() {
        assertEquals(
            "ship the file tree root",
            chat(title = "the drawers", lastPrompt = "ship the file tree root").lastLine,
        )
    }

    @Test
    fun `a named chat with no prompt has no second line`() {
        // The shape a tab with no transcript arrives in: named after the tab,
        // with no prompt behind it.
        assertNull(chat(title = "harness", lastPrompt = null).lastLine)
    }

    private companion object {
        const val USER_HOME = "/data/user/0/dev.harness/files"
        const val DATA_HOME = "/data/data/dev.harness/files"
        val HOMES = listOf(USER_HOME, DATA_HOME)
    }
}

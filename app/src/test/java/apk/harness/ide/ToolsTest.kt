package apk.harness.ide

import apk.harness.intents.Action
import apk.harness.intents.Handoff
import apk.harness.intents.HandoffOutcome
import apk.harness.ui.Rendered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    private val surfaces = Surfaces()

    private val documents = mutableMapOf(
        CLEAN_PATH to CLEAN,
        BROKEN_PATH to BROKEN,
    )

    /** Every handoff that reached the fire, so a refused one can be shown not to. */
    private val fired = mutableListOf<Handoff>()

    /** What the device would answer with; null is nothing installed that handles it. */
    private var receiver: String? = "Firefox"

    private val tools = Tools(
        surfaces = surfaces,
        describeHandoff = { receiver },
        fireHandoff = { handoff ->
            fired += handoff
            receiver?.let { HandoffOutcome.Started(it) } ?: HandoffOutcome.NoHandler
        },
        writable = listOf(WRITABLE),
        readFile = { path -> documents[path] ?: error("no such file: $path") },
        readDocument = { path ->
            Rendered(documents[path] ?: error("no such file: $path"), 0)
        },
    )

    private fun call(
        name: String,
        arguments: JSONObject = JSONObject(),
        owner: Long = NO_CHAT,
    ): JSONObject = runBlocking { tools.call(name, arguments, owner) }

    private fun JSONObject.text(): String =
        getJSONArray("content").getJSONObject(0).getString("text")

    private fun path(path: String) = JSONObject().put("filePath", path)

    @Test
    fun `showing a document puts its path on screen`() {
        val result = call("show_document_panel", path(CLEAN_PATH))

        val shown = surfaces.visible.value as Surface.Document
        assertEquals(CLEAN_PATH, shown.path)
        assertEquals(CLEAN, shown.markdown)
        assertEquals("Showing $CLEAN_PATH", result.text())
    }

    @Test
    fun `showing a document names what did not check`() {
        val result = call("show_document_panel", path(BROKEN_PATH))

        // The fence opens on the document's third line, and a checker problem
        // lands on the fence rather than inside it.
        assertEquals("Showing $BROKEN_PATH\nline 3: unknown id: plaecs", result.text())
        assertTrue(surfaces.visible.value is Surface.Document)
    }

    @Test
    fun `checking a document touches no surface`() {
        val result = call("check_document_cells", path(BROKEN_PATH))

        assertNull(surfaces.visible.value)
        assertEquals("$BROKEN_PATH\nline 3: unknown id: plaecs", result.text())
    }

    @Test
    fun `checking a clean document says so`() {
        assertEquals(
            "Every cell in $CLEAN_PATH checks",
            call("check_document_cells", path(CLEAN_PATH)).text(),
        )
    }

    @Test
    fun `a path that cannot be read is an error rather than a blank panel`() {
        val result = call("show_document_panel", path("/nowhere.md"))

        assertTrue(result.optBoolean("isError"))
        assertTrue(result.text().startsWith("cannot read /nowhere.md"))
        assertNull(surfaces.visible.value)
    }

    @Test
    fun `a document shown by a chat belongs to that chat`() {
        call("show_document_panel", path(CLEAN_PATH), owner = 3L)

        assertEquals(3L, surfaces.owner.value)
    }

    @Test
    fun `a document shown by no chat belongs to no chat`() {
        call("show_document_panel", path(CLEAN_PATH))

        assertEquals(NO_CHAT, surfaces.owner.value)
    }

    @Test
    fun `the markdown tool is gone from the list the agent is offered`() {
        val offered = names(tools.panelDefinitions())

        assertTrue("show_markdown_document_panel" !in offered)
        assertTrue("show_file_document_panel" !in offered)
        assertTrue("show_document_panel" in offered)
        assertTrue("check_document_cells" in offered)
    }

    @Test
    fun `every tool the panel list names is one call handles`() {
        // The definitions and the `when` in `call` are two lists kept in step by
        // hand, and hand-kept lists drift: `set_permission_mode` is handled and
        // named in neither list. This is the check that catches the other
        // direction, where a tool is offered and answers nothing.
        val unhandled = names(tools.panelDefinitions()).filter { name ->
            call(name).text().startsWith("unknown tool")
        }

        assertEquals(emptyList<String>(), unhandled)
    }

    @Test
    fun `an action outside the seven never reaches the fire`() {
        val result = call("open_in_phone_app", JSONObject().put("action", "call"))

        assertTrue(result.optBoolean("isError"))
        assertTrue("dial" in result.text())
        assertEquals(emptyList<Handoff>(), fired)
        assertNull(surfaces.visible.value)
    }

    @Test
    fun `a fire that reaches past a link waits for the person`() = runBlocking {
        val asking = async(Dispatchers.Default) {
            tools.call("open_in_phone_app", launch(), NO_CHAT)
        }
        delay(SETTLE)

        // The question is on screen, naming what would receive it, and nothing
        // has left the app yet.
        val asked = surfaces.visible.value as Surface.Handoff
        assertEquals("Firefox", asked.app)
        assertEquals(emptyList<Handoff>(), fired)
        assertFalse(asking.isCompleted)

        surfaces.answer(asked, true)

        val result = withTimeout(SOON) { asking.await() }
        assertEquals(listOf(Handoff(Action.Launch, target = PACKAGE)), fired)
        assertEquals("Firefox took it", result.text())
    }

    @Test
    fun `a declined fire is information rather than an error`() = runBlocking {
        val asking = async(Dispatchers.Default) {
            tools.call("open_in_phone_app", launch(), NO_CHAT)
        }
        delay(SETTLE)

        surfaces.answer(surfaces.visible.value as Surface.Handoff, false)

        val result = withTimeout(SOON) { asking.await() }
        assertFalse(result.optBoolean("isError"))
        assertEquals("The operator declined", result.text())
        assertEquals(emptyList<Handoff>(), fired)
    }

    @Test
    fun `a plain link fires without ever asking`() {
        val result = call("open_in_phone_app", JSONObject().put("uri", "https://example.test"))

        assertEquals(listOf(Handoff(Action.View, uri = "https://example.test")), fired)
        assertNull(surfaces.visible.value)
        assertEquals("Firefox took it", result.text())
    }

    private fun launch() = JSONObject().put("action", "launch").put("package", PACKAGE)

    private fun names(definitions: org.json.JSONArray): List<String> =
        (0 until definitions.length()).map { definitions.getJSONObject(it).getString("name") }

    private companion object {
        const val CLEAN_PATH = "/doc.md"
        const val BROKEN_PATH = "/broken.md"
        const val PACKAGE = "org.mozilla.firefox"

        /** Stands in for the app's own `filesDir`; no test here names a file under it. */
        const val WRITABLE = "/data/user/0/apk.harness/files"

        /** Long enough for a coroutine on another thread to reach its wait. */
        const val SETTLE = 100L

        /** Short enough that a call which should not wait fails rather than hangs. */
        const val SOON = 2000L

        val CLEAN = """
            # Title

            ```harness-map
            id = "montmartre"
            title = "Famous residents of Montmartre"
            [[rows]]
            name = "Le Bateau-Lavoir"
            at = [48.886056, 2.337721]
            source = "reasoned"
            ```
        """.trimIndent()

        val BROKEN = """
            # Title

            ```harness-table
            from = "plaecs"
            columns = ["name"]
            ```
        """.trimIndent()
    }
}

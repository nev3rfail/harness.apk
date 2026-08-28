package apk.harness.ide

import apk.harness.ui.Rendered
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    private val surfaces = Surfaces()

    private val documents = mutableMapOf(
        CLEAN_PATH to CLEAN,
        BROKEN_PATH to BROKEN,
    )

    private val tools = Tools(
        surfaces = surfaces,
        openExternal = { true },
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

    private fun names(definitions: org.json.JSONArray): List<String> =
        (0 until definitions.length()).map { definitions.getJSONObject(it).getString("name") }

    private companion object {
        const val CLEAN_PATH = "/doc.md"
        const val BROKEN_PATH = "/broken.md"

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

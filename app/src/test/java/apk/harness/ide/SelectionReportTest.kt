package apk.harness.ide

import apk.harness.ui.markdownBlocks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionReportTest {

    @Test
    fun `nothing selected is sent as an explicit null, not an absent key`() {
        val params = selectionParams("/doc.md")
        assertEquals("/doc.md", params.getString("filePath"))
        assertTrue(params.has("selection"))
        assertTrue(params.isNull("selection"))
    }

    @Test
    fun `the end position names the line after the last selected one`() {
        val selection = selectionParams("/doc.md", 4, 7, "text").getJSONObject("selection")
        assertEquals(4, selection.getJSONObject("start").getInt("line"))
        assertEquals(8, selection.getJSONObject("end").getInt("line"))
    }

    @Test
    fun `a one-line selection is a range of one line, not an empty one`() {
        // The case that degenerates: character 0 of the only selected line is
        // the point before it, so an inclusive end sends `1 to 0`.
        val selection = selectionParams("/doc.md", 0, 0, "text").getJSONObject("selection")
        assertEquals(0, selection.getJSONObject("start").getInt("line"))
        assertEquals(1, selection.getJSONObject("end").getInt("line"))
    }

    @Test
    fun `both ends sit at character zero`() {
        val selection = selectionParams("/doc.md", 4, 7, "text").getJSONObject("selection")
        assertEquals(0, selection.getJSONObject("start").getInt("character"))
        assertEquals(0, selection.getJSONObject("end").getInt("character"))
    }

    @Test
    fun `the params carry the path and the selected text`() {
        val params = selectionParams("/doc.md", 4, 7, "text")
        assertEquals("/doc.md", params.getString("filePath"))
        assertEquals("text", params.getString("text"))
    }

    @Test
    fun `an open document with no selection reports its path alone`() {
        assertEquals(Report("/doc.md", null, null), reportFor(DOCUMENT, null))
    }

    @Test
    fun `a selection reports its range and the text on those lines`() {
        val report = reportFor(DOCUMENT, Selection("/doc.md", 2, 4))
        assertEquals(Report("/doc.md", 2..4, "first para\n\nsecond para"), report)
    }

    @Test
    fun `a cleared selection reports the path alone`() {
        assertEquals(Report("/doc.md", null, null), reportFor(DOCUMENT, null))
    }

    @Test
    fun `no document reports nothing`() {
        assertNull(reportFor(null, null))
        assertNull(reportFor(null, Selection("/doc.md", 2, 4)))
    }

    @Test
    fun `the reported text is sliced from the document at send time`() {
        val rewritten = DOCUMENT.copy(markdown = "# Title\n\nrewritten\n\nsecond para\n")
        assertEquals("rewritten", reportFor(rewritten, Selection("/doc.md", 2, 2))?.text)
    }

    @Test
    fun `the reported lines are zero-based against a document whose first line is line zero`() {
        // The first paragraph of a document that opens with one, taken through
        // the same arithmetic a tap goes through.
        val document = Surface.Document("/top.md", "top line\n\nand more\n", 0)
        val unit = unitOf(0, markdownBlocks(document.markdown).first())
        val report = reportFor(document, Selection(document.path, unit.lines.first, unit.lines.last))
        assertEquals(0..0, report?.lines)
        assertEquals("top line", report?.text)
    }

    @Test
    fun `a selection in a fenced source reports the file's line, not the fence's`() {
        // Body line 0 of a source file is document line 1. The wire carries
        // filePath, and the CLI reads that file, so the number beside the text
        // has to be the file's.
        val document = Surface.Document("/w/Foo.kt", "```kotlin\nval a = 1\nval b = 2\n```\n", -1)
        val report = reportFor(document, Selection("/w/Foo.kt", 1, 2))!!
        assertEquals(0..1, report.lines)
        assertEquals("val a = 1\nval b = 2", report.text)
    }

    @Test
    fun `a described file reports its path and no range`() {
        val document = Surface.Document("/w/blob.bin", "**Binary file**\n", null)
        val report = reportFor(document, Selection("/w/blob.bin", 0, 0))!!
        assertEquals(null, report.lines)
        assertEquals(null, report.text)
    }

    private companion object {
        val DOCUMENT = Surface.Document("/doc.md", "# Title\n\nfirst para\n\nsecond para\n", 0)
    }
}

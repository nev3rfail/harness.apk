package apk.harness.ide

import apk.harness.ui.markdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionReportTest {

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

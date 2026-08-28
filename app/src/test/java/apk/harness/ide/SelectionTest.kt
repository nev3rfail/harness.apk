package apk.harness.ide

import apk.harness.ui.MarkdownBlock
import apk.harness.ui.Spanned
import apk.harness.ui.markdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {

    @Test
    fun `a block on its own selects its own lines`() {
        val unit = unitOf(0, Spanned(MarkdownBlock.Prose("a"), 4..7))
        assertEquals(4..7, spanOf(unit, unit))
    }

    @Test
    fun `an anchor above a target spans both`() {
        val anchor = unitOf(0, Spanned(MarkdownBlock.Prose("a"), 2..3))
        val target = unitOf(2, Spanned(MarkdownBlock.Prose("b"), 8..11))
        assertEquals(2..11, spanOf(anchor, target))
    }

    @Test
    fun `an anchor below a target spans both, the same way round`() {
        val anchor = unitOf(2, Spanned(MarkdownBlock.Prose("b"), 8..11))
        val target = unitOf(0, Spanned(MarkdownBlock.Prose("a"), 2..3))
        assertEquals(2..11, spanOf(anchor, target))
    }

    @Test
    fun `a fence's first body line is the line after its opening`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "val a = 1"), 10..12)
        assertEquals(11..11, fenceUnit(0, fence, bodyLine = 0).lines)
    }

    @Test
    fun `a line inside a fence is not the whole fence`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a\nb\nc"), 0..4)
        val line = fenceUnit(0, fence, bodyLine = 1)
        val whole = unitOf(0, fence)
        assertEquals(2..2, line.lines)
        assert(line != whole)
    }

    @Test
    fun `two lines inside one fence span the lines between them`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a\nb\nc\nd"), 0..5)
        assertEquals(1..4, spanOf(fenceUnit(0, fence, 0), fenceUnit(0, fence, 3)))
    }

    @Test
    fun `a slice of the first line is the first line`() {
        assertEquals("first", sliceOf("first\nsecond\nthird\n", 0..0))
    }

    @Test
    fun `a slice keeps the newlines between its lines and none after`() {
        assertEquals("second\nthird", sliceOf("first\nsecond\nthird\nfourth\n", 1..2))
    }

    @Test
    fun `a slice past the end of a document takes what is there`() {
        assertEquals("third", sliceOf("first\nsecond\nthird\n", 2..9))
    }

    @Test
    fun `a selection over a real document names the paragraph it points at`() {
        val source = "# Title\n\nfirst para\n\nsecond para\n"
        val blocks = markdownBlocks(source)
        val unit = unitOf(2, blocks[2])
        val selection = Selection("/doc.md", unit.lines.first, unit.lines.last)
        assertEquals(4, selection.first)
        assertEquals(4, selection.last)
        assertEquals("second para", sliceOf(source, selection.first..selection.last))
    }
}

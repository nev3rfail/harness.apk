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

    @Test
    fun `a range is drawn for a person one line later than it is sent`() {
        assertEquals("L113-119", Selection("/doc.md", 112, 118).label())
    }

    @Test
    fun `one line is drawn as one number`() {
        assertEquals("L1", Selection("/doc.md", 0, 0).label())
    }

    @Test
    fun `a long press takes the unit it landed on and nothing else`() {
        val target = unitOf(3, Spanned(MarkdownBlock.Prose("a"), 8..11))
        val held = Selection("/doc.md", 0, 2)
        val next = pointAt("/doc.md", anchor = null, selection = held, target, anchoring = true)
        assertEquals(Selection("/doc.md", 8, 11), next)
    }

    @Test
    fun `a tap extends from the anchor to the unit it landed on`() {
        val anchor = unitOf(0, Spanned(MarkdownBlock.Prose("a"), 2..3))
        val target = unitOf(2, Spanned(MarkdownBlock.Prose("b"), 8..11))
        val held = Selection("/doc.md", 2, 3)
        assertEquals(
            Selection("/doc.md", 2, 11),
            pointAt("/doc.md", anchor, held, target, anchoring = false),
        )
    }

    @Test
    fun `a tap above the anchor extends upwards`() {
        val anchor = unitOf(2, Spanned(MarkdownBlock.Prose("b"), 8..11))
        val target = unitOf(0, Spanned(MarkdownBlock.Prose("a"), 2..3))
        val held = Selection("/doc.md", 8, 11)
        assertEquals(
            Selection("/doc.md", 2, 11),
            pointAt("/doc.md", anchor, held, target, anchoring = false),
        )
    }

    @Test
    fun `a tap on a unit the selection covers drops the selection`() {
        val target = unitOf(1, Spanned(MarkdownBlock.Prose("b"), 4..5))
        val held = Selection("/doc.md", 2, 9)
        val anchor = unitOf(0, Spanned(MarkdownBlock.Prose("a"), 2..3))
        assertEquals(null, pointAt("/doc.md", anchor, held, target, anchoring = false))
    }

    @Test
    fun `a tap with no anchor and no selection takes the unit alone`() {
        val target = unitOf(1, Spanned(MarkdownBlock.Prose("b"), 4..5))
        assertEquals(
            Selection("/doc.md", 4, 5),
            pointAt("/doc.md", anchor = null, selection = null, target, anchoring = false),
        )
    }

    @Test
    fun `the first tap after a restore extends from where the selection starts`() {
        // A restored document has a selection and no anchor: the run of pointing
        // that made it ended when the panel was put away.
        val held = Selection("/doc.md", 4, 5)
        val target = unitOf(3, Spanned(MarkdownBlock.Prose("c"), 10..12))
        assertEquals(
            Selection("/doc.md", 4, 12),
            pointAt("/doc.md", anchor = null, selection = held, target, anchoring = false),
        )
    }

    @Test
    fun `a fence draws its own body lines rather than the document's`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a\nb\nc"), 10..14)
        assertEquals(1..2, bodyLinesOf(fence, 12..13))
    }

    @Test
    fun `a fence taken whole draws from its first body line`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a\nb\nc"), 10..14)
        assertEquals(0..3, bodyLinesOf(fence, 10..14))
    }

    @Test
    fun `a selection ending above a fence draws nothing in it`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a"), 10..12)
        assertEquals(null, bodyLinesOf(fence, 2..9))
    }

    @Test
    fun `a selection starting below a fence draws nothing in it`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a"), 10..12)
        assertEquals(null, bodyLinesOf(fence, 13..20))
    }

    @Test
    fun `a selection reaching into a fence from above draws its first lines`() {
        val fence = Spanned(MarkdownBlock.Code("kotlin", "a\nb\nc"), 10..14)
        assertEquals(0..1, bodyLinesOf(fence, 4..12))
    }
}

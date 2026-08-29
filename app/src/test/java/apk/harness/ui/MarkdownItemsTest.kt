package apk.harness.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownItemsTest {

    @Test
    fun `a flat bullet list gives one item per line`() {
        val items = markdownBlocks("- first\n- second\n- third\n").single().items
        assertEquals(listOf(0..0, 1..1, 2..2), items.map { it.lines })
    }

    @Test
    fun `an ordered list gives one item per line`() {
        val items = markdownBlocks("1. first\n2. second\n").single().items
        assertEquals(listOf(0..0, 1..1), items.map { it.lines })
    }

    @Test
    fun `a nested list is a child of the item above it`() {
        val source = "- a\n  - b\n  - c\n- d\n"
        val items = markdownBlocks(source).single().items
        assertEquals(listOf(0..2, 3..3), items.map { it.lines })
        assertEquals(listOf(1..1, 2..2), items[0].children.map { it.lines })
        assertTrue(items[1].children.isEmpty())
    }

    @Test
    fun `three levels of nesting nest three deep`() {
        val source = "- a\n  - b\n    - c\n- d\n"
        val items = markdownBlocks(source).single().items
        assertEquals(0..2, items[0].lines)
        assertEquals(1..2, items[0].children.single().lines)
        assertEquals(2..2, items[0].children.single().children.single().lines)
    }

    @Test
    fun `a leaf spans its own lines`() {
        val source = "- a\n  - b\n"
        val items = markdownBlocks(source).single().items
        // D16: a nested item with nothing under it selects alone.
        assertEquals(1..1, items[0].children.single().lines)
    }

    @Test
    fun `an indented continuation belongs to the item above it`() {
        val source = "1. first\n   more of first\n2. second\n"
        val items = markdownBlocks(source).single().items
        assertEquals(listOf(0..1, 2..2), items.map { it.lines })
    }

    @Test
    fun `a loose list keeps its blank lines out of the items`() {
        val source = "1. first\n\n2. second\n\n3. third\n"
        val items = markdownBlocks(source).single().items
        assertEquals(listOf(0..0, 2..2, 4..4), items.map { it.lines })
    }

    @Test
    fun `a loose item's indented continuation is still its own`() {
        val source = "1. first\n\n   more of first\n\n2. second\n"
        val items = markdownBlocks(source).single().items
        assertEquals(listOf(0..2, 4..4), items.map { it.lines })
    }

    @Test
    fun `the last item of a list ends at the block's last line`() {
        val spanned = markdownBlocks("- a\n- b\n").single()
        assertEquals(spanned.lines.last, spanned.items.last().lines.last)
    }

    @Test
    fun `a list that follows a paragraph in the same unit still has items`() {
        val spanned = markdownBlocks("intro line\n- a\n- b\n").single()
        // The intro belongs to no item, so a touch on it takes the block whole.
        assertEquals(listOf(1..1, 2..2), spanned.items.map { it.lines })
    }

    @Test
    fun `prose after a list in the same unit ends the items`() {
        val spanned = markdownBlocks("- a\nback to prose\n").single()
        assertEquals(listOf(0..0), spanned.items.map { it.lines })
    }

    @Test
    fun `a prose block that is not a list carries no items`() {
        assertTrue(markdownBlocks("just a paragraph\n").single().items.isEmpty())
    }

    @Test
    fun `a list immediately followed by a fence ends at its own last line`() {
        val blocks = markdownBlocks("- a\n- b\n```\nx\n```\n")
        val list = blocks.first { it.block is MarkdownBlock.Prose }
        assertEquals(0..1, list.lines)
        assertEquals(listOf(0..0, 1..1), list.items.map { it.lines })
    }

    @Test
    fun `a list's items are offset by the line the block begins on`() {
        val blocks = markdownBlocks("a paragraph\n\n- first\n- second\n")
        assertEquals(listOf(2..2, 3..3), blocks[1].items.map { it.lines })
    }

    @Test
    fun `an offset at the start of a text resolves to its first line`() {
        assertEquals(0, lineOfOffset("- a\n- b\n", 0))
    }

    @Test
    fun `an offset resolves to the line it falls on`() {
        assertEquals(1, lineOfOffset("- a\n- b\n", 4))
        assertEquals(1, lineOfOffset("- a\n- b\n", 6))
        assertEquals(2, lineOfOffset("- a\n- b\n", 8))
    }

    @Test
    fun `every item is keyed by the line it begins on`() {
        val items = markdownBlocks("- a\n  - b\n- c\n").single().items
        assertEquals(setOf(0, 1, 2), itemsByLine(items).keys)
        assertEquals(1..1, itemsByLine(items)[1]?.lines)
    }

    @Test
    fun `the deepest item whose bounds contain a touch is the one under it`() {
        val items = markdownBlocks("- a\n  - b\n  - c\n- d\n").single().items
        val bounds = mapOf(
            0 to Rect(0f, 0f, 100f, 60f),
            1 to Rect(10f, 20f, 100f, 40f),
            2 to Rect(10f, 40f, 100f, 60f),
            3 to Rect(0f, 60f, 100f, 80f),
        )
        // A nested list is drawn inside its parent's item, so a parent's bounds
        // contain every child's and the deepest match is the one under the
        // finger.
        assertEquals(1..1, itemAt(items, bounds, 30f)?.lines)
        assertEquals(2..2, itemAt(items, bounds, 50f)?.lines)
    }

    @Test
    fun `a touch on a parent's own text takes the parent`() {
        val items = markdownBlocks("- a\n  - b\n").single().items
        val bounds = mapOf(
            0 to Rect(0f, 0f, 100f, 40f),
            1 to Rect(10f, 20f, 100f, 40f),
        )
        // Inside no child, so it takes the parent -- whose lines span its
        // subtree, which is D15.
        assertEquals(0..1, itemAt(items, bounds, 10f)?.lines)
    }

    @Test
    fun `a touch in no item at all is no item`() {
        val items = markdownBlocks("- a\n- b\n").single().items
        val bounds = mapOf(0 to Rect(0f, 0f, 100f, 20f), 1 to Rect(0f, 20f, 100f, 40f))
        assertNull(itemAt(items, bounds, 90f))
    }

    @Test
    fun `an item nothing recorded bounds for is skipped`() {
        val items = markdownBlocks("- a\n  - b\n").single().items
        val bounds = mapOf(0 to Rect(0f, 0f, 100f, 40f))
        assertEquals(0..1, itemAt(items, bounds, 30f)?.lines)
    }

    @Test
    fun `a block with no items has no item anywhere`() {
        assertNull(itemAt(emptyList(), emptyMap(), 10f))
    }
}

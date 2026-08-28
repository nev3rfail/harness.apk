package apk.harness.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownSpansTest {

    @Test
    fun `prose that begins at the top of the file starts at line zero`() {
        val blocks = markdownBlocks("first\nsecond\n")
        assertEquals(0..1, blocks.single().lines)
    }

    @Test
    fun `a fence spans its opening and closing lines`() {
        val blocks = markdownBlocks("intro\n\n```kotlin\nval a = 1\n```\nafter\n")
        val fence = blocks.first { it.block is MarkdownBlock.Code }
        assertEquals(2..4, fence.lines)
    }

    @Test
    fun `a fence that never closes runs to the last line`() {
        val blocks = markdownBlocks("```kotlin\nval a = 1\nval b = 2\n")
        assertEquals(0..2, blocks.single().lines)
    }

    @Test
    fun `a table spans its header, its separator and its rows`() {
        val blocks = markdownBlocks("| a | b |\n| - | - |\n| 1 | 2 |\n| 3 | 4 |\n")
        assertEquals(0..3, blocks.single().lines)
    }

    @Test
    fun `a table at the end of a file needs no line after it`() {
        val blocks = markdownBlocks("intro\n\n| a |\n| - |\n| 1 |")
        assertEquals(2..4, blocks.last().lines)
    }

    @Test
    fun `every block's lines are in order and none overlap`() {
        val blocks = markdownBlocks(DOCUMENT)
        blocks.zipWithNext { first, second ->
            assert(first.lines.last < second.lines.first) {
                "${first.lines} overlaps ${second.lines}"
            }
        }
    }

    @Test
    fun `prose splits at a blank line into one unit per paragraph`() {
        val blocks = markdownBlocks("# heading\n\nfirst para\n\nsecond para\n")
        assertEquals(3, blocks.size)
        assertEquals(0..0, blocks[0].lines)
        assertEquals(2..2, blocks[1].lines)
        assertEquals(4..4, blocks[2].lines)
        assertEquals("second para", (blocks[2].block as MarkdownBlock.Prose).text)
    }

    @Test
    fun `a paragraph of several lines is one unit`() {
        val blocks = markdownBlocks("one\ntwo\nthree\n\nnext\n")
        assertEquals(2, blocks.size)
        assertEquals(0..2, blocks[0].lines)
    }

    @Test
    fun `a loose ordered list stays one unit, so its numbering does not restart`() {
        val blocks = markdownBlocks("1. first\n\n2. second\n\n3. third\n")
        assertEquals(1, blocks.size)
        assertEquals(0..4, blocks.single().lines)
    }

    @Test
    fun `a loose bullet list stays one unit`() {
        val blocks = markdownBlocks("- first\n\n- second\n")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `a paragraph after a list is its own unit`() {
        val blocks = markdownBlocks("1. first\n\n2. second\n\nand then prose\n")
        assertEquals(2, blocks.size)
        assertEquals(0..2, blocks[0].lines)
        assertEquals(4..4, blocks[1].lines)
    }

    @Test
    fun `an indented continuation of a list item keeps the item's unit`() {
        val blocks = markdownBlocks("1. first\n\n   more of first\n\n2. second\n")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `several blank lines between paragraphs are not a unit of their own`() {
        val blocks = markdownBlocks("one\n\n\n\ntwo\n")
        assertEquals(2, blocks.size)
        assertEquals(0..0, blocks[0].lines)
        assertEquals(4..4, blocks[1].lines)
    }

    private companion object {
        val DOCUMENT = """
            # Title

            Some prose about a thing.

            | a | b |
            | - | - |
            | 1 | 2 |

            ```kotlin
            val a = 1
            ```

            Closing prose.
        """.trimIndent()
    }
}

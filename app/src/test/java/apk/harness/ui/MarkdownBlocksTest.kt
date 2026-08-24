package apk.harness.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `prose with no table stays one block`() {
        val blocks = markdownBlocks("# Title\n\nSome text.\n")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Prose)
    }

    @Test
    fun `a table is lifted out with the prose around it kept in order`() {
        val blocks = markdownBlocks(
            """
            Before.

            | Where | Cost |
            | --- | ---: |
            | Castelo | 15 EUR |
            | Se | 5 EUR |

            After.
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertEquals("Before.", (blocks[0] as MarkdownBlock.Prose).text)

        val table = blocks[1] as MarkdownBlock.Table
        assertEquals(listOf("Where", "Cost"), table.header)
        assertEquals(
            listOf(listOf("Castelo", "15 EUR"), listOf("Se", "5 EUR")),
            table.rows,
        )

        assertEquals("After.", (blocks[2] as MarkdownBlock.Prose).text)
    }

    @Test
    fun `a pipe line without a separator under it is not a table`() {
        val blocks = markdownBlocks("| this is just text |\nand so is this\n")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Prose)
    }

    @Test
    fun `alignment markers are accepted in the separator`() {
        val blocks = markdownBlocks("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |\n")
        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("a", "b", "c"), table.header)
        assertEquals(listOf(listOf("1", "2", "3")), table.rows)
    }

    @Test
    fun `a header with no rows is still a table`() {
        val blocks = markdownBlocks("| only | header |\n| --- | --- |\n")
        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("only", "header"), table.header)
        assertTrue(table.rows.isEmpty())
    }
}

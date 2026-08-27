package apk.harness.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    /** The one block a source is expected to produce, as a fence. */
    private fun fence(source: String): MarkdownBlock.Code =
        markdownBlocks(source).single() as MarkdownBlock.Code

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

    @Test
    fun `a fence is lifted out with the prose around it kept in order`() {
        val blocks = markdownBlocks(
            """
            Before.

            ```kotlin
            fun main() {}
            ```

            After.
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertEquals("Before.", (blocks[0] as MarkdownBlock.Prose).text)

        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {}", code.text)

        assertEquals("After.", (blocks[2] as MarkdownBlock.Prose).text)
    }

    @Test
    fun `a fence with no info string carries no language`() {
        assertNull(fence("```\nplain\n```\n").language)
    }

    @Test
    fun `an info string is read down to its first word, lowercased`() {
        assertEquals("kotlin", fence("```Kotlin title=x\ncode\n```\n").language)
    }

    @Test
    fun `a table inside a fenced block is code`() {
        val blocks = markdownBlocks(
            """
            Before.

            ```
            | Where | Cost |
            | --- | ---: |
            | Castelo | 15 EUR |
            ```

            After.
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        val code = blocks[1] as MarkdownBlock.Code
        assertTrue(code.text.contains("| Castelo | 15 EUR |"))
    }

    @Test
    fun `a short run does not close a longer fence`() {
        val code = fence("````\n```\n| a | b |\n| --- | --- |\n````\n")

        assertEquals("```\n| a | b |\n| --- | --- |", code.text)
    }

    @Test
    fun `a tilde fence is a fence`() {
        val code = fence("~~~\n| a | b |\n| --- | --- |\n~~~\n")

        assertEquals("| a | b |\n| --- | --- |", code.text)
    }

    @Test
    fun `a backtick run does not close a tilde fence`() {
        val code = fence("~~~\n```\n| a | b |\n| --- | --- |\n~~~\n")

        assertEquals("```\n| a | b |\n| --- | --- |", code.text)
    }

    @Test
    fun `an info string does not close a fence`() {
        val code = fence("```kotlin\n```kotlin\n| a | b |\n| --- | --- |\n```\n")

        assertEquals("kotlin", code.language)
        assertEquals("```kotlin\n| a | b |\n| --- | --- |", code.text)
    }

    @Test
    fun `an unclosed fence runs to the end`() {
        val code = fence("```\n| a | b |\n| --- | --- |\n")

        assertEquals("| a | b |\n| --- | --- |", code.text)
    }

    @Test
    fun `an empty fence is an empty block`() {
        assertEquals("", fence("```\n```\n").text)
    }

    @Test
    fun `a table after a closed fence is still a table`() {
        val blocks = markdownBlocks("```\ncode\n```\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n")

        assertEquals(2, blocks.size)
        assertEquals("code", (blocks[0] as MarkdownBlock.Code).text)
        val table = blocks[1] as MarkdownBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `two fences are two blocks`() {
        val blocks = markdownBlocks("```sh\none\n```\ntext\n```py\ntwo\n```\n")

        assertEquals(3, blocks.size)
        assertEquals("one", (blocks[0] as MarkdownBlock.Code).text)
        assertEquals("text", (blocks[1] as MarkdownBlock.Prose).text)
        assertEquals("two", (blocks[2] as MarkdownBlock.Code).text)
    }
}

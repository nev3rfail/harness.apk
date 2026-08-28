package apk.harness.cells

import apk.harness.ui.FileKind
import apk.harness.ui.MarkdownBlock
import apk.harness.ui.fileDocument
import apk.harness.ui.markdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoteTest {

    @Test
    fun `a map fence and a table fence become widgets`() {
        val promoted = promoteCells(markdownBlocks(DOCUMENT))
        val widgets = promoted.blocks.map { it.block }.filterIsInstance<MarkdownBlock.Widget>()
        assertEquals(2, widgets.size)
        assertEquals(CellKind.Map, widgets[0].cell.kind)
        assertEquals(CellKind.Table, widgets[1].cell.kind)
        assertEquals(emptyList<CellProblem>(), promoted.problems)
    }

    @Test
    fun `a promoted cell keeps the fence's own lines`() {
        val fence = markdownBlocks(DOCUMENT).first { it.block is MarkdownBlock.Code }
        val widget = promoteCells(markdownBlocks(DOCUMENT))
            .blocks.first { it.block is MarkdownBlock.Widget }
        assertEquals(fence.lines, widget.lines)
    }

    @Test
    fun `the prose around the cells is untouched`() {
        val before = markdownBlocks(DOCUMENT).filter { it.block is MarkdownBlock.Prose }
        val after = promoteCells(markdownBlocks(DOCUMENT))
            .blocks.filter { it.block is MarkdownBlock.Prose }
        assertEquals(before, after)
    }

    @Test
    fun `a fence of another language is left alone`() {
        val blocks = promoteCells(markdownBlocks("```kotlin\nval a = 1\n```\n")).blocks
        assertTrue(blocks.single().block is MarkdownBlock.Code)
    }

    @Test
    fun `a cell that does not check stays code and carries its reason`() {
        val source = DOCUMENT.replace("from = \"montmartre\"", "from = \"plaecs\"")
        val promoted = promoteCells(markdownBlocks(source))
        val code = promoted.blocks.map { it.block }.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("harness-table", code.language)
        assertTrue(code.text.contains("unknown id: plaecs"))
        assertTrue(promoted.problems.any { it.reason == "unknown id: plaecs" })
    }

    @Test
    fun `a broken cell does not blank the one beside it`() {
        val source = DOCUMENT.replace("id = \"montmartre\"", "")
        val promoted = promoteCells(markdownBlocks(source))
        // The map still checks and still draws; the table's reference is what broke.
        assertTrue(promoted.blocks.map { it.block }.any { it is MarkdownBlock.Widget })
        assertTrue(promoted.blocks.map { it.block }.any { it is MarkdownBlock.Code })
    }

    @Test
    fun `nine cells render when the tenth does not`() {
        val good = (1..9).joinToString("\n\n") { cell(it) }
        val bad = "```harness-map\nid = \"bad\"\nzoom = 15\n```"
        val promoted = promoteCells(markdownBlocks("$good\n\n$bad\n"))
        val widgets = promoted.blocks.map { it.block }.filterIsInstance<MarkdownBlock.Widget>()
        assertEquals(9, widgets.size)
        assertEquals(1, promoted.problems.size)
    }

    @Test
    fun `a problem's line is the line in the document, not in the fence`() {
        val promoted = promoteCells(markdownBlocks("intro\n\n```harness-map\nzoom = 15\n```\n"))
        assertEquals(2, promoted.problems.single().line)
    }

    @Test
    fun `a cell inside a source file is interior text and is never promoted`() {
        // A .kt file becomes one fence from top to bottom, so a harness-map block
        // inside it is code. The same block in a .md file is a cell.
        val source = "```harness-map\nid = \"a\"\ntitle = \"a\"\n```\n"
        val wrapped = fileDocument(FileKind.Text("kotlin"), source)
        val blocks = promoteCells(markdownBlocks(wrapped)).blocks.map { it.block }
        assertEquals("kotlin", (blocks.single() as MarkdownBlock.Code).language)

        val direct = promoteCells(markdownBlocks(source)).blocks.map { it.block }
        assertTrue(direct.single() is MarkdownBlock.Widget)
    }

    @Test
    fun `a document with no cells is returned as it arrived`() {
        val blocks = markdownBlocks("# heading\n\nsome prose\n")
        assertEquals(blocks, promoteCells(blocks).blocks)
    }

    @Test
    fun `the second cell to claim an id degrades and the first does not`() {
        val twice = cell(1) + "\n\n" + cell(1)
        val promoted = promoteCells(markdownBlocks(twice))
        val blocks = promoted.blocks.map { it.block }
        assertTrue(blocks.first() is MarkdownBlock.Widget)
        assertTrue(blocks.last() is MarkdownBlock.Code)
        assertTrue(promoted.problems.single().reason.contains("duplicate id: place1"))
    }

    private fun cell(index: Int) = """
        ```harness-map
        id = "place$index"
        title = "place $index"
        [[rows]]
        name = "a"
        at = [0.0, 0.0]
        source = "reasoned"
        ```
    """.trimIndent()

    private companion object {
        val DOCUMENT = """
            ## Famous residents of Montmartre

            Picasso's studio and Van Gogh's apartment are four minutes apart on foot.

            ```harness-map
            id = "montmartre"
            title = "Famous residents of Montmartre"
            zoom = 15

            [[rows]]
            name = "Le Bateau-Lavoir"
            at = [48.886056, 2.337721]
            who = "Picasso"
            years = "1904-1909"
            source = { at = "https://maps.google.com/?cid=4568" }
            ```

            ```harness-table
            from = "montmartre"
            columns = ["who", "name", "years"]
            sort = "years"
            ```
        """.trimIndent()
    }
}

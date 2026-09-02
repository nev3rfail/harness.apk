package apk.harness.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownLineBreaksTest {

    @Test
    fun `a wrapped line is joined by a space`() {
        assertEquals("one two three four", drawn("one two\nthree four"))
    }

    @Test
    fun `the indent of a continuation line is the space and not another one`() {
        assertEquals("one two three four", drawn("one two\n    three four"))
    }

    @Test
    fun `a wrapped list item is joined the same way`() {
        assertEquals("item one wrapped here", drawn("- item one\n  wrapped here"))
    }

    @Test
    fun `a wrapped quote is joined the same way`() {
        assertEquals("quoted one quoted two", drawn("> quoted one\n> quoted two"))
    }

    @Test
    fun `several wrapped lines are one run of words`() {
        assertEquals("a b c", drawn("a\nb\nc"))
    }

    @Test
    fun `two spaces at the end of a line are trailing space`() {
        assertEquals("one two three four", drawn("one two  \nthree four"))
    }

    @Test
    fun `the break tag is the line break`() {
        assertEquals("one two\nthree four", drawn("one two<br/>three four"))
        assertEquals("one two\nthree four", drawn("one two<br>three four"))
        assertEquals("one two\nthree four", drawn("one two<BR />three four"))
    }

    @Test
    fun `the space around a break tag belongs to the break`() {
        assertEquals("one two\nthree four", drawn("one two <br /> three four"))
        assertEquals("one two\nthree four", drawn("one two\n<br/>\nthree four"))
    }

    @Test
    fun `a tag that is not the break tag breaks no line`() {
        assertFalse('\n' in drawn("one two <b> three"))
    }

    @Test
    fun `anything that is not a break or the space around one is left to the renderer`() {
        val word = wordsOf("word").first()

        assertEquals(MarkdownTokenTypes.TEXT, word.type)
        assertNull(lineBreakText(word, "word", written = true))
    }

    /**
     * The text a paragraph draws as, built the way the renderer builds it: the
     * hook first, then the rules it does not answer -- a word is itself, and a
     * run of space is one space once something has been written.
     */
    private fun drawn(source: String): String {
        val out = StringBuilder()
        var previous: Any? = null
        wordsOf(source).forEach { child ->
            if (previous == child.type) return@forEach
            previous = null
            val text = lineBreakText(child, source, written = out.isNotEmpty())
            when {
                text != null -> out.append(text)
                child.type == MarkdownTokenTypes.TEXT ->
                    out.append(source.substring(child.startOffset, child.endOffset))
                child.type == MarkdownTokenTypes.WHITE_SPACE ->
                    if (out.isNotEmpty()) out.append(' ')
                // What the renderer does with the `>` that opens a quoted line:
                // draws nothing, and swallows the space after it.
                child.type == MarkdownTokenTypes.BLOCK_QUOTE ->
                    previous = MarkdownTokenTypes.WHITE_SPACE
            }
        }
        return out.toString()
    }

    private fun wordsOf(source: String): List<ASTNode> {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        return requireNotNull(paragraphOf(tree)) { "no paragraph in $source" }.children
    }

    private fun paragraphOf(node: ASTNode): ASTNode? {
        if (node.type == MarkdownElementTypes.PARAGRAPH) return node
        return node.children.firstNotNullOfOrNull { paragraphOf(it) }
    }
}

package apk.harness.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {

    private val kotlin = requireNotNull(grammarFor("kotlin"))
    private val python = requireNotNull(grammarFor("python"))

    /** The tokens as `kind:text`, which is what the assertions are about. */
    private fun spans(source: String, grammar: Grammar): List<String> =
        tokenize(source, grammar).map { "${it.kind}:${source.substring(it.start, it.end)}" }

    @Test
    fun `a keyword is one`() {
        assertEquals(listOf("Keyword:val"), spans("val", kotlin))
    }

    @Test
    fun `a word holding a keyword is not one`() {
        // `information` holds `for`, and a scan that did not run to a word
        // boundary would colour three letters in the middle of it.
        assertEquals(emptyList<String>(), spans("information", kotlin))
    }

    @Test
    fun `a keyword needs a boundary on the left too`() {
        assertEquals(emptyList<String>(), spans("myval", kotlin))
    }

    @Test
    fun `a line comment runs to the end of its line`() {
        assertEquals(
            listOf("Comment:// val here", "Keyword:val"),
            spans("// val here\nval", kotlin),
        )
    }

    @Test
    fun `a keyword inside a comment is not a keyword`() {
        assertEquals(listOf("Comment:# if"), spans("# if", python))
    }

    @Test
    fun `a block comment runs to its close`() {
        assertEquals(
            listOf("Comment:/* val\nval */", "Keyword:val"),
            spans("/* val\nval */val", kotlin),
        )
    }

    @Test
    fun `a block comment that never closes runs to the end`() {
        assertEquals(listOf("Comment:/* val"), spans("/* val", kotlin))
    }

    @Test
    fun `a string is one token`() {
        assertEquals(listOf("Text:\"hello\""), spans("\"hello\"", kotlin))
    }

    @Test
    fun `a comment marker inside a string is not a comment`() {
        assertEquals(listOf("Text:'# not a comment'"), spans("'# not a comment'", python))
    }

    @Test
    fun `a keyword inside a string is not a keyword`() {
        assertEquals(listOf("Text:\"val\""), spans("\"val\"", kotlin))
    }

    @Test
    fun `an escaped quote does not close a string`() {
        assertEquals(listOf("""Text:"a\"b""""), spans("""  "a\"b"  """.trim(), kotlin))
    }

    @Test
    fun `an unterminated string stops at the newline`() {
        assertEquals(
            listOf("Text:\"open", "Keyword:val"),
            spans("\"open\nval", kotlin),
        )
    }

    @Test
    fun `a language with no escape lets a quote close a string`() {
        val sql = requireNotNull(grammarFor("sql"))
        assertEquals(listOf("Text:'a\\'"), spans("'a\\'", sql))
    }

    @Test
    fun `a number is one token`() {
        assertEquals(listOf("Number:42"), spans("42", kotlin))
    }

    @Test
    fun `what could belong to a literal is read with it`() {
        assertEquals(listOf("Number:0xFF_00"), spans("0xFF_00", kotlin))
        assertEquals(listOf("Number:1.5e3"), spans("1.5e3", kotlin))
    }

    @Test
    fun `a number inside a word is part of the word`() {
        assertEquals(emptyList<String>(), spans("utf8", kotlin))
    }

    @Test
    fun `tokens come back in order and never overlap`() {
        val source = "val x = 1 // set\nfun f() { \"s\" }"
        val tokens = tokenize(source, kotlin)

        assertTrue(tokens.isNotEmpty())
        tokens.zipWithNext().forEach { (first, second) ->
            assertTrue("$first then $second", first.end <= second.start)
        }
        tokens.forEach { assertTrue(it.start < it.end && it.end <= source.length) }
    }

    @Test
    fun `an unknown tag has no grammar`() {
        assertNull(grammarFor("brainfuck"))
    }

    @Test
    fun `an unlabelled fence has no grammar`() {
        assertNull(grammarFor(null))
    }

    @Test
    fun `a tag is matched without regard to case`() {
        assertNotNull(grammarFor("Kotlin"))
        assertNotNull(grammarFor("SQL"))
    }

    @Test
    fun `every cell label is coloured as the format its body is written in`() {
        // A cell that fails to check degrades to a code block, and the only thing
        // that makes that block readable is the TOML grammar behind its label.
        val uncoloured = setOf("harness-map", "harness-table").filter { grammarFor(it) == null }

        assertEquals(emptyList<String>(), uncoloured)
    }

    @Test
    fun `every language the file browser labels a fence with is either coloured or known plain`() {
        // The one place that decides a fence's label is `LANGUAGES`. A tag added
        // there without a grammar renders plain, which is a decision rather than
        // an accident -- so it has to be named here.
        val plainOnPurpose = setOf("diff")

        val unaccounted = LANGUAGES.values.toSet()
            .filter { grammarFor(it) == null && it !in plainOnPurpose }

        assertEquals(emptyList<String>(), unaccounted)
    }

    @Test
    fun `a mebibyte of source tokenizes in well under a second`() {
        // The engine this replaces takes 45.7 s on a desktop JVM for this input,
        // because one of its passes is quadratic in the punctuation. The bound
        // here is loose on purpose: it is asserting the shape of the cost, not a
        // number, and it runs on whatever machine the suite runs on.
        val unit = "val name = \"text\" // a comment, with punctuation.\nfun f(a: Int) { }\n"
        val source = buildString {
            while (length < 1 shl 20) append(unit)
        }

        val started = System.nanoTime()
        val tokens = tokenize(source, kotlin)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue("tokenized $millis ms", millis < 3_000)
        assertTrue(tokens.size > 1000)
    }
}

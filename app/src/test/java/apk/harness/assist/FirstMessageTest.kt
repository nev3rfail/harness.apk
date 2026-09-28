package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstMessageTest {

    @Test
    fun `a quote alone is the selected text fenced`() {
        assertEquals("Selected text:\n```\nq\n```", compose("q", "", emptyList()))
    }

    @Test
    fun `a quote and a question`() {
        assertEquals("Selected text:\n```\nq\n```\n\nWhat is this?", compose("q", "What is this?", emptyList()))
    }

    @Test
    fun `files alone are one line each`() {
        assertEquals("Attached: /i/a.pdf\nAttached: /i/b.zip", compose(null, "", listOf("/i/a.pdf", "/i/b.zip")))
    }

    @Test
    fun `a quote past the limit is cut with a line saying so`() {
        val message = compose("x".repeat(QUOTE_LIMIT + 5), "", emptyList())
        assertTrue(message.contains("x".repeat(QUOTE_LIMIT) + "\n```"))
        assertFalse(message.contains("x".repeat(QUOTE_LIMIT + 1)))
        assertTrue(message.endsWith(QUOTE_CUT))
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val quote = "a".repeat(QUOTE_LIMIT - 1) + "😀" + "b"
        val message = compose(quote, "", emptyList())
        assertFalse(message.contains("\uD83D\n"))
        assertTrue(message.contains("a".repeat(QUOTE_LIMIT - 1) + "\n```"))
    }
}

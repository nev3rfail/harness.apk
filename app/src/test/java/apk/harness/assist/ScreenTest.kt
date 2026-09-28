package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {

    private fun node(text: String? = null, vararg children: ScreenNode, description: String? = null, hint: String? = null,
                     visible: Boolean = true, inputType: Int = 0, webDomain: String? = null, htmlType: String? = null) =
        ScreenNode(text, description, hint, visible, inputType, webDomain, htmlType, children.toList())

    @Test
    fun `nested text is one line per node in order`() {
        assertEquals("a\nb\nc", flattenScreen(listOf(node("a", node("b"), node("c")))))
    }

    @Test
    fun `password fields of every variation are skipped`() {
        val text = 0x1
        val number = 0x2
        for (type in listOf(text or 0x80, text or 0x90, text or 0xe0, number or 0x10)) {
            assertEquals("user", flattenScreen(listOf(node("user", node("hunter2", inputType = type)))))
        }
    }

    @Test
    fun `a number field whose variation shares a text password's bits is kept`() {
        // TYPE_CLASS_NUMBER | 0x80 is no number password.
        assertEquals("42", flattenScreen(listOf(node("42", inputType = 0x2 or 0x80))))
        // TYPE_CLASS_TEXT | 0x10 is a URI field, not a password.
        assertEquals("a.b", flattenScreen(listOf(node("a.b", inputType = 0x1 or 0x10))))
    }

    @Test
    fun `a web password field marked only by its html type is skipped`() {
        assertEquals("login", flattenScreen(listOf(node("login", node("hunter2", htmlType = "password")))))
    }

    @Test
    fun `an invisible subtree is skipped`() {
        assertEquals("a", flattenScreen(listOf(node("a", node("hidden", node("under"), visible = false)))))
    }

    @Test
    fun `a hint is written only without text`() {
        assertEquals("[hint: Search]", flattenScreen(listOf(node(hint = "Search"))))
        assertEquals("typed", flattenScreen(listOf(node("typed", hint = "Search"))))
    }

    @Test
    fun `a description equal to the text is not repeated`() {
        assertEquals("Send", flattenScreen(listOf(node("Send", description = "Send"))))
        assertEquals("Send (paper plane)", flattenScreen(listOf(node("Send", description = "paper plane"))))
    }

    @Test
    fun `the web domain and the page url are named`() {
        assertEquals("[url https://x.org/a]\n[web x.org]\nhi", flattenScreen(listOf(node("hi", webDomain = "x.org")), webUri = "https://x.org/a"))
    }

    @Test
    fun `nodes past depth 64 are skipped`() {
        var chain = node("70")
        for (depth in 69 downTo 0) chain = node("$depth", chain)
        val lines = flattenScreen(listOf(chain)).lines()
        assertEquals("64", lines.last())
        assertEquals(65, lines.size)
    }

    @Test
    fun `the text stops at the limit with a line saying it was cut`() {
        val flat = flattenScreen(listOf(node(null, *Array(5_000) { node("line $it") })))
        assertTrue(flat.length <= SCREEN_TEXT_LIMIT)
        assertTrue(flat.endsWith(SCREEN_CUT))
    }

    @Test
    fun `the fence is longer than any run of backticks inside`() {
        val block = fenced("a ``` b ```` c")
        assertTrue(block.startsWith("`````\n"))
        assertTrue(block.endsWith("\n`````"))
        assertEquals("```\nplain\n```", fenced("plain"))
    }

    @Test
    fun `screen text is named by its app and fenced`() {
        assertEquals("Screen text from Chrome\n```\nhi\n```", screenBlock("Chrome", "hi"))
        assertFalse(screenBlock("Chrome", "```").contains("\n```\n```\n"))
    }
}

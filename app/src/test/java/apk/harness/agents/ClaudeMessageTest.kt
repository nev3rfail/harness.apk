package apk.harness.agents

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClaudeMessageTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A sink that says whether anything closed it. */
    private class Sink : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true }
        fun text() = String(toByteArray(), Charsets.UTF_8)
    }

    private fun write(texts: List<String>, vararg images: HeadlessImage): Sink =
        Sink().also { ClaudeBackend.writeMessage(it, texts, images.toList()) }

    private fun content(sink: Sink) = JSONObject(sink.text().trimEnd('\n')).getJSONObject("message").getJSONArray("content")

    @Test
    fun `a text message is one line holding one text block`() {
        val sink = write(listOf("say \"hi\"\nthen stop"))
        val text = sink.text()
        assertTrue(text.endsWith("\n"))
        assertEquals(1, text.count { it == '\n' })
        val message = JSONObject(text.trimEnd('\n'))
        assertEquals("user", message.getString("type"))
        assertTrue(message.isNull("parent_tool_use_id"))
        assertEquals("user", message.getJSONObject("message").getString("role"))
        val blocks = content(sink)
        assertEquals(1, blocks.length())
        assertEquals("text", blocks.getJSONObject(0).getString("type"))
        assertEquals("say \"hi\"\nthen stop", blocks.getJSONObject(0).getString("text"))
    }

    @Test
    fun `each image follows the text as the bytes of its file in base64`() {
        val a = folder.newFile("a.png").apply { writeBytes(ByteArray(100_001) { it.toByte() }) }
        val b = folder.newFile("b.jpg").apply { writeBytes(ByteArray(7) { (255 - it).toByte() }) }
        val blocks = content(write(listOf("two"), HeadlessImage(a, "image/png"), HeadlessImage(b, "image/jpeg")))
        assertEquals(3, blocks.length())
        for ((index, file, type) in listOf(Triple(1, a, "image/png"), Triple(2, b, "image/jpeg"))) {
            val block = blocks.getJSONObject(index)
            assertEquals("image", block.getString("type"))
            val source = block.getJSONObject("source")
            assertEquals("base64", source.getString("type"))
            assertEquals(type, source.getString("media_type"))
            assertArrayEquals(file.readBytes(), Base64.getDecoder().decode(source.getString("data")))
        }
    }

    @Test
    fun `an image with no text writes no text block`() {
        val a = folder.newFile("a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val blocks = content(write(listOf(""), HeadlessImage(a, "image/jpeg")))
        assertEquals(1, blocks.length())
        assertEquals("image", blocks.getJSONObject(0).getString("type"))
    }

    @Test
    fun `screen text is its own text block ahead of the operator's`() {
        val screen = "Screen text from Maps\n```\nt\n```"
        val blocks = content(write(listOf(screen, "where is this")))
        assertEquals(2, blocks.length())
        assertEquals(screen, blocks.getJSONObject(0).getString("text"))
        assertEquals("where is this", blocks.getJSONObject(1).getString("text"))
    }

    @Test
    fun `the sink is left open and writable`() {
        val a = folder.newFile("a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sink = write(listOf("x"), HeadlessImage(a, "image/jpeg"))
        assertFalse(sink.closed)
        sink.write('y'.code)
        assertTrue(sink.text().endsWith("\ny"))
    }

    @Test
    fun `the interrupt is a control request under the id it is given`() {
        val request = JSONObject(ClaudeBackend.interrupt("int-1"))
        assertEquals("control_request", request.getString("type"))
        assertEquals("int-1", request.getString("request_id"))
        assertEquals("interrupt", request.getJSONObject("request").getString("subtype"))
    }
}

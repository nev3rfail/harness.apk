package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskRequestTest {

    /** This app's own authority, as the activity answers it from resolveContentProvider. */
    private fun askRequest(handed: Handed) = askRequest(handed) { it == "btl.harness.files" }

    private fun image(n: Int) = HandedStream("content://media/external/images/media/$n", "image/jpeg", "IMG_$n.jpg", 1000)
    private val pdf = HandedStream("content://files/doc/1", "application/pdf", "a.pdf", 5000)

    @Test
    fun `selected text is a quote with the field empty`() {
        val request = askRequest(Handed(ACTION_PROCESS_TEXT, selected = "the selection"))
        assertEquals("the selection", request.quote)
        assertEquals("", request.field)
    }

    @Test
    fun `shared text fills the field, the subject on its own line first`() {
        assertEquals("Title\nbody", askRequest(Handed(ACTION_SEND, text = "body", subject = "Title")).field)
        assertEquals("body", askRequest(Handed(ACTION_SEND, text = "body")).field)
    }

    @Test
    fun `a shared link fills the field`() {
        assertEquals("https://example.org/a", askRequest(Handed(ACTION_SEND, text = "https://example.org/a")).field)
    }

    @Test
    fun `one image is an image attachment`() {
        assertEquals(listOf(image(1)), askRequest(Handed(ACTION_SEND, streams = listOf(image(1)))).images)
    }

    @Test
    fun `images and a pdf in one share are split into images and files`() {
        val request = askRequest(Handed(ACTION_SEND_MULTIPLE, streams = listOf(image(1), pdf, image(2))))
        assertEquals(listOf(image(1), image(2)), request.images)
        assertEquals(listOf(pdf), request.files)
    }

    @Test
    fun `text and an image stream in one send are both kept`() {
        val request = askRequest(Handed(ACTION_SEND, text = "a page", streams = listOf(image(1))))
        assertEquals("a page", request.field)
        assertEquals(listOf(image(1)), request.images)
    }

    @Test
    fun `text only in the clip data fills the field`() {
        assertEquals("clipped", askRequest(Handed(ACTION_SEND, clipText = "clipped")).field)
    }

    @Test
    fun `of twenty five images twenty go as blocks and five as files`() {
        val split = shareImages(List(25) { 1000L })
        assertEquals((0 until 20).toList(), split.blocks)
        assertEquals((20 until 25).toList(), split.files)
    }

    @Test
    fun `images whose encodings cross the budget before the twentieth go as files from the one crossing it`() {
        // 2.4 MiB encoded is 3.2 MiB of base64: six fit 20 MiB, the seventh crosses it.
        val split = shareImages(List(10) { 2_516_583L })
        assertEquals((0 until 6).toList(), split.blocks)
        assertEquals((6 until 10).toList(), split.files)
    }

    @Test
    fun `an image that did not decode goes as a file, in its place`() {
        assertEquals(ShareImages(listOf(0, 2), listOf(1)), shareImages(listOf(1000L, null, 1000L)))
    }

    @Test
    fun `nothing usable is an empty request`() {
        assertTrue(askRequest(Handed(ACTION_SEND)).empty)
    }

    @Test
    fun `a stream that is not a content uri is refused with its reason`() {
        val file = HandedStream("file:///data/data/btl.harness/files/.claude/.credentials.json", null, null, null)
        val request = askRequest(Handed(ACTION_SEND, streams = listOf(file)))
        assertTrue(request.empty)
        assertEquals(listOf(Refusal("file:///data/data/btl.harness/files/.claude/.credentials.json", NOT_SHARED, null)), request.refused)
    }

    @Test
    fun `a stream of this app's own authority is refused with its reason`() {
        val own = HandedStream("content://btl.harness.files/agent/.claude/.credentials.json", null, ".credentials.json", 400)
        val request = askRequest(Handed(ACTION_SEND, text = "look", streams = listOf(own)))
        assertEquals(emptyList<HandedStream>(), request.files)
        assertEquals(listOf(Refusal(".credentials.json", OWN_FILES, 400)), request.refused)
        assertEquals("look", request.field)
    }

    @Test
    fun `blank text and subject are absent`() {
        assertTrue(askRequest(Handed(ACTION_SEND, text = "  ", subject = "")).empty)
        assertEquals("body", askRequest(Handed(ACTION_SEND, text = "body", subject = " ")).field)
    }
}

package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskRequestTest {

    /** This app's own authority, as the activity answers it from resolveContentProvider. */
    private val isOwn = { authority: String -> authority == "dev.harness.files" }

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
    fun `a refused stream is named with its reason and not attached`() {
        val own = HandedStream("content://dev.harness.files/agent/.claude/.credentials.json", null, "_credentials.json", null, OWN_FILES)
        val request = askRequest(Handed(ACTION_SEND, text = "look", streams = listOf(own)))
        assertEquals(emptyList<HandedStream>(), request.files)
        assertEquals(listOf(Refusal("_credentials.json", OWN_FILES, null)), request.refused)
        assertEquals("look", request.field)
    }

    @Test
    fun `a scheme other than content is refused`() {
        assertEquals(NOT_SHARED, streamRefusal("file", null, isOwn))
        assertEquals(NOT_SHARED, streamRefusal(null, null, isOwn))
        // content:foo has no authority.
        assertEquals(NOT_SHARED, streamRefusal("content", null, isOwn))
        assertEquals(NOT_SHARED, streamRefusal("content", "", isOwn))
    }

    /**
     * Each form with the scheme and the decoded authority android.net.Uri reads
     * from it, which the resolver opens the provider by.
     */
    @Test
    fun `this app's own authority is refused in every form the resolver opens it by`() {
        val forms = listOf(
            "content://dev.harness.files/agent/x" to "dev.harness.files",
            "content://dev.harness%2Efiles/agent/x" to "dev.harness.files",
            "content://0@dev.harness.files/agent/x" to "0@dev.harness.files",
            "content://10@dev.harness.files/agent/x" to "10@dev.harness.files",
            "content://dev.harness.files?/agent/x" to "dev.harness.files",
            "content://dev.harness.files#/agent/x" to "dev.harness.files",
        )
        for ((uri, authority) in forms) assertEquals(uri, OWN_FILES, streamRefusal("content", authority, isOwn))
        assertEquals(OWN_FILES, streamRefusal("CONTENT", "dev.harness.files", isOwn))
    }

    @Test
    fun `another app's authority is read`() {
        assertNull(streamRefusal("content", "com.android.providers.media.documents", isOwn))
        assertNull(streamRefusal("content", "10@media", isOwn))
    }

    @Test
    fun `blank text and subject are absent`() {
        assertTrue(askRequest(Handed(ACTION_SEND, text = "  ", subject = "")).empty)
        assertEquals("body", askRequest(Handed(ACTION_SEND, text = "body", subject = " ")).field)
    }
}

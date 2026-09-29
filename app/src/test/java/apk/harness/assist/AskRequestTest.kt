package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskRequestTest {

    /** This app's own authority, as the activity answers it from resolveContentProvider. */
    private val isOwn = { authority: String -> authority == "dev.harness.files" }

    private fun image(n: Int) = HandedStream("content://media/external/images/media/$n", "$n")
    private val pdf = HandedStream("content://files/doc/1", "1")

    @Test
    fun `selected text is a quote with the field empty`() {
        val request = askRequest(Handed<String>(ACTION_PROCESS_TEXT, selected = "the selection"))
        assertEquals("the selection", request.quote)
        assertEquals("", request.field)
    }

    @Test
    fun `shared text fills the field, the subject on its own line first`() {
        assertEquals("Title\nbody", askRequest(Handed<String>(ACTION_SEND, text = "body", subject = "Title")).field)
        assertEquals("body", askRequest(Handed<String>(ACTION_SEND, text = "body")).field)
    }

    @Test
    fun `a shared link fills the field`() {
        assertEquals("https://example.org/a", askRequest(Handed<String>(ACTION_SEND, text = "https://example.org/a")).field)
    }

    @Test
    fun `the streams not refused are kept, in order, with the text`() {
        val request = askRequest(Handed(ACTION_SEND_MULTIPLE, text = "a page", streams = listOf(image(1), pdf, image(2))))
        assertEquals("a page", request.field)
        assertEquals(listOf(image(1), pdf, image(2)), request.streams)
    }

    @Test
    fun `a stream is an image by its type`() {
        assertTrue(isImage("image/heic"))
        assertFalse(isImage("application/pdf"))
        assertFalse(isImage(null))
    }

    @Test
    fun `past the first hundred streams each is refused without being asked about`() {
        val named = List(102) { "u$it" to true }
        val asked = ArrayList<String>()
        val decided = decided(named) { uri, _ -> asked += uri; null }
        assertEquals(100, asked.size)
        assertEquals(List(100) { null } + listOf(TOO_MANY, TOO_MANY), decided.map { it.second })
        assertEquals(named.map { it.first }, decided.map { it.first })
    }

    @Test
    fun `text only in the clip data fills the field`() {
        assertEquals("clipped", askRequest(Handed<String>(ACTION_SEND, clipText = "clipped")).field)
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
    fun `encoding stops once twenty are blocks or the budget is spent`() {
        assertTrue(roomForBlocks(emptyList()))
        assertTrue(roomForBlocks(List(19) { 1000L }))
        assertFalse(roomForBlocks(List(20) { 1000L }))
        // Six of 2.4 MiB fit the budget; the seventh crosses it.
        assertTrue(roomForBlocks(List(6) { 2_516_583L }))
        assertFalse(roomForBlocks(List(7) { 2_516_583L }))
        // One that did not decode spends nothing.
        assertTrue(roomForBlocks(listOf(1000L, null)))
    }

    @Test
    fun `nothing usable is an empty request`() {
        assertTrue(askRequest(Handed<String>(ACTION_SEND)).empty)
    }

    @Test
    fun `a refused stream is named with its reason and not attached`() {
        val own = HandedStream("content://dev.harness.files/agent/.claude/.credentials.json", "_credentials.json", OWN_FILES)
        val request = askRequest(Handed(ACTION_SEND, text = "look", streams = listOf(own)))
        assertEquals(emptyList<HandedStream<String>>(), request.streams)
        assertEquals(listOf(Refusal("_credentials.json", OWN_FILES, null)), request.refused)
        assertEquals("look", request.field)
    }

    @Test
    fun `a scheme other than content is refused`() {
        assertEquals(NOT_SHARED, streamRefusal("file", null, true, isOwn, asWritten = true))
        assertEquals(NOT_SHARED, streamRefusal(null, null, true, isOwn, asWritten = true))
        // content:foo has no authority.
        assertEquals(NOT_SHARED, streamRefusal("content", null, true, isOwn, asWritten = true))
        assertEquals(NOT_SHARED, streamRefusal("content", "", true, isOwn, asWritten = true))
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
        for ((uri, authority) in forms) assertEquals(uri, OWN_FILES, streamRefusal("content", authority, true, isOwn, asWritten = true))
        assertEquals(OWN_FILES, streamRefusal("CONTENT", "dev.harness.files", true, isOwn, asWritten = true))
    }

    @Test
    fun `another app's authority is read`() {
        assertNull(streamRefusal("content", "com.android.providers.media.documents", true, isOwn, asWritten = true))
        assertNull(streamRefusal("content", "10@media", true, isOwn, asWritten = true))
    }

    @Test
    fun `a stream without the sender's grant is refused`() {
        assertEquals(NOT_GRANTED, streamRefusal("content", "media", false, isOwn, asWritten = true))
    }

    @Test
    fun `a uri that reads as another once printed and parsed is refused before anything else is asked`() {
        // Uri.Builder().encodedAuthority("media/external/file/19") prints as content://media/external/file/19.
        assertEquals(NOT_AS_WRITTEN, streamRefusal("content", "media/external/file/19", true, { error("asked") }, asWritten = false))
        assertEquals(NOT_AS_WRITTEN, streamRefusal("content", "dev.harness.files", true, isOwn, asWritten = false))
    }

    @Test
    fun `the uris handed over are the ones returned, clip data first`() {
        val clip = StringBuilder("A")
        val extra = StringBuilder("B")
        val shared = sharedUris(extras = listOf(extra), clip = listOf(clip), flagged = true)
        assertTrue(shared[0].first === clip)
        assertTrue(shared[1].first === extra)
    }

    @Test
    fun `only clip data uris carry the grant, and only with the flag`() {
        assertEquals(listOf("A" to true, "B" to false), sharedUris(extras = listOf("B"), clip = listOf("A"), flagged = true))
        assertEquals(listOf("A" to false), sharedUris(extras = listOf("A"), clip = listOf("A"), flagged = false))
        assertEquals(listOf("A" to true, "C" to true), sharedUris(extras = listOf("A"), clip = listOf("A", "C"), flagged = true))
        assertEquals(listOf("B" to false), sharedUris(extras = listOf("B"), clip = emptyList(), flagged = true))
    }

    @Test
    fun `shared text and subject longer than the limit are cut with the line saying so`() {
        val long = "x".repeat(QUOTE_LIMIT + 5)
        val cut = "x".repeat(QUOTE_LIMIT) + "\n" + "[the shared text was longer; the rest was cut]"
        assertEquals(cut, askRequest(Handed<String>(ACTION_SEND, text = long)).field)
        assertEquals("$cut\nbody", askRequest(Handed<String>(ACTION_SEND, text = "body", subject = long)).field)
        assertEquals(cut, askRequest(Handed<String>(ACTION_SEND, clipText = long)).field)
    }

    @Test
    fun `blank text and subject are absent`() {
        assertTrue(askRequest(Handed<String>(ACTION_SEND, text = "  ", subject = "")).empty)
        assertEquals("body", askRequest(Handed<String>(ACTION_SEND, text = "body", subject = " ")).field)
    }
}

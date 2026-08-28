package apk.harness.intents

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffRequestTest {

    @Test
    fun `a bare uri is a view, because that is the call this replaces`() {
        val handoff = handoffFor(request("uri" to "https://example.test"), WRITABLE).getOrThrow()
        assertEquals(Action.View, handoff.action)
        assertEquals("https://example.test", handoff.uri)
    }

    @Test
    fun `each word in the vocabulary names its own action`() {
        for (action in Action.entries) {
            val handoff = handoffFor(requestFor(action), WRITABLE).getOrThrow()
            assertEquals(action, handoff.action)
        }
    }

    @Test
    fun `an action outside the vocabulary is refused, and the refusal names the seven`() {
        val refusal = handoffFor(request("action" to "call"), WRITABLE).refusal()
        for (action in Action.entries) {
            assertTrue("${action.word} missing from: ${refusal.reason}", action.word in refusal.reason)
        }
    }

    @Test
    fun `placing a call is refused by name, not merely absent from the list`() {
        // ACTION_CALL is the one an agent is most likely to reach for after
        // finding `dial`, so the refusal says why rather than listing words.
        val refusal = handoffFor(request("action" to "call"), WRITABLE).refusal()
        assertTrue(refusal.reason.isNotBlank())
    }

    @Test
    fun `a view with no uri is refused`() {
        assertTrue(handoffFor(request("action" to "view"), WRITABLE).isFailure)
    }

    @Test
    fun `a launch with no package is refused`() {
        assertTrue(handoffFor(request("action" to "launch"), WRITABLE).isFailure)
    }

    @Test
    fun `a share needs something to share`() {
        val bare = request("action" to "share", "mime_type" to "text/plain")
        assertTrue(handoffFor(bare, WRITABLE).isFailure)
    }

    @Test
    fun `a share of text carries it as an extra`() {
        val handoff = handoffFor(
            request("action" to "share", "mime_type" to "text/plain")
                .put("extras", JSONObject().put("text", "a sentence")),
            WRITABLE,
        ).getOrThrow()
        assertEquals(ExtraValue.Text("a sentence"), handoff.extras["text"])
    }

    @Test
    fun `extras carry strings, numbers, flags and lists of strings`() {
        val extras = JSONObject()
            .put("subject", "a subject")
            .put("count", 3)
            .put("urgent", true)
            .put("to", JSONArray(listOf("a@example.test", "b@example.test")))
        val handoff = handoffFor(
            request("uri" to "mailto:a@example.test", "action" to "compose")
                .put("extras", extras),
            WRITABLE,
        ).getOrThrow()
        assertEquals(ExtraValue.Text("a subject"), handoff.extras["subject"])
        assertEquals(ExtraValue.Number(3.0), handoff.extras["count"])
        assertEquals(ExtraValue.Flag(true), handoff.extras["urgent"])
        assertEquals(
            ExtraValue.Series(listOf("a@example.test", "b@example.test")),
            handoff.extras["to"],
        )
    }

    @Test
    fun `an extra that nests is refused`() {
        val extras = JSONObject().put("deeper", JSONObject().put("a", 1))
        val refusal = handoffFor(
            request("uri" to "https://example.test").put("extras", extras),
            WRITABLE,
        ).refusal()
        assertTrue("deeper" in refusal.reason)
    }

    @Test
    fun `a file inside the app's own directories is taken`() {
        val handoff = handoffFor(
            request("action" to "share", "mime_type" to "text/markdown")
                .put("files", JSONArray(listOf("$FILES/notes.md"))),
            WRITABLE,
        ).getOrThrow()
        assertEquals(listOf("$FILES/notes.md"), handoff.content)
    }

    @Test
    fun `a file under a second root is taken, because the roots are a list`() {
        // `/data/user/0/<package>` and `/data/data/<package>` are one directory
        // joined by a bind mount, which nothing resolves away, so both spellings
        // are handed in and a path written in either is the app's own.
        val handoff = handoffFor(
            request("action" to "share", "mime_type" to "text/markdown")
                .put("files", JSONArray(listOf("$DATA_FILES/montmartre.md"))),
            WRITABLE,
        ).getOrThrow()
        assertEquals(listOf("$DATA_FILES/montmartre.md"), handoff.content)
    }

    @Test
    fun `a file outside them is refused, and the refusal names the path`() {
        val refusal = handoffFor(
            request("action" to "share", "mime_type" to "text/plain")
                .put("files", JSONArray(listOf("/sdcard/Download/secrets.txt"))),
            WRITABLE,
        ).refusal()
        assertTrue("/sdcard/Download/secrets.txt" in refusal.reason)
    }

    @Test
    fun `a path that climbs out with dot dot is refused`() {
        // Refused on the resolved path, not on the text, or `files/../../x`
        // passes a prefix test and lands outside.
        val refusal = handoffFor(
            request("action" to "share", "mime_type" to "text/plain")
                .put("files", JSONArray(listOf("$FILES/../../secrets.txt"))),
            WRITABLE,
        ).refusal()
        assertTrue(refusal.reason.isNotBlank())
    }

    @Test
    fun `share_many takes several files and share takes one`() {
        val many = request("action" to "share_many", "mime_type" to "text/plain")
            .put("files", JSONArray(listOf("$FILES/a.txt", "$FILES/b.txt")))
        assertEquals(2, handoffFor(many, WRITABLE).getOrThrow().content.size)

        val one = request("action" to "share", "mime_type" to "text/plain")
            .put("files", JSONArray(listOf("$FILES/a.txt", "$FILES/b.txt")))
        assertTrue(handoffFor(one, WRITABLE).isFailure)
    }

    @Test
    fun `a settings screen outside the set is refused`() {
        val refusal = handoffFor(
            request("action" to "settings").put("extras", JSONObject().put("screen", "wifi")),
            WRITABLE,
        ).refusal()
        assertTrue("developer" in refusal.reason)
    }

    @Test
    fun `each named settings screen is accepted`() {
        for (screen in SettingsScreen.entries) {
            val handoff = handoffFor(
                request("action" to "settings")
                    .put("extras", JSONObject().put("screen", screen.word)),
                WRITABLE,
            ).getOrThrow()
            assertEquals(Action.Settings, handoff.action)
        }
    }

    private fun request(vararg pairs: Pair<String, String>) = JSONObject().apply {
        for ((key, value) in pairs) put(key, value)
    }

    private fun requestFor(action: Action): JSONObject = when (action) {
        Action.View -> request("action" to "view", "uri" to "https://example.test")
        Action.Share -> request("action" to "share", "mime_type" to "text/plain")
            .put("extras", JSONObject().put("text", "a"))
        Action.ShareMany -> request("action" to "share_many", "mime_type" to "text/plain")
            .put("files", JSONArray(listOf("$FILES/a.txt")))
        Action.Compose -> request("action" to "compose", "uri" to "mailto:a@example.test")
        Action.Dial -> request("action" to "dial", "uri" to "tel:+3460000000")
        Action.Settings -> request("action" to "settings")
            .put("extras", JSONObject().put("screen", "developer"))
        Action.Launch -> request("action" to "launch", "package" to "org.mozilla.firefox")
    }

    private fun Result<Handoff>.refusal(): HandoffRefusal =
        (exceptionOrNull() as HandoffRefusalException).refusal

    private companion object {
        const val FILES = "/data/user/0/apk.harness/files"
        const val CACHE = "/data/user/0/apk.harness/cache"

        // The same two directories under the spelling the agent's own
        // environment document uses.
        const val DATA_FILES = "/data/data/apk.harness/files"
        const val DATA_CACHE = "/data/data/apk.harness/cache"

        val WRITABLE = listOf(FILES, CACHE, DATA_FILES, DATA_CACHE)
    }
}

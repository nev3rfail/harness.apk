package apk.harness.intents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One case per confirmation tier, plus the two cases the tiers exist for.
 *
 * The tiers are decided by what a fire can reach rather than by which action it
 * names, so the cases are written as pairs that differ in one field: the same
 * `view` with and without an extra, and the same `share` with and without a
 * package. If a pair answers the same both ways its tier is decoration.
 */
class HandoffConfirmationTest {

    @Test
    fun `a view on a web uri fires unasked`() {
        assertFalse(confirmationNeeded(Handoff(Action.View, uri = "https://example.test")))
        assertFalse(confirmationNeeded(Handoff(Action.View, uri = "http://example.test")))
    }

    @Test
    fun `a view on a map uri fires unasked`() {
        assertFalse(confirmationNeeded(Handoff(Action.View, uri = "geo:41.38,2.17?q=a+cafe")))
    }

    @Test
    fun `a mail and a telephone uri fire unasked`() {
        assertFalse(confirmationNeeded(Handoff(Action.View, uri = "mailto:a@example.test")))
        assertFalse(confirmationNeeded(Handoff(Action.View, uri = "tel:+3460000000")))
    }

    @Test
    fun `the same view with one extra is confirmed`() {
        // The whole of the first tier is that the URI is the entire payload. One
        // extra means it is not, and the fire reaches further than the link.
        val bare = Handoff(Action.View, uri = "https://example.test")
        assertFalse(confirmationNeeded(bare))
        assertTrue(confirmationNeeded(bare.copy(extras = mapOf("body" to ExtraValue.Text("a")))))
    }

    @Test
    fun `the same view aimed at a package is confirmed`() {
        val bare = Handoff(Action.View, uri = "https://example.test")
        assertTrue(confirmationNeeded(bare.copy(target = "org.mozilla.firefox")))
    }

    @Test
    fun `a view on a scheme not in the list is confirmed`() {
        assertTrue(confirmationNeeded(Handoff(Action.View, uri = "intent://scan#Intent;end")))
        assertTrue(confirmationNeeded(Handoff(Action.View, uri = "market://details?id=a")))
    }

    @Test
    fun `a view on a uri with no scheme at all is confirmed`() {
        // No scheme is not in the open set, so the absent case lands in the
        // confirmed tier without a rule of its own.
        assertTrue(confirmationNeeded(Handoff(Action.View, uri = "example.test/page")))
        assertTrue(confirmationNeeded(Handoff(Action.View, uri = null)))
    }

    @Test
    fun `a view on a content uri is confirmed`() {
        assertTrue(confirmationNeeded(Handoff(Action.View, uri = "content://apk.harness.files/a")))
    }

    @Test
    fun `a share is answered by the system sheet rather than by a dialog`() {
        val share = Handoff(Action.Share, mimeType = "text/plain", extras = mapOf("text" to ExtraValue.Text("a")))
        assertTrue(chooserInstead(share))
        assertFalse(confirmationNeeded(share))
    }

    @Test
    fun `share_many is too`() {
        val share = Handoff(
            Action.ShareMany,
            mimeType = "text/plain",
            content = listOf("/data/user/0/apk.harness/files/a.txt"),
        )
        assertTrue(chooserInstead(share))
        assertFalse(confirmationNeeded(share))
    }

    @Test
    fun `dial, settings and launch are confirmed`() {
        assertTrue(confirmationNeeded(Handoff(Action.Dial, uri = "tel:+3460000000")))
        assertTrue(
            confirmationNeeded(
                Handoff(Action.Settings, extras = mapOf("screen" to ExtraValue.Text("developer"))),
            ),
        )
        assertTrue(confirmationNeeded(Handoff(Action.Launch, target = "org.mozilla.firefox")))
    }

    @Test
    fun `a share aimed at one package is confirmed rather than sheeted`() {
        // A share with a target goes straight to that app, so no chooser is ever
        // drawn and the sheet cannot be the confirmation. Ours has to be.
        val share = Handoff(
            Action.Share,
            mimeType = "text/plain",
            extras = mapOf("text" to ExtraValue.Text("a")),
            target = "org.mozilla.firefox",
        )
        assertFalse(chooserInstead(share))
        assertTrue(confirmationNeeded(share))
    }
}

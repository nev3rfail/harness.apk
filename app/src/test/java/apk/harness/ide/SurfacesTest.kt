package apk.harness.ide

import apk.harness.intents.Action
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {

    private fun diff() = Surface.Diff(
        tabName = "tab",
        path = "/a/b.kt",
        oldText = "before",
        newText = "after",
    )

    private fun file() = Surface.Document("/a/c.kt", "```\nx\n```\n", -1)

    private fun handoff() = Surface.Handoff(
        handoff = apk.harness.intents.Handoff(Action.Launch, target = "org.mozilla.firefox"),
        app = "Firefox",
    )

    @Test
    fun `a surface nobody owes an answer to is replaced at once`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file())

        withTimeout(SOON) { surfaces.show(Surface.Document("/a/d.md", "hello", 0)) }

        assertEquals("/a/d.md", (surfaces.visible.value as Surface.Document).path)
    }

    @Test
    fun `showing over a pending diff waits for the person rather than answering`() = runBlocking {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending)

        val second = async(Dispatchers.Default) { surfaces.show(file()) }
        delay(SETTLE)

        // The second agent is waiting, and the first agent's question is still
        // the operator's to answer.
        assertFalse(second.isCompleted)
        assertFalse(pending.decision.isCompleted)
        assertEquals(pending, surfaces.visible.value)

        surfaces.decide(pending, DiffDecision.Accepted)

        withTimeout(SOON) { second.await() }
        assertEquals(DiffDecision.Accepted, pending.decision.await())
        assertTrue(surfaces.visible.value is Surface.Document)
    }

    @Test
    fun `a diff the operator dismisses is rejected`() = runBlocking {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending)

        surfaces.dismiss()

        assertTrue(pending.decision.isCompleted)
        assertEquals(DiffDecision.Rejected, pending.decision.await())
    }

    @Test
    fun `dismissing a diff releases whoever was waiting behind it`() = runBlocking {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending)
        val second = async(Dispatchers.Default) { surfaces.show(file()) }
        delay(SETTLE)

        surfaces.dismiss()

        withTimeout(SOON) { second.await() }
        assertEquals(DiffDecision.Rejected, pending.decision.await())
        assertTrue(surfaces.visible.value is Surface.Document)
    }

    @Test
    fun `an answered diff keeps its answer`() = runBlocking {
        val surfaces = Surfaces()
        val answered = diff()
        surfaces.show(answered)
        surfaces.decide(answered, DiffDecision.Accepted)

        withTimeout(SOON) { surfaces.show(file()) }

        assertEquals(DiffDecision.Accepted, answered.decision.await())
    }

    @Test
    fun `a diff survives anything that does not touch Surfaces`() = runBlocking {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending)

        // The tree is dialog state of its own. Opening and closing it is not a
        // call on Surfaces, which is the whole reason it is not a Surface: the
        // operator browsing a file cannot answer for the agent.
        assertFalse(pending.decision.isCompleted)
        assertEquals(pending, surfaces.visible.value)
    }

    @Test
    fun `a document parks for the chat that showed it`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)

        surfaces.park()

        assertNull(surfaces.visible.value)
        assertEquals("/a/c.kt", surfaces.parked.value[3L]?.document?.path)
    }

    @Test
    fun `parking keeps the selection`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)
        surfaces.select(Selection("/a/c.kt", 4, 7))

        surfaces.park()

        assertEquals(Selection("/a/c.kt", 4, 7), surfaces.parked.value[3L]?.selection)
        // Off screen, so nothing about the screen claims a selection: the parked
        // entry is where it is held.
        assertNull(surfaces.selection.value)
    }

    @Test
    fun `restoring puts the document and its selection back`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)
        surfaces.select(Selection("/a/c.kt", 4, 7))
        surfaces.park()

        surfaces.restore(3L)

        assertEquals(file(), surfaces.visible.value)
        assertEquals(Selection("/a/c.kt", 4, 7), surfaces.selection.value)
        assertEquals(3L, surfaces.owner.value)
        assertTrue(surfaces.parked.value.isEmpty())
    }

    @Test
    fun `a new surface for a chat discards what that chat had parked`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)
        surfaces.select(Selection("/a/c.kt", 4, 7))
        surfaces.park()

        surfaces.show(Surface.Document("/a/d.md", "hello", 0), owner = 3L)

        assertTrue(surfaces.parked.value.isEmpty())
        assertNull(surfaces.selection.value)
    }

    @Test
    fun `a new surface leaves another chat's parked document alone`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)
        surfaces.park()

        surfaces.show(Surface.Document("/a/d.md", "hello", 0), owner = 4L)

        assertEquals("/a/c.kt", surfaces.parked.value[3L]?.document?.path)
    }

    @Test
    fun `a diff cannot be parked`() = runBlocking {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending, owner = 3L)

        surfaces.park()

        assertEquals(pending, surfaces.visible.value)
        assertTrue(surfaces.parked.value.isEmpty())
        assertFalse(pending.decision.isCompleted)
    }

    @Test
    fun `showing over a pending handoff waits for the person`() = runBlocking {
        val surfaces = Surfaces()
        val pending = handoff()
        surfaces.show(pending)

        val second = async(Dispatchers.Default) { surfaces.show(file()) }
        delay(SETTLE)

        // A confirmation is a question with a tool call behind it, exactly as a
        // diff is, so it holds the screen for the same reason.
        assertFalse(second.isCompleted)
        assertFalse(pending.decision.isCompleted)
        assertEquals(pending, surfaces.visible.value)

        surfaces.answer(pending, true)

        withTimeout(SOON) { second.await() }
        assertTrue(pending.decision.await())
        assertTrue(surfaces.visible.value is Surface.Document)
    }

    @Test
    fun `a handoff the operator dismisses is declined`() = runBlocking {
        val surfaces = Surfaces()
        val pending = handoff()
        surfaces.show(pending)

        surfaces.dismiss()

        assertTrue(pending.decision.isCompleted)
        assertFalse(pending.decision.await())
    }

    @Test
    fun `an answered handoff keeps its answer`() = runBlocking {
        val surfaces = Surfaces()
        val answered = handoff()
        surfaces.show(answered)
        surfaces.answer(answered, true)

        withTimeout(SOON) { surfaces.show(file()) }

        assertTrue(answered.decision.await())
    }

    @Test
    fun `a handoff cannot be parked`() = runBlocking {
        val surfaces = Surfaces()
        val pending = handoff()
        surfaces.show(pending, owner = 3L)

        surfaces.park()

        assertEquals(pending, surfaces.visible.value)
        assertTrue(surfaces.parked.value.isEmpty())
        assertFalse(pending.decision.isCompleted)
    }

    @Test
    fun `a document no chat owns does not park`() = runBlocking {
        val surfaces = Surfaces()
        // What a session started outside the app shows: it reaches the tools
        // through the app-wide token, which names no chat.
        surfaces.show(file())

        surfaces.park()

        // No band is drawn for a chat that does not exist, so parking it would
        // be losing it. It stays on screen instead.
        assertEquals(file(), surfaces.visible.value)
        assertTrue(surfaces.parked.value.isEmpty())
    }

    @Test
    fun `switching parks what is showing for its own chat`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)

        surfaces.switchTo(4L)

        assertNull(surfaces.visible.value)
        assertEquals(setOf(3L), surfaces.parked.value.keys)
    }

    @Test
    fun `switching to a chat with nothing parked leaves the screen empty`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)

        surfaces.switchTo(4L)

        // A band is drawn for a parked document; nothing comes back on screen
        // by itself, and 4 has nothing to draw.
        assertNull(surfaces.visible.value)
        assertNull(surfaces.parked.value[4L])
    }

    @Test
    fun `a selection is dropped when the surface it belongs to goes`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 3L)
        surfaces.select(Selection("/a/c.kt", 4, 7))

        surfaces.dismiss()

        assertNull(surfaces.selection.value)
    }

    @Test
    fun `the owner of what is on screen is the chat that showed it`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file(), owner = 7L)
        assertEquals(7L, surfaces.owner.value)

        surfaces.show(Surface.Document("/a/d.md", "hello", 0))

        assertEquals(NO_CHAT, surfaces.owner.value)
    }

    private companion object {
        /** Long enough for a coroutine on another thread to reach its wait. */
        const val SETTLE = 100L

        /** Short enough that a call which should not wait fails rather than hangs. */
        const val SOON = 2000L
    }
}

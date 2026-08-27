package apk.harness.ide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {

    private fun diff() = Surface.Diff(
        tabName = "tab",
        path = "/a/b.kt",
        oldText = "before",
        newText = "after",
    )

    private fun file() = Surface.FileView("/a/c.kt", "```\nx\n```\n")

    @Test
    fun `a surface nobody owes an answer to is replaced at once`() = runBlocking {
        val surfaces = Surfaces()
        surfaces.show(file())

        withTimeout(SOON) { surfaces.show(Surface.Markdown("note", "hello")) }

        assertTrue(surfaces.visible.value is Surface.Markdown)
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
        assertTrue(surfaces.visible.value is Surface.FileView)
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
        assertTrue(surfaces.visible.value is Surface.FileView)
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

    private companion object {
        /** Long enough for a coroutine on another thread to reach its wait. */
        const val SETTLE = 100L

        /** Short enough that a call which should not wait fails rather than hangs. */
        const val SOON = 2000L
    }
}

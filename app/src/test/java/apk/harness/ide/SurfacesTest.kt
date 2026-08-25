package apk.harness.ide

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `showing another surface rejects a diff nobody answered`() {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending)

        surfaces.show(Surface.FileView("/a/c.kt", "```\nx\n```\n"))

        assertTrue(pending.decision.isCompleted)
        assertEquals(DiffDecision.Rejected, runBlocking { pending.decision.await() })
    }

    @Test
    fun `an answered diff keeps its answer`() {
        val surfaces = Surfaces()
        val answered = diff()
        surfaces.show(answered)
        surfaces.decide(answered, DiffDecision.Accepted)

        surfaces.show(Surface.FileView("/a/c.kt", "```\nx\n```\n"))

        assertEquals(DiffDecision.Accepted, runBlocking { answered.decision.await() })
    }

    @Test
    fun `a diff survives anything that does not touch Surfaces`() {
        val surfaces = Surfaces()
        val pending = diff()
        surfaces.show(pending)

        // The tree is dialog state of its own. Opening and closing it is not a
        // call on Surfaces, which is the whole reason it is not a Surface: the
        // operator browsing a file cannot answer for the agent.
        assertFalse(pending.decision.isCompleted)
        assertEquals(pending, surfaces.visible.value)
    }
}

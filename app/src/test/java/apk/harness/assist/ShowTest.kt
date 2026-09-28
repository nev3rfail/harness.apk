package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowTest {

    @Test
    fun `any show on a locked phone is locked`() {
        for (shown in listOf(false, true)) for (own in listOf(false, true)) for (staged in listOf(false, true)) {
            assertEquals(Show.Locked, classifyShow(locked = true, shown = shown, ownRequest = own, staged = staged))
        }
    }

    @Test
    fun `a summon before the agent is set up says so`() {
        assertEquals(Show.NotStaged, classifyShow(locked = false, shown = false, ownRequest = false, staged = false))
    }

    @Test
    fun `a summon on a hidden panel opens a fresh one`() {
        assertEquals(Show.Fresh, classifyShow(locked = false, shown = false, ownRequest = false, staged = true))
    }

    @Test
    fun `a summon on a shown panel keeps the conversation, twice in a row too`() {
        repeat(2) { assertEquals(Show.Listen, classifyShow(locked = false, shown = true, ownRequest = false, staged = true)) }
    }

    @Test
    fun `the panel's own re-show takes what it asked for`() {
        assertEquals(Show.OwnReShow, classifyShow(locked = false, shown = true, ownRequest = true, staged = true))
        assertEquals(Show.OwnReShow, classifyShow(locked = false, shown = false, ownRequest = true, staged = true))
    }
}

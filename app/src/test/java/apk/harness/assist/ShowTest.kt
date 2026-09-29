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
    fun `a summon over a locked or unstaged panel still up opens a fresh one`() {
        for (gate in listOf(PanelState.Gate.Locked, PanelState.Gate.NotStaged, null)) {
            val shown = panelShown(visible = true, gate = gate)
            assertEquals(Show.Fresh, classifyShow(locked = false, shown = shown, ownRequest = false, staged = true))
        }
        assertEquals(Show.Listen, classifyShow(locked = false, shown = panelShown(true, PanelState.Gate.Open), ownRequest = false, staged = true))
        assertEquals(Show.Fresh, classifyShow(locked = false, shown = panelShown(false, PanelState.Gate.Open), ownRequest = false, staged = true))
    }

    @Test
    fun `the panel's own re-show takes what it asked for`() {
        assertEquals(Show.OwnReShow, classifyShow(locked = false, shown = true, ownRequest = true, staged = true))
        assertEquals(Show.OwnReShow, classifyShow(locked = false, shown = false, ownRequest = true, staged = true))
    }

    @Test
    fun `a summon listens only when the system made it, on Android 15 and later`() {
        assertEquals(true, summonListens(sdk = 35, callerUid = 1000, callerIsSystemUi = false))
        assertEquals(true, summonListens(sdk = 36, callerUid = 10_050, callerIsSystemUi = true))
        // The shell, and any other app.
        assertEquals(false, summonListens(sdk = 35, callerUid = 2000, callerIsSystemUi = false))
        assertEquals(false, summonListens(sdk = 35, callerUid = 10_123, callerIsSystemUi = false))
        assertEquals(false, summonListens(sdk = 35, callerUid = null, callerIsSystemUi = false))
    }

    @Test
    fun `no summon listens on its own on Android 11 to 14`() {
        for (sdk in 30..34) {
            assertEquals(false, summonListens(sdk, callerUid = 1000, callerIsSystemUi = true))
        }
    }
}

package apk.harness.assist

import apk.harness.assist.StopAction.*
import org.junit.Assert.assertEquals
import org.junit.Test

class StopTest {

    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    /** Runs [events] from [state], answering every action taken on the way. */
    private fun run(state: StopState, vararg events: StopEvent): List<StopAction> {
        var current = state
        return events.flatMap { event -> escalation(current, event).also { current = it.first }.second }
    }

    private fun escalate(inFlight: Boolean = false, alive: Boolean = true, sessionId: String? = id) =
        StopState(StopKind.Escalate, alive = alive, turnInFlight = inFlight, sessionId = sessionId)

    private val dismiss = StopState(StopKind.Dismiss, alive = true, turnInFlight = true, sessionId = id)

    @Test
    fun `a dismiss closes stdin and sends SIGTERM at once, and SIGKILL at 2 s`() {
        assertEquals(listOf(CloseStdin, Terminate, After(2_000, 1)), run(dismiss, StopEvent.Begin))
        assertEquals(listOf(CloseStdin, Terminate, After(2_000, 1), Kill), run(dismiss, StopEvent.Begin, StopEvent.Timer(1)))
    }

    @Test
    fun `a dismissed process that exits is not killed`() {
        assertEquals(listOf(CloseStdin, Terminate, After(2_000, 1)), run(dismiss, StopEvent.Begin, StopEvent.Exited, StopEvent.Timer(1)))
    }

    @Test
    fun `an idle escalation closes stdin, waits for the roster once the process exits, then opens the tab`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Settle(id), Open(id), Dismiss),
            run(escalate(), StopEvent.Begin, StopEvent.Exited, StopEvent.Settled),
        )
    }

    @Test
    fun `a turn in flight is interrupted and given 4 s for its result`() {
        assertEquals(
            listOf(Interrupt, After(4_000, 1), CloseStdin, After(3_000, 2), Settle(id), Open(id), Dismiss),
            run(escalate(inFlight = true), StopEvent.Begin, StopEvent.Result, StopEvent.Exited, StopEvent.Settled),
        )
    }

    @Test
    fun `an interrupt with no result closes stdin at 4 s`() {
        assertEquals(listOf(Interrupt, After(4_000, 1), CloseStdin, After(3_000, 2)), run(escalate(inFlight = true), StopEvent.Begin, StopEvent.Timer(1)))
    }

    @Test
    fun `a process that ignores EOF is sent SIGTERM at 3 s`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Terminate, After(2_000, 2), Settle(id)),
            run(escalate(), StopEvent.Begin, StopEvent.Timer(1), StopEvent.Exited),
        )
    }

    @Test
    fun `a process that ignores SIGTERM is killed at 2 s and the roster waited for`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Terminate, After(2_000, 2), Kill, Settle(id)),
            run(escalate(), StopEvent.Begin, StopEvent.Timer(1), StopEvent.Timer(2)),
        )
    }

    @Test
    fun `a process already dead goes straight to the roster wait`() {
        assertEquals(listOf(Settle(id), Open(id), Dismiss), run(escalate(alive = false), StopEvent.Begin, StopEvent.Settled))
    }

    @Test
    fun `a panel that sent nothing opens the tab with no session`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Settle(null), Open(null), Dismiss),
            run(escalate(sessionId = null), StopEvent.Begin, StopEvent.Exited, StopEvent.Settled),
        )
    }

    @Test
    fun `back during the stop is ignored`() {
        val (state, actions) = escalation(escalate(), StopEvent.Begin)
        assertEquals(state to emptyList<StopAction>(), escalation(state, StopEvent.Back))
        assertEquals(listOf(CloseStdin, After(3_000, 1)), actions)
    }

    @Test
    fun `a hide while stdin drains sends SIGTERM, not a second close, and opens nothing`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Terminate, After(2_000, 2), Kill),
            run(escalate(), StopEvent.Begin, StopEvent.Hidden, StopEvent.Timer(1), StopEvent.Timer(2)),
        )
    }

    @Test
    fun `a hide during the interrupt closes stdin and sends SIGTERM`() {
        assertEquals(
            listOf(Interrupt, After(4_000, 1), CloseStdin, Terminate, After(2_000, 2), Kill),
            run(escalate(inFlight = true), StopEvent.Begin, StopEvent.Hidden, StopEvent.Timer(1), StopEvent.Timer(2)),
        )
    }

    @Test
    fun `a hide after SIGTERM keeps its timer and opens nothing`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Terminate, After(2_000, 2), Kill),
            run(escalate(), StopEvent.Begin, StopEvent.Timer(1), StopEvent.Hidden, StopEvent.Timer(2)),
        )
    }

    @Test
    fun `a hide before the stop begins makes it a dismiss`() {
        assertEquals(
            listOf(CloseStdin, Terminate, After(2_000, 1)),
            run(escalate(), StopEvent.Hidden, StopEvent.Begin),
        )
    }

    @Test
    fun `an exit during the interrupt goes to the roster wait`() {
        assertEquals(
            listOf(Interrupt, After(4_000, 1), Settle(id), Open(id), Dismiss),
            run(escalate(inFlight = true), StopEvent.Begin, StopEvent.Exited, StopEvent.Settled),
        )
    }

    @Test
    fun `a second begin does nothing`() {
        assertEquals(listOf(CloseStdin, After(3_000, 1)), run(escalate(), StopEvent.Begin, StopEvent.Begin))
    }

    @Test
    fun `a hide while the roster is waited for opens nothing`() {
        assertEquals(
            listOf(CloseStdin, After(3_000, 1), Settle(id)),
            run(escalate(), StopEvent.Begin, StopEvent.Exited, StopEvent.Hidden, StopEvent.Settled),
        )
    }

    @Test
    fun `a timer from an earlier phase does nothing`() {
        assertEquals(
            listOf(Interrupt, After(4_000, 1), CloseStdin, After(3_000, 2)),
            run(escalate(inFlight = true), StopEvent.Begin, StopEvent.Result, StopEvent.Timer(1)),
        )
    }
}

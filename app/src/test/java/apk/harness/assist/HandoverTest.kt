package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverTest {

    private fun run(vararg events: HandoverEvent): List<HandoverOutcome> {
        val begun = beginHandover()
        var state: Handover? = begun.first
        val outcomes = mutableListOf(begun.second)
        for (event in events) {
            val current = state ?: break
            val (next, outcome) = handover(current, event)
            state = next
            outcomes += outcome
        }
        return outcomes
    }

    @Test
    fun `a mic handover waits for the activity, then 2 s for the re-show`() {
        assertEquals(
            listOf(HandoverOutcome.After(2_000, 1), HandoverOutcome.Wait, HandoverOutcome.After(2_000, 2), HandoverOutcome.Keep),
            run(HandoverEvent.MicStarted, HandoverEvent.MicFinished, HandoverEvent.ReShown),
        )
    }

    @Test
    fun `a mic handover whose activity started outlives the first wait`() {
        assertEquals(
            listOf(HandoverOutcome.After(2_000, 1), HandoverOutcome.Wait, HandoverOutcome.Wait, HandoverOutcome.After(2_000, 2), HandoverOutcome.Keep),
            run(HandoverEvent.MicStarted, HandoverEvent.Timer(1), HandoverEvent.MicFinished, HandoverEvent.ReShown),
        )
    }

    @Test
    fun `a mic handover whose activity never started is a dismiss after 2 s`() {
        assertEquals(listOf(HandoverOutcome.After(2_000, 1), HandoverOutcome.Dismiss), run(HandoverEvent.Timer(1)))
    }

    @Test
    fun `a mic handover with no re-show after the activity finishes is a dismiss`() {
        assertEquals(
            listOf(HandoverOutcome.After(2_000, 1), HandoverOutcome.Wait, HandoverOutcome.After(2_000, 2), HandoverOutcome.Wait, HandoverOutcome.Dismiss),
            run(HandoverEvent.MicStarted, HandoverEvent.MicFinished, HandoverEvent.Timer(1), HandoverEvent.Timer(2)),
        )
    }

    @Test
    fun `the mic activity stopped with no answer is a dismiss`() {
        assertEquals(
            listOf(HandoverOutcome.After(2_000, 1), HandoverOutcome.Wait, HandoverOutcome.Dismiss),
            run(HandoverEvent.MicStarted, HandoverEvent.MicStopped),
        )
    }

    @Test
    fun `a re-show that fails is a dismiss`() {
        assertEquals(
            listOf(HandoverOutcome.After(2_000, 1), HandoverOutcome.Wait, HandoverOutcome.After(2_000, 2), HandoverOutcome.Dismiss),
            run(HandoverEvent.MicStarted, HandoverEvent.MicFinished, HandoverEvent.ShowFailed),
        )
    }

    @Test
    fun `a fresh summon during a handover ends it as a dismiss`() {
        assertEquals(HandoverOutcome.Dismiss, run(HandoverEvent.Superseded).last())
        assertEquals(
            HandoverOutcome.Dismiss,
            run(HandoverEvent.MicStarted, HandoverEvent.MicFinished, HandoverEvent.Superseded).last(),
        )
    }

    @Test
    fun `the context flags reset once every callback asked for has arrived`() {
        val asked = ContextRequest(text = true, shot = true)
        val (afterStructure, first) = deliver(asked, Delivery.Structure)
        assertEquals(ContextOutcome(take = true, close = false), first)
        val (closed, second) = deliver(afterStructure, Delivery.Screenshot)
        assertEquals(ContextOutcome(take = true, close = true), second)
        assertEquals(ContextOutcome(take = false, close = false), deliver(closed, Delivery.Structure).second)
    }

    @Test
    fun `a screenshot alone drops the structure it comes with`() {
        val (next, outcome) = deliver(ContextRequest(text = false, shot = true), Delivery.Structure)
        assertEquals(ContextOutcome(take = false, close = false), outcome)
        assertEquals(ContextOutcome(take = true, close = true), deliver(next, Delivery.Screenshot).second)
    }

    @Test
    fun `at 2 s the flags reset and a late callback is dropped`() {
        val (closed, outcome) = timeOut(ContextRequest(text = true, shot = true))
        assertEquals(ContextOutcome(take = false, close = true), outcome)
        assertEquals(ContextOutcome(take = false, close = false), deliver(closed, Delivery.Screenshot).second)
        assertEquals(ContextOutcome(take = false, close = false), timeOut(closed).second)
    }

    @Test
    fun `a delivery with no request of the panel's own is dropped, a full one too`() {
        for (delivery in Delivery.entries) {
            val (next, outcome) = deliver(null, delivery)
            assertNull(next)
            assertEquals(ContextOutcome(take = false, close = false), outcome)
        }
        assertEquals(ContextOutcome(take = false, close = false), timeOut(null).second)
    }

    @Test
    fun `a parked delivery applies only to the show of its own summon within the context wait`() {
        assertEquals(true, parkedApplies(parkedIn = 3, generation = 3, parkedAt = 1_000, now = 2_999))
        assertEquals(false, parkedApplies(parkedIn = 3, generation = 3, parkedAt = 1_000, now = 3_000))
        assertEquals(false, parkedApplies(parkedIn = 2, generation = 3, parkedAt = 1_000, now = 1_001))
    }
}

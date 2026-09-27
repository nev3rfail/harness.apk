package apk.harness.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {

    private val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val c = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun agent(pid: Int, id: String, start: Long = 100, tag: String? = "t") =
        RunningSession(pid = pid, sessionId = id, status = "idle", procStart = start, tag = tag)

    @Test
    fun `the oldest process carrying the tag is the tab's agent`() {
        val outer = agent(7, a, start = 100)
        val nested = agent(9, b, start = 200)
        val other = agent(5, c, start = 50, tag = "u")

        assertEquals(outer, agentOf("t", listOf(nested, other, outer)))
    }

    @Test
    fun `a tag nothing carries has no agent`() {
        assertNull(agentOf("t", listOf(agent(5, c, tag = "u"), agent(6, c, tag = null))))
    }

    @Test
    fun `a first sighting on another id moves the tab`() {
        val out = followDecision(FollowState(a), agent(7, b), now = 0)

        assertEquals(b, out.state.sessionId)
        assertTrue(out.moved)
        assertTrue(out.confirmed)
    }

    @Test
    fun `a first sighting on the tab's own id moves nothing`() {
        val out = followDecision(FollowState(a), agent(7, a), now = 0)

        assertEquals(a, out.state.sessionId)
        assertFalse(out.moved)
        assertEquals(7, out.state.lastPid)
    }

    @Test
    fun `the same record again moves nothing`() {
        val state = FollowState(a, lastPid = 7, lastRecordId = a)

        val out = followDecision(state, agent(7, a), now = 0)

        assertFalse(out.moved)
        assertFalse(out.confirmed)
    }

    @Test
    fun `a record that changes its id moves the tab after new`() {
        val state = FollowState(a, lastPid = 7, lastRecordId = a)

        val out = followDecision(state, agent(7, b), now = 0)

        assertEquals(b, out.state.sessionId)
        assertTrue(out.moved)
    }

    @Test
    fun `a nested agent taking over moves the tab though its record did not change`() {
        // The nested agent was seen under its own id while the outer one was the
        // tab's; the outer one exits and the nested one is now the tab's agent.
        val state = FollowState(a, lastPid = 7, lastRecordId = a)

        val out = followDecision(state, agent(9, b, start = 200), now = 0)

        assertEquals(b, out.state.sessionId)
        assertTrue(out.moved)
        assertEquals(9, out.state.lastPid)
    }

    @Test
    fun `a status rewrite on the old id does not undo a switch the app made`() {
        val state = FollowState(b, lastPid = 7, lastRecordId = a, provisional = b, provisionalAt = 1_000)

        val out = followDecision(state, agent(7, a), now = 2_000)

        assertEquals(b, out.state.sessionId)
        assertFalse(out.moved)
        assertEquals(b, out.state.provisional)
    }

    @Test
    fun `the record carrying the switched id confirms it`() {
        val state = FollowState(b, lastPid = 7, lastRecordId = a, provisional = b, provisionalAt = 1_000)

        val out = followDecision(state, agent(7, b), now = 2_000)

        assertEquals(b, out.state.sessionId)
        assertNull(out.state.provisional)
        assertTrue(out.confirmed)
        assertFalse(out.moved)
    }

    @Test
    fun `a switch the record never confirms gives way to the record`() {
        val state = FollowState(b, lastPid = 7, lastRecordId = a, provisional = b, provisionalAt = 1_000)

        val out = followDecision(state, agent(7, a), now = 1_000 + PROVISIONAL_MS)

        assertEquals(a, out.state.sessionId)
        assertNull(out.state.provisional)
        assertTrue(out.moved)
    }

    @Test
    fun `a tab with no agent keeps its id`() {
        val state = FollowState(a, lastPid = 7, lastRecordId = a)

        val out = followDecision(state, null, now = 0)

        assertEquals(a, out.state.sessionId)
        assertFalse(out.moved)
    }

    @Test
    fun `a switch with no agent to confirm it is kept and stops waiting`() {
        val state = FollowState(b, provisional = b, provisionalAt = 0)

        val out = followDecision(state, null, now = PROVISIONAL_MS)

        assertEquals(b, out.state.sessionId)
        assertNull(out.state.provisional)
    }
}

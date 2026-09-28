package apk.harness.assist

import apk.harness.agents.Denial
import apk.harness.agents.HeadlessEvent
import apk.harness.agents.HeadlessEvent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTest {

    private fun fold(vararg events: ThreadEvent, from: Conversation = Conversation()) =
        events.fold(from, ::thread)

    private fun agent(event: HeadlessEvent) = ThreadEvent.Agent(event)
    private val sent = ThreadEvent.Sent("q", emptyList())
    private val ok = Finished(ok = true, text = "done", denials = emptyList(), errors = emptyList(), signedOut = false)

    @Test
    fun `the panel says starting only after a send, until the next line of any kind`() {
        assertEquals(Status.Ready, Conversation().status)
        assertEquals(Status.Ready, fold(agent(Ignored)).status)
        assertEquals(Status.Starting, fold(sent).status)
        assertEquals(Status.Ready, fold(sent, agent(Ignored)).status)
        assertEquals(Status.Ready, fold(sent, agent(Malformed)).status)
    }

    @Test
    fun `deltas append to the block of their index`() {
        val turn = fold(sent, agent(MessageStart), agent(TextDelta(0, "a")), agent(TextDelta(1, "x")), agent(TextDelta(0, "b"))).turns.single()
        assertEquals(listOf(Item.Text(0, 0, "ab"), Item.Text(0, 1, "x")), turn.items)
    }

    @Test
    fun `a second message in one turn opens a second segment`() {
        val turn = fold(
            sent, agent(MessageStart), agent(TextDelta(0, "a")),
            agent(ToolUse("Bash", "ls")),
            agent(MessageStart), agent(TextDelta(0, "b")),
        ).turns.single()
        assertEquals(listOf(Item.Text(0, 0, "a"), Item.Tool("Bash", "ls"), Item.Text(1, 0, "b")), turn.items)
    }

    @Test
    fun `a turn with no deltas takes the result's text`() {
        val turn = fold(sent, agent(ok)).turns.single()
        assertEquals(listOf(Item.Text(0, 0, "done")), turn.items)
        assertTrue(turn.done)
    }

    @Test
    fun `denials are merged by tool use id`() {
        val finished = ok.copy(denials = listOf(Denial("t1", "Bash"), Denial("t2", "Write")))
        val turn = fold(sent, agent(Denied("t1", "Bash", "needs approval")), agent(finished)).turns.single()
        assertEquals(listOf(Item.Denied("t1", "Bash"), Item.Denied("t2", "Write")), turn.items.filterIsInstance<Item.Denied>())
    }

    @Test
    fun `an error result then an exit shows the errors, not stopped`() {
        val error = Finished(ok = false, text = null, denials = emptyList(), errors = listOf("e1", "e2"), signedOut = false)
        assertEquals(Status.Errors(listOf("e1", "e2")), fold(sent, agent(error), ThreadEvent.Exited(1, listOf("x"))).status)
    }

    @Test
    fun `an exit with no error result is a stop with its stderr`() {
        val conversation = fold(sent, agent(MessageStart), ThreadEvent.Exited(137, listOf("killed")))
        assertEquals(Status.Stopped(137, listOf("killed")), conversation.status)
        assertTrue(conversation.turns.single().done)
    }

    @Test
    fun `a signed out result is its own state`() {
        val out = Finished(ok = false, text = "Not logged in", denials = emptyList(), errors = listOf("Not logged in"), signedOut = true)
        assertEquals(Status.SignedOut, fold(sent, agent(out)).status)
    }

    @Test
    fun `a send after a stop starts again`() {
        val stopped = fold(sent, ThreadEvent.Exited(1, emptyList()))
        assertEquals(Status.Starting, thread(stopped, sent).status)
    }

    @Test
    fun `retrying and thinking are shown on the open turn and cleared by text`() {
        val open = fold(sent, agent(Thinking), agent(Retrying(2))).turns.single()
        assertTrue(open.thinking)
        assertEquals(2, open.retrying)
        val speaking = thread(fold(sent, agent(Thinking), agent(Retrying(2))), agent(TextDelta(0, "a"))).turns.single()
        assertEquals(false, speaking.thinking)
        assertEquals(null, speaking.retrying)
    }

    @Test
    fun `output outside a turn makes no turn and does not reopen one`() {
        assertEquals(emptyList<Turn>(), fold(agent(Started("s")), agent(TextDelta(0, "stray"))).turns)
        val closed = fold(sent, agent(ok), agent(TextDelta(0, "late")))
        assertEquals(listOf(Item.Text(0, 0, "done")), closed.turns.single().items)
    }
}

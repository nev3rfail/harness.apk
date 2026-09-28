package apk.harness.assist

import apk.harness.agents.HeadlessEvent

/** One exchange: the operator's message, its attachment lines, and the answer. */
data class Turn(
    val message: String,
    val attachments: List<String> = emptyList(),
    val items: List<Item> = emptyList(),
    val segment: Int = -1,
    val thinking: Boolean = false,
    val retrying: Int? = null,
    val done: Boolean = false,
)

/** What an answer is drawn as, in order. */
sealed interface Item {
    data class Text(val segment: Int, val index: Int, val text: String) : Item
    data class Tool(val name: String, val input: String) : Item
    data class Denied(val toolUseId: String, val tool: String) : Item
}

/** What the panel says about the run, beside the thread. */
sealed interface Status {
    /** A message was sent and the agent has written nothing since. */
    data object Starting : Status
    data object Ready : Status
    data object SignedOut : Status
    data class Errors(val errors: List<String>) : Status
    data class Stopped(val code: Int, val stderr: List<String>) : Status
}

data class Conversation(val turns: List<Turn> = emptyList(), val status: Status = Status.Ready) {
    /** A turn is waiting for its result; the next message waits in the field. */
    val inFlight: Boolean get() = turns.lastOrNull()?.done == false
}

sealed interface ThreadEvent {
    data class Sent(val message: String, val attachments: List<String>) : ThreadEvent
    data class Agent(val event: HeadlessEvent) : ThreadEvent
    /** The process exited outside a dismiss or an escalation. */
    data class Exited(val code: Int, val stderr: List<String>) : ThreadEvent
}

/** [conversation] with [event] folded in. */
fun thread(conversation: Conversation, event: ThreadEvent): Conversation = when (event) {
    is ThreadEvent.Sent -> conversation.copy(
        turns = conversation.turns + Turn(event.message, event.attachments),
        // Until the agent's next line: a send before it is up waits on stdin,
        // and a send after an error or a stop starts a new process.
        status = Status.Starting,
    )
    is ThreadEvent.Exited -> conversation.copy(
        turns = conversation.closeLast { it.copy(done = true, thinking = false, retrying = null) },
        status = when (conversation.status) {
            is Status.Errors, Status.SignedOut -> conversation.status
            else -> Status.Stopped(event.code, event.stderr)
        },
    )
    is ThreadEvent.Agent -> agent(conversation.copy(status = conversation.status.takeUnless { it == Status.Starting } ?: Status.Ready), event.event)
}

private fun agent(conversation: Conversation, event: HeadlessEvent): Conversation = when (event) {
    is HeadlessEvent.Finished -> conversation.copy(
        turns = conversation.closeLast { finish(it, event) },
        status = when {
            event.signedOut -> Status.SignedOut
            !event.ok -> Status.Errors(event.errors)
            else -> Status.Ready
        },
    )
    else -> conversation.copy(turns = conversation.closeLast { step(it, event) })
}

/** The turns with [change] applied to the last one while it is open. */
private fun Conversation.closeLast(change: (Turn) -> Turn): List<Turn> {
    val last = turns.lastOrNull()?.takeUnless { it.done } ?: return turns
    return turns.dropLast(1) + change(last)
}

private fun step(turn: Turn, event: HeadlessEvent): Turn = when (event) {
    HeadlessEvent.MessageStart -> turn.copy(segment = turn.segment + 1)
    is HeadlessEvent.TextDelta -> {
        val segment = maxOf(turn.segment, 0)
        val at = turn.items.indexOfFirst { it is Item.Text && it.segment == segment && it.index == event.index }
        val items = if (at < 0) turn.items + Item.Text(segment, event.index, event.text)
        else turn.items.toMutableList().apply { this[at] = (this[at] as Item.Text).let { it.copy(text = it.text + event.text) } }
        turn.copy(items = items, segment = segment, thinking = false, retrying = null)
    }
    HeadlessEvent.Thinking -> turn.copy(thinking = true)
    is HeadlessEvent.ToolUse -> turn.copy(items = turn.items + Item.Tool(event.name, event.input), thinking = false)
    is HeadlessEvent.Denied -> turn.withDenial(event.toolUseId, event.tool)
    is HeadlessEvent.Retrying -> turn.copy(retrying = event.attempt)
    else -> turn
}

private fun finish(turn: Turn, event: HeadlessEvent.Finished): Turn {
    val spoken = turn.items.any { it is Item.Text }
    val withText = if (!spoken && event.ok && !event.text.isNullOrEmpty()) {
        turn.copy(items = turn.items + Item.Text(maxOf(turn.segment, 0), 0, event.text))
    } else turn
    return event.denials.fold(withText) { t, denial -> t.withDenial(denial.toolUseId, denial.tool) }
        .copy(done = true, thinking = false, retrying = null)
}

private fun Turn.withDenial(id: String, tool: String): Turn =
    if (items.any { it is Item.Denied && it.toolUseId == id }) this
    else copy(items = items + Item.Denied(id, tool))

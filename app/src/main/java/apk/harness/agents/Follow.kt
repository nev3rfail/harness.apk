package apk.harness.agents

/**
 * The agent of the tab [tag] names: the oldest live record carrying the tag.
 *
 * An agent started from inside a tab -- through the agent's own shell tool, or
 * in the shell the tab drops to -- inherits the tag too. The outer agent started
 * first, so it is the one on screen, and the order is the kernel's start time
 * rather than anything a record claims about itself.
 */
fun agentOf(tag: String, roster: List<RunningSession>): RunningSession? =
    roster.filter { it.tag == tag }.minByOrNull { it.procStart ?: Long.MAX_VALUE }

/**
 * What a tab knows about following its agent.
 *
 * [lastPid] and [lastRecordId] are the agent it followed and the id that
 * agent's record carried, so a record is acted on only when it changes.
 * [provisional] is an id the app switched the tab to itself, at [provisionalAt],
 * which the record has not confirmed yet.
 */
data class FollowState(
    val sessionId: String,
    val lastPid: Int? = null,
    val lastRecordId: String? = null,
    val provisional: String? = null,
    val provisionalAt: Long? = null,
)

/**
 * A follow's result: the [state] to keep, whether the tab is now on a
 * conversation it was not on ([moved]), and whether the conversation it is on
 * now has its agent's confirmation ([confirmed]) -- which is when a parked
 * record of the same conversation stops being needed.
 */
data class FollowOutcome(val state: FollowState, val moved: Boolean, val confirmed: Boolean)

/**
 * How long a switch the app made waits for the agent's record to agree before
 * the record wins.
 */
const val PROVISIONAL_MS = 5_000L

/**
 * The follow decision for one tab, given its [agent] as the roster has it now.
 *
 * A tab moves when its agent is a different process from the one it followed,
 * or when the same process's record carries an id it did not carry last time. A
 * record that has not changed moves nothing, so a status rewrite still carrying
 * an old id cannot undo a switch the app made. That switch is provisional: the
 * record confirming it settles it, and a record that has not within
 * [PROVISIONAL_MS] of [now] is what the tab follows instead.
 *
 * A tab with no agent keeps its id.
 */
fun followDecision(state: FollowState, agent: RunningSession?, now: Long): FollowOutcome {
    if (agent == null) {
        val expired = state.provisionalAt?.let { now - it >= PROVISIONAL_MS } == true
        val kept = if (expired) state.copy(provisional = null, provisionalAt = null) else state
        return FollowOutcome(kept, moved = false, confirmed = false)
    }

    val recordId = agent.sessionId
    val seen = state.copy(lastPid = agent.pid, lastRecordId = recordId)

    state.provisional?.let { provisional ->
        if (recordId == provisional) {
            val settled = seen.copy(sessionId = provisional, provisional = null, provisionalAt = null)
            return FollowOutcome(settled, moved = false, confirmed = true)
        }
        val waited = now - (state.provisionalAt ?: now)
        if (waited < PROVISIONAL_MS) return FollowOutcome(seen, moved = false, confirmed = false)
        val reverted = seen.copy(sessionId = recordId, provisional = null, provisionalAt = null)
        return FollowOutcome(reverted, moved = recordId != state.sessionId, confirmed = true)
    }

    val changed = agent.pid != state.lastPid || recordId != state.lastRecordId
    if (!changed || recordId == state.sessionId) {
        return FollowOutcome(seen, moved = false, confirmed = changed)
    }
    return FollowOutcome(seen.copy(sessionId = recordId), moved = true, confirmed = true)
}

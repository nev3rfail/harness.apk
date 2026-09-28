package apk.harness.assist

/** Why a run is being stopped: nobody is looking, or the conversation moves to a tab. */
enum class StopKind { Dismiss, Escalate }

data class StopState(
    val kind: StopKind,
    val alive: Boolean,
    val turnInFlight: Boolean,
    /** The conversation the tab opens on; null when the panel sent nothing. */
    val sessionId: String?,
    val phase: Phase = Phase.Idle,
    /** Names the timer the current phase set, so an earlier one is told apart. */
    val generation: Int = 0,
) {
    enum class Phase { Idle, Interrupting, Draining, Terminating, Settling, Done }
}

sealed interface StopEvent {
    data object Begin : StopEvent
    /** The turn in flight wrote its `result`. */
    data object Result : StopEvent
    data object Exited : StopEvent
    data class Timer(val generation: Int) : StopEvent
    /** The roster no longer names the conversation, or its wait ran out. */
    data object Settled : StopEvent
    /** Home or the lock screen hid the panel. */
    data object Hidden : StopEvent
    data object Back : StopEvent
}

sealed interface StopAction {
    data object Interrupt : StopAction
    data object CloseStdin : StopAction
    data object Terminate : StopAction
    data object Kill : StopAction
    data class After(val millis: Long, val generation: Int) : StopAction
    /** Wait, up to 1 s, for the roster to stop naming [sessionId], then report Settled. */
    data class Settle(val sessionId: String?) : StopAction
    data class Open(val sessionId: String?) : StopAction
    data object Dismiss : StopAction
}

// 4 + 3 + 2 s, and the 1 s roster wait (HeadlessRun.settle): at most 10 s.
const val INTERRUPT_WAIT = 4_000L
const val EXIT_WAIT = 3_000L
const val KILL_WAIT = 2_000L

/**
 * The next step of a stop. A dismiss closes stdin and sends SIGTERM at once and
 * SIGKILL at 2 s. An escalation ends the transcript on a closed turn -- the turn
 * in flight interrupted and given 4 s for its result, stdin closed and 3 s to
 * exit, SIGTERM and 2 s, SIGKILL -- waits for the roster to let the
 * conversation go, and then opens the tab and dismisses the panel. A hide
 * before the tab opens turns it into a dismiss; back is ignored.
 */
fun escalation(state: StopState, event: StopEvent): Pair<StopState, List<StopAction>> {
    if (state.phase == StopState.Phase.Done) return state to emptyList()
    val none = state to emptyList<StopAction>()
    if (state.phase == StopState.Phase.Settling) return when (event) {
        StopEvent.Settled -> state.copy(phase = StopState.Phase.Done) to listOf(StopAction.Open(state.sessionId), StopAction.Dismiss)
        StopEvent.Hidden -> state.copy(kind = StopKind.Dismiss, phase = StopState.Phase.Done) to emptyList()
        else -> none
    }
    return when (event) {
        StopEvent.Begin -> when {
            !state.alive -> finish(state)
            state.kind == StopKind.Dismiss -> next(state, StopState.Phase.Terminating, KILL_WAIT, StopAction.CloseStdin, StopAction.Terminate)
            state.turnInFlight -> next(state, StopState.Phase.Interrupting, INTERRUPT_WAIT, StopAction.Interrupt)
            else -> drain(state)
        }
        StopEvent.Result -> if (state.phase == StopState.Phase.Interrupting) drain(state) else none
        StopEvent.Exited -> finish(state)
        is StopEvent.Timer -> if (event.generation != state.generation) none else when (state.phase) {
            StopState.Phase.Interrupting -> drain(state)
            StopState.Phase.Draining -> next(state, StopState.Phase.Terminating, KILL_WAIT, StopAction.Terminate)
            StopState.Phase.Terminating -> finish(state, StopAction.Kill)
            else -> none
        }
        StopEvent.Hidden -> if (state.kind == StopKind.Escalate) {
            next(state.copy(kind = StopKind.Dismiss), StopState.Phase.Terminating, KILL_WAIT, StopAction.CloseStdin, StopAction.Terminate)
        } else none
        StopEvent.Settled, StopEvent.Back -> none
    }
}

private fun drain(state: StopState) = next(state, StopState.Phase.Draining, EXIT_WAIT, StopAction.CloseStdin)

private fun next(state: StopState, phase: StopState.Phase, millis: Long, vararg actions: StopAction): Pair<StopState, List<StopAction>> {
    val moved = state.copy(phase = phase, generation = state.generation + 1)
    return moved to actions.toList() + StopAction.After(millis, moved.generation)
}

// A dismiss is done once the process is gone; an escalation waits for the roster first.
private fun finish(state: StopState, vararg first: StopAction): Pair<StopState, List<StopAction>> =
    if (state.kind == StopKind.Escalate) {
        state.copy(phase = StopState.Phase.Settling) to first.toList() + StopAction.Settle(state.sessionId)
    } else {
        state.copy(phase = StopState.Phase.Done) to first.toList()
    }

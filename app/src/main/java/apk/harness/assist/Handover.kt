package apk.harness.assist

/** A hide the panel makes itself, for context or for the microphone. */
enum class HandoverKind { Context, Mic }

data class Handover(val kind: HandoverKind, val generation: Int = 0, val micFinished: Boolean = false)

sealed interface HandoverEvent {
    data object ReShown : HandoverEvent
    data object MicFinished : HandoverEvent
    data object MicStopped : HandoverEvent
    data object ShowFailed : HandoverEvent
    data class Timer(val generation: Int) : HandoverEvent
}

sealed interface HandoverOutcome {
    data object Wait : HandoverOutcome
    data class After(val millis: Long, val generation: Int) : HandoverOutcome
    data object Keep : HandoverOutcome
    data object Dismiss : HandoverOutcome
}

const val HANDOVER_WAIT = 2_000L

/** A context handover waits 2 s from its request; a mic one waits for MicActivity first. */
fun beginHandover(kind: HandoverKind): Pair<Handover, HandoverOutcome> = when (kind) {
    HandoverKind.Context -> Handover(kind, generation = 1) to HandoverOutcome.After(HANDOVER_WAIT, 1)
    HandoverKind.Mic -> Handover(kind) to HandoverOutcome.Wait
}

/** The next step of a handover; a null state means it has ended. */
fun handover(state: Handover, event: HandoverEvent): Pair<Handover?, HandoverOutcome> = when (event) {
    HandoverEvent.ReShown -> null to HandoverOutcome.Keep
    HandoverEvent.MicStopped, HandoverEvent.ShowFailed -> null to HandoverOutcome.Dismiss
    HandoverEvent.MicFinished ->
        if (state.kind == HandoverKind.Mic && !state.micFinished) {
            val next = state.copy(micFinished = true, generation = state.generation + 1)
            next to HandoverOutcome.After(HANDOVER_WAIT, next.generation)
        } else state to HandoverOutcome.Wait
    is HandoverEvent.Timer ->
        if (event.generation == state.generation && (state.kind == HandoverKind.Context || state.micFinished)) {
            null to HandoverOutcome.Dismiss
        } else state to HandoverOutcome.Wait
}

/**
 * One summon's request for screen context. The platform takes a screenshot only
 * with the structure, so both come for [shot]; the structure is taken only for
 * [text].
 */
data class ContextRequest(
    val text: Boolean,
    val shot: Boolean,
    val gotStructure: Boolean = false,
    val gotScreenshot: Boolean = false,
    val open: Boolean = true,
)

enum class Delivery { Structure, Screenshot }

/** Whether to take what arrived, and whether the flags reset and the panel draws again now. */
data class ContextOutcome(val take: Boolean, val close: Boolean)

private val DROP = ContextOutcome(take = false, close = false)

/**
 * What to do with a delivery. With no [request], or a closed one, it is
 * dropped: the platform delivers on every summon, with context or without.
 */
fun deliver(request: ContextRequest?, delivery: Delivery): Pair<ContextRequest?, ContextOutcome> {
    if (request == null || !request.open) return request to DROP
    val next = when (delivery) {
        Delivery.Structure -> request.copy(gotStructure = true)
        Delivery.Screenshot -> request.copy(gotScreenshot = true)
    }
    val complete = next.gotStructure && (!next.shot || next.gotScreenshot)
    val take = when (delivery) {
        Delivery.Structure -> request.text
        Delivery.Screenshot -> request.shot
    }
    return next.copy(open = !complete) to ContextOutcome(take, close = complete)
}

/** Two seconds after the request: the flags reset, and anything later is dropped. */
fun timeOut(request: ContextRequest?): Pair<ContextRequest?, ContextOutcome> =
    request?.copy(open = false) to ContextOutcome(take = false, close = request?.open == true)

/** How long a context request, and a delivery parked for a show, wait. */
const val CONTEXT_WAIT = 2_000L

/**
 * Whether a delivery parked before a show, in summon [parkedIn] at [parkedAt],
 * goes to the panel that show makes: the same summon, within [CONTEXT_WAIT].
 */
fun parkedApplies(parkedIn: Int, generation: Int, parkedAt: Long, now: Long): Boolean =
    parkedIn == generation && now - parkedAt < CONTEXT_WAIT

/** Whether the microphone permission dialog lands under the session window, so the mic hands the panel over. */
const val MIC_DIALOG_UNDER_SESSION = true

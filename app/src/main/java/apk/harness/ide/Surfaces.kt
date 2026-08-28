package apk.harness.ide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the agent has asked the app to put on screen. */
sealed interface Surface {
    /**
     * A proposed edit, awaiting a decision. The agent's tool call stays open
     * until [decision] completes, which is the one place the protocol expects an
     * editor to wait for a person.
     */
    data class Diff(
        val tabName: String,
        val path: String,
        val oldText: String,
        val newText: String,
        val decision: CompletableDeferred<DiffDecision> = CompletableDeferred(),
    ) : Surface

    /**
     * A document on screen, and where it came from.
     *
     * Everything the panel shows is a markdown document: `documentFor` renders
     * markdown as itself, wraps source in a numbered fence, and describes a blob
     * in a sentence. The path is not decoration -- it is the only field
     * `selection_changed` requires, so a surface without one could not be
     * reported to an agent at all.
     */
    data class Document(val path: String, val markdown: String) : Surface

    data class Place(
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val zoom: Double,
    ) : Surface
}

enum class DiffDecision { Accepted, Rejected }

/**
 * The single thing on screen above the terminal.
 *
 * A phone has room for one, so a new surface replaces the last. A diff left
 * unanswered is still rejected when it goes, because the tool call waiting on it
 * has to be told something.
 *
 * One of these serves every agent the app is running. That is what makes the
 * waiting in [show] necessary rather than fussy: with one agent, replacing a
 * pending diff meant that same agent moving on, and with several it would be one
 * agent answering another's question as *Rejected* without either of them
 * hearing about it. What is on screen carries the chat that showed it, so the
 * screen holds one surface and still says whose it is.
 */
class Surfaces {
    private val _visible = MutableStateFlow<Surface?>(null)
    val visible: StateFlow<Surface?> = _visible.asStateFlow()

    private val _owner = MutableStateFlow(NO_CHAT)

    /**
     * Who owns what is on screen.
     *
     * [NO_CHAT] for a surface the app put there itself, which is the one case
     * with no agent to tell about it.
     */
    val owner: StateFlow<Long> = _owner.asStateFlow()

    /**
     * Serialises the screen. Held across the wait below, so a third caller
     * queues behind the second rather than racing it for the slot the first one
     * is about to leave.
     */
    private val screen = Mutex()

    /**
     * Puts [surface] on screen, once the screen is free.
     *
     * Free means: nothing there, or something there that nobody owes an answer
     * to. A diff awaiting a decision is neither, so this waits for that decision
     * rather than answering it. The caller is a tool call, which is already
     * something the agent waits on.
     *
     * [owner] is the chat the surface belongs to, which the tool call arrived
     * carrying. It defaults to [NO_CHAT] for the app showing something of its
     * own, such as a file picked out of the tree.
     */
    suspend fun show(surface: Surface, owner: Long = NO_CHAT) {
        screen.withLock {
            val previous = _visible.value
            if (previous is Surface.Diff && !previous.decision.isCompleted) {
                previous.decision.await()
            }
            // The owner first, so a collector woken by the surface reads the
            // chat that surface belongs to rather than the one before it.
            _owner.value = owner
            _visible.value = surface
        }
    }

    fun dismiss() {
        val previous = _visible.value
        _visible.value = null
        abandon(previous)
    }

    /** Closes a diff by the name the agent gave it. */
    fun closeTab(tabName: String) {
        val current = _visible.value
        if (current is Surface.Diff && current.tabName == tabName) dismiss()
    }

    fun closeAllTabs() {
        if (_visible.value is Surface.Diff) dismiss()
    }

    fun decide(diff: Surface.Diff, decision: DiffDecision) {
        diff.decision.complete(decision)
        if (_visible.value === diff) _visible.value = null
    }

    private fun abandon(surface: Surface?) {
        if (surface is Surface.Diff) surface.decision.complete(DiffDecision.Rejected)
    }
}

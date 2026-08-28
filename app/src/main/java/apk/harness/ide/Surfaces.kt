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
    /**
     * A document a chat has put away, and the selection held in it.
     *
     * The scroll offset is not here: nothing outside the composition reads it,
     * and the composition that draws the band outlives every dialog it opens.
     */
    data class Parked(val document: Surface.Document, val selection: Selection?)

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

    private val _parked = MutableStateFlow<Map<Long, Parked>>(emptyMap())

    /**
     * The document each chat has put away.
     *
     * A map rather than one entry, because a document belongs to the
     * conversation it came up in and several chats may each have one parked. The
     * band at the bottom of the screen draws the entry for the chat on screen.
     */
    val parked: StateFlow<Map<Long, Parked>> = _parked.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)

    /**
     * The selection in the document on screen, if it has one.
     *
     * Here rather than in the panel's composition because parking destroys that
     * composition, and minimising a document and then talking about it is the
     * point of holding a selection at all.
     */
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

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
            // What arrives is what that chat has now, so whatever it had parked
            // is gone, along with the selection held in it. Another chat's
            // parked document is untouched: it is not on screen to be replaced.
            _parked.value = _parked.value - owner
            _selection.value = null
            // The owner first, so a collector woken by the surface reads the
            // chat that surface belongs to rather than the one before it.
            _owner.value = owner
            _visible.value = surface
        }
    }

    /**
     * Takes the surface off screen and forgets it.
     *
     * The selection goes with it: the app has discarded it, and a claim it no
     * longer believes is worse than no claim at all.
     */
    fun dismiss() {
        val previous = _visible.value
        _visible.value = null
        _selection.value = null
        abandon(previous)
    }

    /**
     * Takes the document off screen and keeps it, with its selection, for the
     * chat that showed it.
     *
     * Anything that is not a document stays: a diff is a question an agent is
     * blocked on, and hiding it behind a band would strand them.
     *
     * A document nobody owns stays too. The band is drawn from the entry for
     * the chat on screen and no chat holds [NO_CHAT], so parking one there
     * would put it somewhere nothing can reach it -- a document lost rather
     * than put away.
     */
    fun park() {
        val document = _visible.value as? Surface.Document ?: return
        if (_owner.value == NO_CHAT) return
        _parked.value = _parked.value + (_owner.value to Parked(document, _selection.value))
        _visible.value = null
        _selection.value = null
    }

    /**
     * Puts [key]'s parked document back on screen, with its selection.
     *
     * A diff on screen is left alone, for the reason [park] refuses one: the
     * operator answers the question first, and the band is still there
     * afterwards. This cannot wait for that answer the way [show] does, because
     * it is called from a gesture rather than from a tool call.
     */
    fun restore(key: Long) {
        val put = _parked.value[key] ?: return
        val current = _visible.value
        if (current is Surface.Diff && !current.decision.isCompleted) return
        _parked.value = _parked.value - key
        _owner.value = key
        _visible.value = put.document
        _selection.value = put.selection
    }

    /** Sets or clears the selection in the document on screen. */
    fun select(selection: Selection?) {
        _selection.value = selection
    }

    /**
     * Moves the screen to [key]'s chat.
     *
     * What is showing belongs to another chat, so it parks: a panel left over a
     * conversation it has nothing to do with is the thing this prevents.
     * Nothing is restored in its place -- [key]'s band is drawn from [parked]
     * and the operator pulls it up.
     */
    fun switchTo(key: Long) {
        // Already the arriving chat's own document, which happens when a
        // background chat showed it. It stays on screen.
        if (_owner.value == key) return
        park()
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

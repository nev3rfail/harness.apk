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
     *
     * [lineOffset] is where these lines sit against the file's: a file line is a
     * document line plus the offset, and null when the document describes the
     * file rather than showing it. The wire carries the path, and whoever reads
     * it opens the file, so the numbers beside the text have to be the file's.
     */
    data class Document(val path: String, val markdown: String, val lineOffset: Int?) : Surface

    /**
     * A handoff waiting on a person.
     *
     * The same shape as [Diff]: the tool call stays open until [decision]
     * completes, and `Surfaces` will not replace it while it is undecided. The
     * fire it describes reaches out of the sandbox, so the person answers before
     * it happens rather than reading about it afterwards.
     *
     * [app] is what would receive it, resolved before the question was asked,
     * because "an app" is not something a person can weigh.
     */
    data class Handoff(
        val handoff: apk.harness.intents.Handoff,
        val app: String,
        val decision: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) : Surface

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
     * [closing] says the document was closed rather than minimised, and the
     * entry is kept only so a pull up can undo it. One map rather than a second
     * beside it, because the band is drawn from the entry for the chat on screen
     * and the two states are drawn on the same pixels by the same composable.
     *
     * The scroll offset is not here: nothing outside the composition reads it,
     * and the composition that draws the band outlives every dialog it opens.
     */
    data class Parked(
        val document: Surface.Document,
        val selection: Selection?,
        val closing: Boolean = false,
    )

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
     * to. A question awaiting one is neither, so this waits for that answer
     * rather than giving it. The caller is a tool call, which is already
     * something the agent waits on.
     *
     * [owner] is the chat the surface belongs to, which the tool call arrived
     * carrying. It defaults to [NO_CHAT] for the app showing something of its
     * own, such as a file picked out of the tree.
     */
    suspend fun show(surface: Surface, owner: Long = NO_CHAT) {
        screen.withLock {
            _visible.value?.pending?.await()
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
        clearScreen()
        abandon(previous)
    }

    /**
     * Takes the document off screen and keeps it, with its selection, for the
     * chat that showed it.
     *
     * Anything that is not a document stays: a diff and a handoff are questions
     * an agent is blocked on, and hiding one behind a band would strand them.
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
        clearScreen()
    }

    /**
     * Puts [key]'s parked document back on screen, with its selection.
     *
     * A question on screen is left alone, for the reason [park] refuses one: the
     * operator answers it first, and the band is still there afterwards. This
     * cannot wait for that answer the way [show] does, because it is called from
     * a gesture rather than from a tool call.
     */
    fun restore(key: Long) {
        val put = _parked.value[key] ?: return
        if (_visible.value?.pending != null) return
        _parked.value = _parked.value - key
        _owner.value = key
        _visible.value = put.document
        _selection.value = put.selection
    }

    /**
     * Closes [key]'s document, keeping it briefly so a pull up can undo it.
     *
     * The document goes into [parked] marked closing, with the selection it
     * held, so an unclose brings both back. What is on screen goes with it; a
     * document already behind a band is marked where it stands, which is what a
     * pull down on that band does.
     *
     * Anything that is not a document stays: a diff and a handoff are questions
     * an agent is blocked on, and hiding one behind a band would strand them. A
     * document no chat owns stays too, because no band is drawn for [NO_CHAT]
     * and an entry there could neither be seen nor undone.
     */
    fun close(key: Long) {
        if (key == NO_CHAT) return
        val shown = (_visible.value as? Surface.Document)?.takeIf { _owner.value == key }
        val put = _parked.value[key]
        val entry = when {
            shown != null -> Parked(shown, _selection.value, closing = true)
            put != null -> put.copy(closing = true)
            else -> return
        }
        _parked.value = _parked.value + (key to entry)
        if (shown != null) clearScreen()
    }

    /**
     * Drops [key]'s parked document, and the selection held in it.
     *
     * The one way a parked document leaves without coming back. A chat that has
     * gone takes its entry with it for the same reason: a document parked
     * against a key nothing can reach is a document nothing can close.
     */
    fun forget(key: Long) {
        _parked.value = _parked.value - key
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
     *
     * Every closing entry goes, whosever it is. An undo is for the thing just
     * done, and the operator has gone to look at something else.
     */
    fun switchTo(key: Long) {
        _parked.value = _parked.value.filterValues { !it.closing }
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
        if (_visible.value === diff) clearScreen()
    }

    /**
     * Answers a handoff, the way [decide] answers a diff.
     *
     * [allowed] false is the operator declining, which the waiting tool call
     * reports as an answer rather than as a failure: it asked, and a person said
     * no.
     */
    fun answer(handoff: Surface.Handoff, allowed: Boolean) {
        handoff.decision.complete(allowed)
        if (_visible.value === handoff) clearScreen()
    }

    /**
     * Empties the screen.
     *
     * A selection exists only while the document holding it is visible, so the
     * two are cleared together everywhere rather than the rule being restated
     * at each place that clears one of them.
     */
    private fun clearScreen() {
        _visible.value = null
        _selection.value = null
    }

    /**
     * Refuses whatever was on screen on behalf of whoever is waiting behind it.
     *
     * A question taken off screen unanswered would strand the tool call that
     * asked it, so going away is itself an answer, and the safe answer is no.
     */
    private fun abandon(surface: Surface?) {
        when (surface) {
            is Surface.Diff -> surface.decision.complete(DiffDecision.Rejected)
            is Surface.Handoff -> surface.decision.complete(false)
            else -> Unit
        }
    }

    /**
     * The answer a tool call is waiting behind this surface for, if there is one.
     *
     * A diff and a handoff are the same kind of thing -- a question nobody has
     * answered yet -- and every rule about holding the screen is about that
     * rather than about which of the two it is. One place to ask keeps a third
     * kind of question from having to be added to a list of special cases.
     */
    private val Surface.pending: CompletableDeferred<*>?
        get() = when (this) {
            is Surface.Diff -> decision.takeIf { !it.isCompleted }
            is Surface.Handoff -> decision.takeIf { !it.isCompleted }
            else -> null
        }
}

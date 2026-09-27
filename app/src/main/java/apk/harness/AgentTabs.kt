package apk.harness

import android.util.Log
import apk.harness.agents.AgentBackend
import apk.harness.agents.BACKENDS
import apk.harness.agents.FollowState
import apk.harness.agents.PROVISIONAL_MS
import apk.harness.agents.RunningSession
import apk.harness.agents.TabIdentity
import apk.harness.agents.agentOf
import apk.harness.agents.followDecision
import apk.harness.agents.freshSession
import apk.harness.agents.isSessionId
import apk.harness.chats.Chat
import apk.harness.chats.oneSpelling
import apk.harness.ide.IdeServer
import com.ghostty.android.terminal.TerminalSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One agent, with everything that belongs to it alone.
 *
 * [key] is this tab's identity for as long as it exists, and is not the session
 * id: a tab is a thing on screen and a session id is a conversation on disk, and
 * the same conversation reopened later is a different tab.
 */
data class AgentTab(
    val key: Long,
    /** The conversation this agent is in, settled before it starts. */
    val sessionId: String,
    /** The working directory, which decides which project the conversation is filed under. */
    val directory: File,
    /** What a drawer row calls this tab while no transcript names it. */
    val label: String,
    /** The CLI running in this tab, which owns the spelling of its arguments. */
    val backend: AgentBackend,
    val session: TerminalSession,
    val ide: IdeServer,
    /**
     * What every process of this tab carries in its environment, so a record a
     * process writes about itself names the tab it runs in. A random id rather
     * than [key]: keys count from one per screen, and an agent left over from an
     * earlier screen would claim the new tab of the same key.
     */
    val tag: String,
)

/**
 * The agents the app is running, and which of them is on screen.
 *
 * Held by the composition, and aliased onto a field so the activity itself can
 * reach it: onDestroy stops every tab, onNewIntent shows the one a notification
 * names, and an ask resolves its chat through it. The activity declares every
 * configuration change it handles, so nothing short of the process ending
 * recreates that composition.
 *
 * Each tab gets its own [IdeServer] on its own port and is told which one it is,
 * so several editors under one agent home stay several answers to several
 * questions rather than several answers to one. What the tabs share is the
 * screen: one [apk.harness.ide.Surfaces] serves all of them, because a phone has
 * room for one panel.
 *
 * A tab follows its agent. The CLI moves to another conversation on its own --
 * `/new`, `/clear`, a `/resume` typed into it -- and rewrites the record it
 * keeps about itself, so the tab takes its conversation from its agent's record
 * rather than keeping the one it was opened on. Every change to the list of tabs
 * is made under one lock, because the roster arrives on the thread that heard
 * it while taps arrive on the main thread and tabs are built on another.
 */
class AgentTabs(
    private val agent: Agent,
    /**
     * Builds an editor for a workspace, bound to the tab's key.
     *
     * The key goes in because an editor serves one tab: it is what lets a tool
     * call arriving there say which chat made it.
     */
    private val editorFor: (File, Long) -> IdeServer,
    /**
     * The MCP config naming the app's panel server for one chat, or null when
     * there is none. One server serves every chat, so the config is what
     * carries the chat's own token.
     */
    private val panelConfigFor: (Long) -> File?,
    /** Drops a chat's panel config when the chat goes. */
    private val releasePanelConfig: (Long) -> Unit,
    /**
     * Drops a chat's parked document when the chat goes.
     *
     * A tab's editor is stopped when the tab goes and the screen's own state is
     * left alone, so without this a document stays parked against a key nothing
     * can reach: no band is drawn for a chat that is not there, and no gesture
     * can close it.
     */
    private val forgetParked: (Long) -> Unit,
    /** The time, which a test replaces. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var nextKey = 1L

    private val lock = Any()

    // Set by stopAll, under the lock, so a tab built while the agents were
    // being stopped is released rather than added to a list that is gone.
    private var stopped = false

    // What each tab knows about following its agent, by key.
    private val following = HashMap<Long, FollowState>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tabs = MutableStateFlow<List<AgentTab>>(emptyList())
    val tabs: StateFlow<List<AgentTab>> = _tabs.asStateFlow()

    private val _activeKey = MutableStateFlow(0L)
    val activeKey: StateFlow<Long> = _activeKey.asStateFlow()

    val active: AgentTab? get() = _tabs.value.firstOrNull { it.key == _activeKey.value }

    // After every field it reads: the roster may arrive at once.
    init {
        Roster.publishTabs(::identities)
        scope.launch { Roster.roster.collect(::follow) }
    }

    /** The tabs as their processes know them, for a reader outside the screen. */
    private fun identities(): List<TabIdentity> =
        _tabs.value.map { TabIdentity(it.tag, it.session.pid) }

    /** The tab a conversation is in, whether or not it is showing. */
    fun tabFor(sessionId: String): AgentTab? =
        _tabs.value.firstOrNull { it.sessionId == sessionId }

    /**
     * Puts the tab holding [sessionId] on screen, and says whether one does.
     *
     * A switch and not a start: every tab stays composed while it is hidden, so
     * the agent behind this one has been running all along. Everything downstream
     * follows `activeKey` -- the visible terminal, the parked document band, and
     * the surface the panel draws.
     */
    fun show(sessionId: String): Boolean {
        val tab = tabFor(sessionId) ?: return false
        _activeKey.value = tab.key
        return true
    }

    /**
     * The tab the app opens for itself, in the agent's home.
     *
     * The conversation is resolved before the agent starts: the backend names
     * the one it would pick up, and the tab opens on that id. So the tab carries
     * a session id from its first frame, which is what lets the drawer draw it
     * as a row and reach it.
     *
     * A conversation another process is in gets a fresh id instead: the newest
     * transcript is the one being written, and the roster says who is writing
     * it.
     */
    fun openDefault(): AgentTab? {
        val backend = BACKENDS.first()
        val taken = backend.running(agent.home).mapTo(HashSet()) { it.sessionId }
        val mostRecent = backend.mostRecent(agent.home, agent.home)
        val sessionId = freshSession(mostRecent, taken) { UUID.randomUUID().toString() }
        return add(
            directory = agent.home,
            sessionId = sessionId,
            label = HOME_LABEL,
            backend = backend,
            arguments =
                if (sessionId == mostRecent) backend.resume(sessionId)
                else backend.start(sessionId),
        )
    }

    /**
     * Reopens a conversation, in the project it belongs to.
     *
     * A conversation that already has a tab is brought forward rather than
     * started twice: two agents appending to one transcript is a conversation
     * with two authors and no way to read it back.
     */
    fun resume(chat: Chat, directory: File): AgentTab? {
        tabFor(chat.sessionId)?.let { existing ->
            _activeKey.value = existing.key
            return existing
        }
        val backend = backendFor(chat.backendId)
        return add(
            directory = directory,
            sessionId = chat.sessionId,
            label = chat.label,
            backend = backend,
            arguments = backend.resume(chat.sessionId),
        )
    }

    /**
     * Starts a conversation in [directory], under an id this app chooses.
     *
     * The id is the app's rather than the agent's, so the tab and the drawer
     * name the same conversation before a transcript exists.
     */
    fun start(directory: File, label: String): AgentTab? {
        val sessionId = UUID.randomUUID().toString()
        val backend = BACKENDS.first()
        return add(
            directory = directory,
            sessionId = sessionId,
            label = label,
            backend = backend,
            arguments = backend.start(sessionId),
        )
    }

    /**
     * Puts [chat] on the screen in place of what the tab there holds.
     *
     * Two ways in, and the tab decides which. An agent that is alive, runs
     * [chat]'s backend, stands in [directory] and takes a switching command is
     * told to change conversation: the line goes into its pty and the tab takes
     * the new id, because the app asked for the switch and the tab names what is
     * on screen rather than what the agent left.
     *
     * Anything else is a different agent: the chat opens in a tab of its own and
     * the one it replaces closes. A working directory is fixed for the life of a
     * process, and it is both what a conversation id resolves against and what
     * the conversation is filed under, so a chat of another project is one no
     * running agent can be moved into.
     *
     * Either way it costs the conversation the tab was in, which is why this is
     * a control of its own rather than what a tap does. Whatever is typed and
     * not sent is lost with it, so the caller reads the prompt first.
     *
     * Starting an agent binds a socket and may stage the agent's home, so this
     * does not belong on the thread a tap arrives on.
     */
    fun continueHere(chat: Chat, directory: File) {
        val tab = active ?: return
        if (tab.sessionId == chat.sessionId) return
        val backend = backendFor(chat.backendId)

        val line = backend.switch(chat.sessionId)?.takeIf {
            tab.backend.id == backend.id &&
                tab.directory.absolutePath == directory.absolutePath &&
                tab.session.isRunning.value
        }
        if (line == null) {
            // The new tab first: the last tab does not close, and this is what
            // stands behind the one going away.
            add(
                directory = directory,
                sessionId = chat.sessionId,
                label = chat.label,
                backend = backend,
                arguments = backend.resume(chat.sessionId),
            )
            close(tab.key)
            return
        }

        // Enter, as a terminal receives it.
        tab.session.write(line + "\r")
        // The tab takes the id at once, so the drawer does not show the chat it
        // left as current while the agent rewrites its record. Until the record
        // agrees the id is provisional: a line that went into an overlay, or an
        // id the CLI refused, leaves the record where it was, and a switch the
        // record never confirms gives way to it. Those make no event, so the
        // decision is run again once the wait is over.
        synchronized(lock) {
            val state = following[tab.key] ?: FollowState(tab.sessionId)
            following[tab.key] = state.copy(
                sessionId = chat.sessionId,
                provisional = chat.sessionId,
                provisionalAt = now(),
            )
            _tabs.value = _tabs.value.map {
                if (it.key == tab.key) it.copy(sessionId = chat.sessionId, label = chat.label) else it
            }
        }
        scope.launch {
            delay(PROVISIONAL_MS)
            follow(Roster.roster.value)
        }
        Log.i(TAG, "tab ${tab.key} carries session ${chat.sessionId}")
    }

    /**
     * Moves every tab onto the conversation its agent is in, as [roster] has it.
     *
     * A tab moved to a conversation it was not in takes its directory's name as
     * its label, which the drawer draws until the new conversation's transcript
     * names it; the old label named the conversation it left.
     */
    private fun follow(roster: List<RunningSession>) {
        synchronized(lock) {
            if (stopped) return
            val time = now()
            var changed = false
            val next = _tabs.value.map { tab ->
                val state = following[tab.key] ?: FollowState(tab.sessionId)
                val outcome = followDecision(state, agentOf(tab.tag, roster), time)
                following[tab.key] = outcome.state
                val sessionId = outcome.state.sessionId
                if (sessionId == tab.sessionId) return@map tab
                changed = true
                Log.i(TAG, "tab ${tab.key} follows its agent to session $sessionId")
                tab.copy(
                    sessionId = sessionId,
                    label = if (outcome.moved) labelFor(tab.directory) else tab.label,
                )
            }
            if (changed) _tabs.value = next
        }
    }

    /** What a tab in [directory] is called until a transcript names its chat. */
    private fun labelFor(directory: File): String =
        if (oneSpelling(directory.absolutePath) == oneSpelling(agent.home.absolutePath)) HOME_LABEL
        else directory.name

    /**
     * Closes a tab and the agent in it.
     *
     * The last tab does not close. There is no screen behind it: the terminal is
     * what the app is, and a switcher over nothing is not a state worth being
     * able to reach.
     */
    fun close(key: Long) {
        val closing = synchronized(lock) {
            val remaining = _tabs.value.filter { it.key != key }
            if (remaining.size == _tabs.value.size || remaining.isEmpty()) return
            val closing = _tabs.value.first { it.key == key }

            _tabs.value = remaining
            following.remove(key)
            if (_activeKey.value == key) _activeKey.value = remaining.last().key
            closing
        }
        release(closing)
    }

    /**
     * Closes the tab holding [sessionId], if one does.
     *
     * Goes through [close], which does not close the last tab.
     */
    fun closeSession(sessionId: String) {
        tabFor(sessionId)?.let { close(it.key) }
    }

    /**
     * Every agent, on the way out.
     *
     * Following stops first, so a roster heard after this cannot bring the
     * released tabs back.
     */
    fun stopAll() {
        val closing = synchronized(lock) {
            stopped = true
            scope.cancel()
            following.clear()
            _tabs.value.also { _tabs.value = emptyList() }
        }
        closing.forEach(::release)
    }

    /** The backend [id] names, or the first for a chat that names none of them. */
    private fun backendFor(id: String): AgentBackend =
        BACKENDS.firstOrNull { it.id == id } ?: BACKENDS.first()

    private fun add(
        directory: File,
        sessionId: String,
        label: String,
        backend: AgentBackend,
        arguments: List<String>,
    ): AgentTab? {
        // Refused where it would reach the CLI's command line. Every place an id
        // enters the app checks it; this is the one after all of them.
        if (!isSessionId(sessionId)) {
            Log.w(TAG, "refused a tab on '$sessionId', which is not a session id")
            return null
        }

        // The key is settled first: the editor and the panel config are both
        // bound to it, and both are built before the agent starts.
        val key = synchronized(lock) { nextKey++ }
        val tag = UUID.randomUUID().toString()

        // Built outside the lock: the editor binds a socket and the session may
        // stage the agent's home, and the lock is also taken on the main thread.
        // The editor is bound before the agent starts, because the port travels
        // to the agent in its environment rather than being found on disk.
        val editor = editorFor(directory, key)
        val port = editor.start()

        val configured = panelConfigFor(key)?.let { arguments + backend.mcpConfig(it) } ?: arguments

        val session = agent.session(
            directory = directory,
            idePort = port,
            arguments = configured,
            script = backend.script,
            tag = tag,
        )

        val tab = AgentTab(key, sessionId, directory, label, backend, session, editor, tag)
        val added = synchronized(lock) {
            if (stopped) return@synchronized false
            _tabs.value = _tabs.value + tab
            following[key] = FollowState(sessionId)
            _activeKey.value = key
            true
        }
        if (!added) {
            release(tab)
            return null
        }
        Log.i(TAG, "tab $key on $directory, session $sessionId, ide $port")
        return tab
    }

    private fun release(tab: AgentTab) {
        runCatching { tab.session.stop() }
        runCatching { tab.ide.stop() }
        // The panel config goes with the tab: its token is what says calls come
        // from this chat, and a token outliving its chat is a credential nobody
        // owns.
        runCatching { releasePanelConfig(tab.key) }
        runCatching { forgetParked(tab.key) }
    }

    private companion object {
        const val TAG = "AgentTabs"

        /** What the app's own tab is called until a transcript names it. */
        const val HOME_LABEL = "home"
    }
}

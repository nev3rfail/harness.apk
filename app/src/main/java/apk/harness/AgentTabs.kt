package apk.harness

import android.os.SystemClock
import android.util.Log
import apk.harness.agents.AgentBackend
import apk.harness.agents.BACKENDS
import apk.harness.agents.FollowState
import apk.harness.agents.PROVISIONAL_MS
import apk.harness.agents.TabIdentity
import apk.harness.agents.agentOf
import apk.harness.agents.followDecision
import apk.harness.agents.freshSession
import apk.harness.agents.isSessionId
import apk.harness.chats.Chat
import apk.harness.chats.oneSpelling
import apk.harness.ide.IdeServer
import apk.harness.tabs.RestorePlan
import apk.harness.tabs.SavedTab
import apk.harness.tabs.SavedTabs
import apk.harness.tabs.TabsWriter
import apk.harness.tabs.readSaved
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
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
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
    /** The conversation this agent is in, as its record last said. */
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
 * keeps about itself, and the tab takes its conversation from that record.
 *
 * The open tabs are kept in [savedFile] and come back with the next screen. A
 * tab can be dormant -- what `spec/088` calls parked: a conversation with a row
 * in the drawer and no agent yet, which starts when it is opened. A start brings
 * the tab that was in front back running and the rest back dormant.
 *
 * Every change to the tabs is made under one lock, because the roster arrives on
 * the thread that heard it while taps arrive on the main thread and tabs are
 * built on another.
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
    /** Where the open tabs are kept across the process. */
    private val savedFile: File,
    /** Tells the operator why a conversation did not open, on any thread. */
    private val refuse: (String) -> Unit,
    /** The time on a clock that does not step, which a test replaces. */
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var nextKey = 1L

    private val lock = Any()

    // Set by stopAll, under the lock, so a tab built while the agents were
    // being stopped is released rather than added to a list that is gone.
    private var stopped = false

    // What each tab knows about following its agent, by key.
    private val following = HashMap<Long, FollowState>()

    // The conversations whose agent is being started, held from the check that
    // no tab has them until the tab is added.
    private val starting = HashSet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One screen writes the file after another, and a screen whose end was
    // never reported writes nothing over the one that replaced it.
    private val writer = TabsWriter(
        savedFile,
        generation = (readSaved(savedFile)?.generation ?: 0L) + 1,
    ) { Log.w(TAG, "the open tabs were not written", it) }

    private val _tabs = MutableStateFlow<List<AgentTab>>(emptyList())
    val tabs: StateFlow<List<AgentTab>> = _tabs.asStateFlow()

    private val _dormant = MutableStateFlow<List<SavedTab>>(emptyList())

    /** The tabs that have a row and no agent yet. */
    val dormant: StateFlow<List<SavedTab>> = _dormant.asStateFlow()

    private val _activeKey = MutableStateFlow(0L)
    val activeKey: StateFlow<Long> = _activeKey.asStateFlow()

    val active: AgentTab? get() = _tabs.value.firstOrNull { it.key == _activeKey.value }

    // Held so stopAll can take back exactly the source it gave.
    private val identities: () -> List<TabIdentity> = {
        _tabs.value.map { TabIdentity(it.tag, it.session.pid, it.sessionId, it.label, it.key) }
    }

    // After every field it reads: the roster may arrive at once.
    init {
        Roster.publishTabs(identities)
        scope.launch { Roster.roster.collect { follow() } }
    }

    /** The running tab a conversation is in, whether or not it is showing. */
    fun tabFor(sessionId: String): AgentTab? =
        _tabs.value.firstOrNull { it.sessionId == sessionId }

    /** True when [sessionId] has a tab, running or dormant. */
    fun hasTab(sessionId: String): Boolean =
        tabFor(sessionId) != null || _dormant.value.any { it.sessionId == sessionId }

    /**
     * Puts the tab holding [sessionId] on screen, and says whether one does.
     *
     * A switch and not a start: every tab stays composed while it is hidden, so
     * the agent behind this one has been running all along. Everything downstream
     * follows `activeKey` -- the visible terminal, the parked document band, and
     * the surface the panel draws.
     */
    fun show(sessionId: String): Boolean = synchronized(lock) {
        val tab = tabFor(sessionId) ?: return false
        _activeKey.value = tab.key
        persist()
        true
    }

    /**
     * Brings the saved tabs back as [plan] says: its tab to start, running, and
     * the rest dormant. Answers whether a tab runs afterwards.
     */
    fun restore(plan: RestorePlan): Boolean {
        synchronized(lock) {
            if (stopped) return false
            // The tab to start is dormant until it starts, so a start that is
            // refused leaves it where the others are rather than losing it.
            _dormant.value = plan.dormant + listOfNotNull(plan.start)
            // Written by the start that follows, which names the tab in front;
            // a write here would name none.
        }
        for (tab in plan.dropped) Log.i(TAG, "dropped the tab of ${tab.sessionId} in ${tab.directory}")
        val start = plan.start ?: return false
        return openDormant(start.sessionId) != null
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
        val taken = Roster.readNow().mapTo(HashSet()) { it.sessionId }
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
            trusted = true,
        )
    }

    /**
     * Reopens a conversation, in the project it belongs to.
     *
     * A conversation that already has a tab is brought forward rather than
     * started twice: two agents appending to one transcript is a conversation
     * with two authors and no way to read it back. A dormant tab of it starts,
     * untrusted: its directory came back from a file, not from the operator.
     */
    fun resume(chat: Chat, directory: File, trusted: Boolean = true): AgentTab? {
        tabFor(chat.sessionId)?.let { existing ->
            show(existing.sessionId)
            return existing
        }
        if (_dormant.value.any { it.sessionId == chat.sessionId }) {
            return openDormant(chat.sessionId)
        }
        return openConversation(directory, chat.sessionId, chat.backendId, chat.label, trusted)
    }

    /**
     * Opens [sessionId] in [directory] in a tab of its own, resuming it when the
     * history holds its transcript and starting it under that id when it does
     * not -- a conversation opened and never spoken in has nothing to resume.
     *
     * Refused, with the reason told, while the roster names the conversation as
     * live in another agent. A conversation already starting -- a second tap
     * while the first one's agent is still coming up -- is not started again:
     * the id is held from the check to the tab being added.
     */
    fun openConversation(
        directory: File,
        sessionId: String,
        backendId: String,
        label: String,
        trusted: Boolean,
    ): AgentTab? {
        synchronized(lock) {
            tabFor(sessionId)?.let { existing ->
                _activeKey.value = existing.key
                persist()
                return existing
            }
            if (!starting.add(sessionId)) return null
        }
        try {
            if (liveElsewhere(sessionId)) return null
            val backend = backendFor(backendId)
            val arguments =
                if (backend.hasTranscript(agent.home, sessionId)) backend.resume(sessionId)
                else backend.start(sessionId)
            return add(directory, sessionId, label, backend, arguments, trusted)
        } finally {
            synchronized(lock) { starting.remove(sessionId) }
        }
    }

    /**
     * Starts a conversation in [directory], under an id this app chooses.
     *
     * The id is the app's rather than the agent's, so the tab and the drawer
     * name the same conversation before a transcript exists.
     */
    fun start(directory: File, label: String, trusted: Boolean = true): AgentTab? {
        val sessionId = UUID.randomUUID().toString()
        val backend = BACKENDS.first()
        return add(
            directory = directory,
            sessionId = sessionId,
            label = label,
            backend = backend,
            arguments = backend.start(sessionId),
            trusted = trusted,
        )
    }

    /**
     * The session of a tab in [directory], running or dormant, whose
     * conversation has no transcript yet: a tab nobody has spoken in.
     */
    fun untouchedIn(directory: File): String? {
        val path = oneSpelling(directory.absolutePath)
        val backend = BACKENDS.first()
        val running = _tabs.value.firstOrNull {
            oneSpelling(it.directory.absolutePath) == path &&
                !it.backend.hasTranscript(agent.home, it.sessionId)
        }
        if (running != null) return running.sessionId
        return _dormant.value.firstOrNull {
            oneSpelling(File(it.directory).absolutePath) == path &&
                !backend.hasTranscript(agent.home, it.sessionId)
        }?.sessionId
    }

    /**
     * Starts the dormant tab of [sessionId] as a running one, in its own
     * directory. It is not trusted by being started: its directory came back
     * from a file, and a restart is not a tap.
     */
    fun openDormant(sessionId: String): AgentTab? {
        val saved = _dormant.value.firstOrNull { it.sessionId == sessionId } ?: return null
        // The start drops the dormant record once the tab is added.
        return openConversation(File(saved.directory), saved.sessionId, saved.backend, saved.label, trusted = false)
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
     * A conversation live in another agent is refused before anything is typed.
     *
     * Starting an agent binds a socket and may stage the agent's home, so this
     * does not belong on the thread a tap arrives on.
     */
    fun continueHere(chat: Chat, directory: File) {
        val tab = active ?: return
        if (tab.sessionId == chat.sessionId) return
        // A conversation another tab holds, or one starting, is brought forward
        // rather than typed into this agent: that would be two writers.
        tabFor(chat.sessionId)?.let {
            show(it.sessionId)
            return
        }
        if (synchronized(lock) { chat.sessionId in starting }) return
        if (liveElsewhere(chat.sessionId)) return
        val backend = backendFor(chat.backendId)

        val line = backend.switch(chat.sessionId)?.takeIf {
            tab.backend.id == backend.id &&
                tab.directory.absolutePath == directory.absolutePath &&
                tab.session.isRunning.value
        }
        if (line == null) {
            // The new tab first: the last tab does not close, and this is what
            // stands behind the one going away.
            openConversation(directory, chat.sessionId, chat.backendId, chat.label, trusted = true)
                ?: return
            close(tab.key)
            return
        }

        // The tab takes the id before the line is typed, so the drawer does not
        // show the chat it left as current while the agent rewrites its record.
        // Until the record agrees the id is provisional: a line that went into an
        // overlay, or an id the CLI refused, leaves the record where it was, and a
        // switch the record never confirms gives way to it. Those make no event,
        // so the decision is run again once the wait is over.
        synchronized(lock) {
            if (_tabs.value.none { it.key == tab.key }) return
            // Checked again where the id is claimed: a start of it may have
            // begun since the check above.
            if (tabFor(chat.sessionId) != null || chat.sessionId in starting) return
            val state = following[tab.key] ?: FollowState(tab.sessionId)
            following[tab.key] = state.copy(
                sessionId = chat.sessionId,
                provisional = chat.sessionId,
                provisionalAt = now(),
            )
            _tabs.value = _tabs.value.map {
                if (it.key == tab.key) it.copy(sessionId = chat.sessionId, label = chat.label) else it
            }
            persist()
        }
        // Enter, as a terminal receives it.
        tab.session.write(line + "\r")
        scope.launch {
            delay(PROVISIONAL_MS + PROVISIONAL_SLACK_MS)
            follow()
        }
        Log.i(TAG, "tab ${tab.key} carries session ${chat.sessionId}")
    }

    /**
     * Moves every tab onto the conversation its agent is in, as the newest
     * roster has it.
     *
     * Read under the lock rather than taken as an argument, so two runs never
     * decide on two rosters in the wrong order. A tab moved to a conversation it
     * was not in takes its directory's name as its label, which the drawer draws
     * until the new conversation's transcript names it. A dormant tab of the
     * conversation a running tab now holds is dropped, once the agent's record
     * confirms it.
     */
    private fun follow() {
        synchronized(lock) {
            if (stopped) return
            val roster = Roster.roster.value
            val time = now()
            var changed = false
            val confirmed = HashSet<String>()
            val next = _tabs.value.map { tab ->
                val state = following[tab.key] ?: FollowState(tab.sessionId)
                val outcome = followDecision(state, agentOf(tab.tag, roster), time)
                following[tab.key] = outcome.state
                if (outcome.confirmed) confirmed += outcome.state.sessionId
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
            val dormant = _dormant.value.filter { it.sessionId !in confirmed }
            if (dormant.size != _dormant.value.size) {
                _dormant.value = dormant
                changed = true
            }
            if (changed) persist()
        }
    }

    /**
     * True when the roster names [sessionId] as live in an agent, and the
     * operator has been told so. A conversation with two writers is never the
     * answer, and a leftover agent that will not exit ends with the app.
     */
    private fun liveElsewhere(sessionId: String): Boolean {
        if (Roster.readNow().none { it.sessionId == sessionId }) return false
        refuse(
            "That conversation is still open in an earlier agent. " +
                "Force-stopping the app from Android's settings ends it.",
        )
        return true
    }

    /** What a tab in [directory] is called until a transcript names its chat. */
    private fun labelFor(directory: File): String =
        if (oneSpelling(directory.absolutePath) == oneSpelling(agent.home.absolutePath)) HOME_LABEL
        else directory.name

    /**
     * Closes a tab and the agent in it.
     *
     * The last running tab does not close. There is no screen behind it: the
     * terminal is what the app is, and a switcher over nothing is not a state
     * worth being able to reach.
     */
    fun close(key: Long) {
        val closing = synchronized(lock) {
            val remaining = _tabs.value.filter { it.key != key }
            if (remaining.size == _tabs.value.size || remaining.isEmpty()) return
            val closing = _tabs.value.first { it.key == key }

            _tabs.value = remaining
            following.remove(key)
            if (_activeKey.value == key) _activeKey.value = remaining.last().key
            persist()
            closing
        }
        release(closing)
    }

    /**
     * Closes the tab holding [sessionId], running or dormant, if one does.
     *
     * A running one goes through [close], which does not close the last one.
     */
    fun closeSession(sessionId: String) {
        tabFor(sessionId)?.let {
            close(it.key)
            return
        }
        synchronized(lock) {
            _dormant.value = _dormant.value.filter { it.sessionId != sessionId }
            persist()
        }
    }

    /**
     * Every agent, on the way out.
     *
     * Following stops first, so a roster heard after this cannot bring the
     * released tabs back. What is kept is what the tabs were before this: the
     * writer writes what waits and takes nothing more, so the tabs come back on
     * the next start however the app went away.
     */
    fun stopAll() {
        val closing = synchronized(lock) {
            stopped = true
            scope.cancel()
            following.clear()
            _tabs.value.also { _tabs.value = emptyList() }
        }
        Roster.withdrawTabs(identities)
        writer.close()
        closing.forEach(::release)
    }

    /**
     * Hands the writer the tabs as they are now, and says they changed. Called
     * under the lock, on every change to the tabs.
     */
    private fun persist() {
        if (stopped) return
        Roster.tabsChanged()
        val running = _tabs.value.map { SavedTab(it.directory.absolutePath, it.sessionId, it.backend.id, it.label) }
        val saved = SavedTabs(
            generation = 0L,
            front = active?.sessionId,
            tabs = running + _dormant.value.filter { dormant -> running.none { it.sessionId == dormant.sessionId } },
        )
        writer.offer(saved)
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
        trusted: Boolean,
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
            trusted = trusted,
        )

        val tab = AgentTab(key, sessionId, directory, label, backend, session, editor, tag)
        val added = synchronized(lock) {
            if (stopped || tabFor(sessionId) != null) return@synchronized false
            _tabs.value = _tabs.value + tab
            // A conversation with a running tab has no dormant one.
            _dormant.value = _dormant.value.filter { it.sessionId != sessionId }
            following[key] = FollowState(sessionId)
            _activeKey.value = key
            persist()
            true
        }
        if (!added) {
            release(tab)
            return null
        }
        // A terminal whose process ends may leave its agent's record behind, and
        // a death writes nothing the roster would hear.
        scope.launch {
            session.isRunning.dropWhile { !it }.first { !it }
            Roster.refresh()
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
        Roster.refresh()
    }

    private companion object {
        const val TAG = "AgentTabs"

        /** What the app's own tab is called until a transcript names it. */
        const val HOME_LABEL = "home"

        /** Past the provisional wait, so the run after it finds the wait over. */
        const val PROVISIONAL_SLACK_MS = 100L
    }
}

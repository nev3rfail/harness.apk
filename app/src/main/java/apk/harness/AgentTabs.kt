package apk.harness

import android.util.Log
import apk.harness.agents.AgentBackend
import apk.harness.agents.BACKENDS
import apk.harness.agents.freshSession
import apk.harness.chats.Chat
import apk.harness.ide.IdeServer
import com.ghostty.android.terminal.TerminalSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
)

/**
 * The agents the app is running, and which of them is on screen.
 *
 * Held by the Activity rather than by a composition, because an agent outlives
 * every recomposition and most configuration changes.
 *
 * Each tab gets its own [IdeServer] on its own port and is told which one it is,
 * so several editors under one agent home stay several answers to several
 * questions rather than several answers to one. What the tabs share is the
 * screen: one [apk.harness.ide.Surfaces] serves all of them, because a phone has
 * room for one panel.
 */
class AgentTabs(
    private val agent: Agent,
    /** Builds an editor for a workspace. */
    private val editorFor: (File) -> IdeServer,
    /** The MCP config naming the app's panel server, or null when it has none. */
    private val panelConfig: () -> File?,
) {
    private var nextKey = 1L

    private val _tabs = MutableStateFlow<List<AgentTab>>(emptyList())
    val tabs: StateFlow<List<AgentTab>> = _tabs.asStateFlow()

    private val _activeKey = MutableStateFlow(0L)
    val activeKey: StateFlow<Long> = _activeKey.asStateFlow()

    val active: AgentTab? get() = _tabs.value.firstOrNull { it.key == _activeKey.value }

    /** The tab a conversation is in, whether or not it is showing. */
    fun tabFor(sessionId: String): AgentTab? =
        _tabs.value.firstOrNull { it.sessionId == sessionId }

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
    fun openDefault(): AgentTab {
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
    fun resume(chat: Chat, directory: File): AgentTab {
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
    fun start(directory: File, label: String): AgentTab {
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
     * Moves the agent on screen to [chat], in the tab it is already in.
     *
     * The line goes into the running agent's pty and the tab takes the new id:
     * the app asked for the switch, so the tab names the conversation on screen
     * rather than the one the agent left. What it costs is that conversation,
     * which is why this is a control of its own rather than what a tap does.
     *
     * Nothing is written when the tab is already in [chat], when [chat] was read
     * by another backend -- the agent on screen would not understand the line --
     * when the backend has no such command, or when [directory] is not the tab's
     * own. The agent resolves a conversation id against the directory it runs in
     * and files the conversation there, so a chat of another project is one this
     * tab cannot take.
     */
    fun continueHere(chat: Chat, directory: File) {
        val tab = active ?: return
        if (tab.sessionId == chat.sessionId || tab.backend.id != chat.backendId) return
        if (tab.directory.absolutePath != directory.absolutePath) return
        val line = tab.backend.switch(chat.sessionId) ?: return

        // Enter, as a terminal receives it.
        tab.session.write(line + "\r")
        _tabs.value = _tabs.value.map {
            if (it.key == tab.key) it.copy(sessionId = chat.sessionId, label = chat.label) else it
        }
        Log.i(TAG, "tab ${tab.key} carries session ${chat.sessionId}")
    }

    /**
     * Closes a tab and the agent in it.
     *
     * The last tab does not close. There is no screen behind it: the terminal is
     * what the app is, and a switcher over nothing is not a state worth being
     * able to reach.
     */
    fun close(key: Long) {
        val remaining = _tabs.value.filter { it.key != key }
        if (remaining.size == _tabs.value.size || remaining.isEmpty()) return
        val closing = _tabs.value.first { it.key == key }

        _tabs.value = remaining
        if (_activeKey.value == key) _activeKey.value = remaining.last().key
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

    /** Every agent, on the way out. */
    fun stopAll() {
        _tabs.value.forEach(::release)
        _tabs.value = emptyList()
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
    ): AgentTab {
        // The editor is bound before the agent starts, because the port travels
        // to the agent in its environment rather than being found on disk.
        val editor = editorFor(directory)
        val port = editor.start()

        val configured = panelConfig()?.let { arguments + backend.mcpConfig(it) } ?: arguments

        val session = agent.session(
            directory = directory,
            idePort = port,
            arguments = configured,
            script = backend.script,
        )

        val key = nextKey++
        val tab = AgentTab(key, sessionId, directory, label, backend, session, editor)
        _tabs.value = _tabs.value + tab
        _activeKey.value = key
        Log.i(TAG, "tab $key on $directory, session $sessionId, ide $port")
        return tab
    }

    private fun release(tab: AgentTab) {
        runCatching { tab.session.stop() }
        runCatching { tab.ide.stop() }
    }

    private companion object {
        const val TAG = "AgentTabs"

        /** What the app's own tab is called until a transcript names it. */
        const val HOME_LABEL = "home"
    }
}

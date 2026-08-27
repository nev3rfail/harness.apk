package apk.harness

import android.util.Log
import apk.harness.chats.Chat
import apk.harness.ide.IdeServer
import com.ghostty.android.terminal.TerminalSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
class AgentTab(
    val key: Long,
    /** The conversation this agent is writing, named by the app rather than found. */
    val sessionId: String,
    /** The working directory, which decides which project the conversation is filed under. */
    val directory: File,
    /** What the strip calls it. */
    val label: String,
    val session: TerminalSession,
    val ide: IdeServer,
    /** Cancelled when the tab closes; watches [session] for the live marker. */
    internal val watcher: Job,
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
    private val scope: CoroutineScope,
    private val agent: Agent,
    /** Builds an editor for a workspace. Started by [open], which needs its port. */
    private val editorFor: (File) -> IdeServer,
    /** The MCP config naming the app's panel server, or null when it has none. */
    private val panelConfig: () -> File?,
) {
    private var nextKey = 1L

    private val _tabs = MutableStateFlow<List<AgentTab>>(emptyList())
    val tabs: StateFlow<List<AgentTab>> = _tabs.asStateFlow()

    private val _activeKey = MutableStateFlow(0L)
    val activeKey: StateFlow<Long> = _activeKey.asStateFlow()

    /**
     * The conversations that have a running agent behind them.
     *
     * The app forked the process and holds the handle, so this is observed
     * rather than probed and there is no protocol involved.
     */
    private val _live = MutableStateFlow<Set<String>>(emptySet())
    val live: StateFlow<Set<String>> = _live.asStateFlow()

    val active: AgentTab? get() = _tabs.value.firstOrNull { it.key == _activeKey.value }

    /** True when a conversation already has a tab, whether or not it is showing. */
    fun tabFor(sessionId: String): AgentTab? =
        _tabs.value.firstOrNull { it.sessionId == sessionId }

    /**
     * The tab the app opens for itself, in the agent's home.
     *
     * No session id and no arguments: the agent picks up where it left off, the
     * way it does without any of this. The id it chooses is unknown until the
     * transcript exists, so this tab contributes nothing to the live markers --
     * which is honest, since the drawer cannot say which chat it is either.
     */
    fun openDefault(): AgentTab = add(
        directory = agent.home,
        sessionId = "",
        label = HOME_LABEL,
        arguments = emptyList(),
    )

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
        return add(
            directory = directory,
            sessionId = chat.sessionId,
            label = chat.label,
            arguments = listOf("--resume", chat.sessionId),
        )
    }

    /**
     * Starts a conversation in [directory], under an id this app chooses.
     *
     * Naming it here rather than reading it back afterwards is what lets the
     * drawer mark the row live from the moment the tab opens.
     */
    fun start(directory: File, label: String): AgentTab {
        val sessionId = UUID.randomUUID().toString()
        return add(
            directory = directory,
            sessionId = sessionId,
            label = label,
            arguments = listOf("--session-id", sessionId),
        )
    }

    fun select(key: Long) {
        if (_tabs.value.any { it.key == key }) _activeKey.value = key
    }

    /**
     * Closes a tab and the agent in it.
     *
     * The last tab does not close. There is no screen behind it: the terminal is
     * what the app is, and a strip of tabs over nothing is not a state worth
     * being able to reach.
     */
    fun close(key: Long) {
        val remaining = _tabs.value.filter { it.key != key }
        if (remaining.size == _tabs.value.size || remaining.isEmpty()) return
        val closing = _tabs.value.first { it.key == key }

        _tabs.value = remaining
        if (_activeKey.value == key) _activeKey.value = remaining.last().key
        release(closing)
    }

    /** Every agent, on the way out. */
    fun stopAll() {
        _tabs.value.forEach(::release)
        _tabs.value = emptyList()
        _live.value = emptySet()
    }

    private fun add(
        directory: File,
        sessionId: String,
        label: String,
        arguments: List<String>,
    ): AgentTab {
        // The editor is bound before the agent starts, because the port travels
        // to the agent in its environment rather than being found on disk.
        val editor = editorFor(directory)
        val port = editor.start()

        val configured = panelConfig()
            ?.let { arguments + listOf("--mcp-config", it.absolutePath) }
            ?: arguments

        val session = agent.session(
            directory = directory,
            idePort = port,
            arguments = configured,
        )

        val key = nextKey++
        val watcher = scope.launch {
            session.isRunning.collect { running ->
                if (sessionId.isEmpty()) return@collect
                _live.value =
                    if (running) _live.value + sessionId else _live.value - sessionId
            }
        }

        val tab = AgentTab(key, sessionId, directory, label, session, editor, watcher)
        _tabs.value = _tabs.value + tab
        _activeKey.value = key
        Log.i(TAG, "tab $key on $directory, session ${sessionId.ifEmpty { "unnamed" }}, ide $port")
        return tab
    }

    private fun release(tab: AgentTab) {
        tab.watcher.cancel()
        if (tab.sessionId.isNotEmpty()) _live.value = _live.value - tab.sessionId
        runCatching { tab.session.stop() }
        runCatching { tab.ide.stop() }
    }

    private companion object {
        const val TAG = "AgentTabs"
        const val HOME_LABEL = "home"
    }
}

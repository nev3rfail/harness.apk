package apk.harness

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import apk.harness.agents.BACKENDS
import apk.harness.agents.OpenTab
import apk.harness.agents.RunningSession
import apk.harness.agents.mergedProjects
import apk.harness.agents.withOpenTabs
import apk.harness.bootstrap.Bootstrap
import apk.harness.bootstrap.BootstrapProgress
import apk.harness.chats.Chat
import apk.harness.chats.Project
import apk.harness.ide.APP_NAME
import apk.harness.ide.IdeServer
import apk.harness.ide.NO_CHAT
import apk.harness.ide.McpEndpoint
import apk.harness.ide.PanelServer
import apk.harness.ide.Surface
import apk.harness.ide.Surfaces
import apk.harness.ide.Tools
import apk.harness.ide.label
import apk.harness.ui.BootstrapScreen
import apk.harness.ui.DrawerEdgeStrip
import apk.harness.ui.DrawerSide
import apk.harness.ui.FileTree
import apk.harness.ui.HarnessTheme
import apk.harness.ui.InputToolbar
import apk.harness.ui.SessionTree
import apk.harness.ui.SideDrawer
import apk.harness.ui.SurfacePanel
import apk.harness.ui.TerminalPane
import apk.harness.ui.documentFor
import com.ghostty.android.renderer.GhosttyGLSurfaceView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The harness.
 *
 * Terminals running agents, and an IDE each of them connects back to. An agent
 * draws its own interface in its terminal; anything it wants drawn properly it
 * asks the app for, and that arrives here as a [Surface] over the top.
 */
class MainActivity : ComponentActivity() {

    // Built once the userland is installed rather than in onCreate, so it is
    // absent while the install runs.
    private var tabs: AgentTabs? = null
    private lateinit var panels: PanelServer
    private lateinit var tools: Tools
    private val surfaces = Surfaces()

    /** Every terminal on screen, by its tab. Only one of them is visible. */
    private val views = mutableMapOf<Long, GhosttyGLSurfaceView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Started before anything else it protects.
        AgentService.start(this)

        val home = filesDir

        tools = Tools(
            surfaces = surfaces,
            openExternal = ::openExternally,
            // Raw bytes for a diff to line up against the proposed text; a
            // missing or unreadable file throws, which is the "no before text"
            // case a diff needs to detect rather than mask with a placeholder.
            readFile = { path -> File(path).readText() },
            // The same file as a document: fenced when it is source, or a
            // refusal string in place of content that should not be drawn --
            // binary, oversized, or unreadable. What a tree row shows.
            readDocument = { path -> documentFor(File(path)) },
        )

        // The panels the agent chooses for itself travel the other way in, as a
        // server it is configured with rather than an editor it attaches to.
        // One of these serves every tab: a phone has room for one panel.
        panels = PanelServer(
            workspace = home,
            endpoint = McpEndpoint(APP_NAME, tools::panelDefinitions, tools::call),
        )
        panels.start()

        val bootstrap = Bootstrap(this)

        enableEdgeToEdge()
        setContent {
            HarnessTheme {
                var progress by remember {
                    mutableStateOf<BootstrapProgress?>(
                        if (bootstrap.isInstalled()) BootstrapProgress.Done else null
                    )
                }
                // Bumped to run the install again. Every step is idempotent, so a
                // second attempt costs only what the first one did not finish.
                var attempt by remember { mutableStateOf(0) }
                LaunchedEffect(attempt) {
                    if (progress != BootstrapProgress.Done) bootstrap.install { progress = it }
                }
                if (progress == BootstrapProgress.Done) {
                    // Built here rather than in onCreate: session() probes for a
                    // userland once, so a session made before the install is a
                    // session that never sees it.
                    val holder = remember { openTabs(home) }
                    HarnessScreen(
                        tabs = holder,
                        surfaces = surfaces,
                        root = home,
                        agentHome = home,
                        // Zero at this boundary means "unasked": the renderer leaves the
                        // terminal library its own default rather than disabling history.
                        scrollbackBytes = TerminalSettings.scrollbackBytes(home) ?: 0L,
                        onViewCreated = { key, view -> views[key] = view },
                        onViewReleased = { key -> views.remove(key) },
                        openLink = { openExternally(it) },
                        copyText = { text -> runOnUiThread { copyToClipboard(text) } },
                        pasteText = ::clipboardAsInput,
                    )
                } else {
                    BootstrapScreen(
                        progress = progress,
                        onRetry = {
                            progress = null
                            attempt++
                        },
                        // Past the screen without an agent. The session falls
                        // back to a shell on its own when nothing is staged, so
                        // this needs to change nothing but the gate.
                        onSkip = { progress = BootstrapProgress.Done },
                    )
                }
            }
        }
    }

    /**
     * The tab holder, with the app's own agent already in it.
     *
     * Every tab gets an editor of its own, on a port of its own, because
     * discovery otherwise picks one lockfile out of the directory by matching
     * its workspace and taking the newest -- which with several servers is
     * several answers to one question.
     */
    private fun openTabs(home: File): AgentTabs = AgentTabs(
        agent = Agent(this),
        editorFor = { workspace, key ->
            IdeServer(
                workspace = workspace,
                // The agent finds an editor by reading lockfiles out of its own
                // config directory, so every server writes into the one home
                // every agent is given.
                lockDirectory = File(home, "$AGENT_CONFIG_DIRECTORY/ide"),
                endpoint = McpEndpoint(APP_NAME, tools::editorDefinitions, tools::call),
                // One editor to one tab, so every call it answers is this
                // chat's.
                owner = key,
            )
        },
        panelConfigFor = { key -> panels.configFor(key) },
        releasePanelConfig = { key -> panels.discard(key) },
    ).also {
        tabs = it
        it.openDefault()
    }

    override fun onPause() {
        super.onPause()
        showing()?.onPauseView()
    }

    override fun onResume() {
        super.onResume()
        showing()?.onResumeView()
    }

    /** The one terminal on screen, or null before there is one. */
    private fun showing(): GhosttyGLSurfaceView? =
        views.values.firstOrNull { it.visibility == View.VISIBLE }

    override fun onDestroy() {
        super.onDestroy()
        tabs?.stopAll()
        panels.stop()
        AgentService.stop(this)
    }

    /**
     * Puts text on the device clipboard. The system announces the copy itself
     * from Android 13 on, so nothing is drawn here.
     */
    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(APP_NAME, text))
    }

    /**
     * The clipboard as terminal input. Control characters are dropped rather
     * than typed: a pasted newline is Enter, so it would run whatever the paste
     * happens to contain, and nothing else in that range has any business
     * arriving from outside the terminal.
     *
     * ponytail: one line at a time. Multi-line pastes want bracketed paste,
     * which means asking the terminal whether the program turned it on.
     */
    private fun clipboardAsInput(): String {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        val text = clip.getItemAt(0).coerceToText(this).toString()
        return text.lines().joinToString(" ") { line ->
            line.filter { it.code in 0x20..0x7E || it.code > 0x7F }
        }
    }

    /**
     * Hands a URI to whatever on the device handles it. This is the embed story
     * for anything the app has no business drawing itself: a `geo:` link is a
     * map application, `tel:` is the dialer.
     */
    private fun openExternally(uri: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private companion object {
        /** Where the agent keeps its projects, its lockfiles and its settings. */
        const val AGENT_CONFIG_DIRECTORY = ".claude"
    }
}

@Composable
private fun HarnessScreen(
    tabs: AgentTabs,
    surfaces: Surfaces,
    root: File,
    agentHome: File,
    scrollbackBytes: Long,
    onViewCreated: (Long, GhosttyGLSurfaceView) -> Unit,
    onViewReleased: (Long) -> Unit,
    openLink: (String) -> Unit,
    copyText: (String) -> Unit,
    pasteText: () -> String,
) {
    // spike: with a `capture` file in the home, every byte the agent writes is
    // kept, so a screen that goes wrong can be replayed.
    val context = androidx.compose.ui.platform.LocalContext.current
    val capture = remember {
        val home = context.filesDir
        if (File(home, "capture").exists()) {
            java.io.FileOutputStream(File(home, "pty.log"), true).buffered()
        } else {
            null
        }
    }

    val open by tabs.tabs.collectAsState()
    val activeKey by tabs.activeKey.collectAsState()
    val active = open.firstOrNull { it.key == activeKey }

    // The terminal the toolbar types into: whichever one is showing.
    val views = remember { mutableStateMapOf<Long, GhosttyGLSurfaceView>() }
    val view = views[activeKey]

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    val surface by surfaces.visible.collectAsState()
    val documentOwner by surfaces.owner.collectAsState()
    val parked by surfaces.parked.collectAsState()
    val selection by surfaces.selection.collectAsState()

    // Where each chat's document was scrolled to. Here rather than in Surfaces
    // because nothing outside the composition reads it, and this composition
    // outlives every dialog it opens -- which is what a parked document needs,
    // since parking it destroys the panel that was drawing it.
    val offsets = remember { mutableStateMapOf<Long, Int>() }

    // The document on screen belongs to a chat, so it cannot stay over another
    // one. Switching parks it for its own chat, which draws that chat's band.
    LaunchedEffect(activeKey) { surfaces.switchTo(activeKey) }

    var filesOpen by remember { mutableStateOf(false) }
    var chatsOpen by remember { mutableStateOf(false) }
    var expandedFiles by remember { mutableStateOf(emptySet<String>()) }
    var expandedProjects by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // The terminal stays mounted underneath: a panel is a look at something,
        // not a change of screen, and the agent keeps running behind it.
        // The status bar inset lives here, so the terminal's first line is never
        // under the bar when the bar is showing.
        Column(modifier = Modifier.fillMaxSize().imePadding().statusBarsPadding()) {
            // The strips overlay the terminal's edges and are declared after
            // it, so a drag starting there reaches a strip rather than the
            // surface underneath.
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                // Every tab is composed; only the active one is visible. A tab
                // dropped from the composition would take its terminal with it
                // and leave its agent writing to a pty nobody reads.
                open.forEach { tab ->
                    key(tab.key) {
                        TerminalPane(
                            session = tab.session,
                            visible = tab.key == activeKey,
                            scrollbackBytes = scrollbackBytes,
                            capture = capture,
                            onCreated = { created ->
                                views[tab.key] = created
                                onViewCreated(tab.key, created)
                            },
                            onReleased = {
                                views.remove(tab.key)
                                onViewReleased(tab.key)
                            },
                            onModifiersConsumed = {
                                ctrlActive = false
                                altActive = false
                            },
                            openLink = openLink,
                            copyText = copyText,
                        )
                    }
                }
                DrawerEdgeStrip(
                    side = DrawerSide.Left,
                    onOpen = { chatsOpen = true },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                DrawerEdgeStrip(
                    side = DrawerSide.Right,
                    onOpen = { filesOpen = true },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                // The band for the chat being looked at, and only for that one:
                // another chat's parked document is that chat's to come back to.
                // In this Box rather than in the Column around it, so it sits
                // over the terminal's bottom edge and above the toolbar.
                parked[activeKey]?.let { put ->
                    DrawerEdgeStrip(
                        side = DrawerSide.Bottom,
                        onOpen = { surfaces.restore(activeKey) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        label = bandLabel(put),
                    )
                }
            }
            InputToolbar(
                onKey = { active?.session?.write(it) },
                onPaste = {
                    pasteText().takeIf { text -> text.isNotEmpty() }
                        ?.let { active?.session?.write(it) }
                },
                onShowKeyboard = { view?.showKeyboard() },
                onToggleCtrl = {
                    ctrlActive = !ctrlActive
                    view?.ctrlPending = ctrlActive
                },
                onToggleAlt = {
                    altActive = !altActive
                    view?.altPending = altActive
                },
                ctrlActive = ctrlActive,
                altActive = altActive,
            )
        }

        surface?.let { shown ->
            Dialog(
                onDismissRequest = surfaces::dismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                SurfacePanel(
                    surface = shown,
                    onDecide = { diff, decision -> surfaces.decide(diff, decision) },
                    onDismiss = surfaces::dismiss,
                    // Offered only for a document a chat owns: parking is
                    // coming back to it later, and the band that comes back
                    // from is drawn per chat.
                    onPark = if (documentOwner == NO_CHAT) null else surfaces::park,
                    // Keyed by the chat the document belongs to rather than by
                    // the chat on screen: a document a background agent showed
                    // parks for its own chat and comes back where that chat
                    // left it.
                    scroll = offsets[documentOwner] ?: 0,
                    onScroll = { offset -> offsets[documentOwner] = offset },
                    // Held in Surfaces rather than in the panel: the document is
                    // put away and talked about afterwards, and the panel that
                    // made the selection is gone by then.
                    selection = selection,
                    onSelect = surfaces::select,
                )
            }
        }

        // Drawers of their own rather than Surfaces. Surfaces holds one surface
        // and waits for a pending diff before replacing it, so routing a tree
        // through it would leave the operator browsing a file behind an
        // unanswered question.
        if (filesOpen) {
            SideDrawer(side = DrawerSide.Right, onClosed = { filesOpen = false }) { close ->
                FileTree(
                    root = root,
                    expanded = expandedFiles,
                    onToggle = { file ->
                        expandedFiles =
                            if (file.path in expandedFiles) expandedFiles - file.path
                            else expandedFiles + file.path
                    },
                    // A click handler runs on the main thread, and this reads up
                    // to a mebibyte. The panel opens when the document is built.
                    onPick = { file ->
                        scope.launch {
                            val document = withContext(Dispatchers.IO) { documentFor(file) }
                            // The chat on screen owns it. The operator opened it
                            // while looking at that conversation, which is the
                            // conversation the file is at hand for, and a
                            // document with no owner is one no agent is told
                            // about and no band can bring back.
                            surfaces.show(Surface.Document(file.path, document), activeKey)
                        }
                    },
                    onDismiss = close,
                )
            }
        }

        if (chatsOpen) {
            SideDrawer(side = DrawerSide.Left, onClosed = { chatsOpen = false }) { close ->
                // Read here rather than in the tree, so the tree draws what it
                // is given and the backends stay out of it. Both reads are
                // scoped to this branch: nothing is stat-ed behind a shut panel.
                val projects by produceState(emptyList<Project>(), agentHome) {
                    value = withContext(Dispatchers.IO) { mergedProjects(BACKENDS, agentHome) }
                }
                // Every tab is a row whether or not a transcript names it: a tab
                // with no row is one nothing can reach, switch to or close.
                val listed = remember(projects, open) {
                    withOpenTabs(
                        projects,
                        open.map {
                            OpenTab(it.sessionId, it.directory, it.label, it.backend.id)
                        },
                    )
                }

                // The conversation on screen is what the drawer is opened to
                // look away from, so the project holding it is open when the
                // drawer is. Added to what is expanded rather than replacing
                // it: a project opened by hand stays open.
                LaunchedEffect(listed, active?.sessionId) {
                    listed.firstOrNull { project ->
                        project.chats.any { it.sessionId == active?.sessionId }
                    }?.let { expandedProjects = expandedProjects + it.path }
                }

                // The replacement for this local lives in lifecycle-runtime-compose,
                // an artifact the app does not depend on for one composition local.
                @Suppress("DEPRECATION")
                val owner = LocalLifecycleOwner.current
                // Polled rather than watched: a process writes its own record
                // and there is nothing to subscribe to. Two seconds is slow
                // enough to cost nothing and quick enough that a dot follows
                // what the agent is doing.
                //
                // The poll follows the lifecycle rather than the composition: it
                // runs while the Activity is started, so a drawer left open
                // behind a locked screen reads again when the screen comes back
                // and holds the roster it last read until then.
                var running by remember { mutableStateOf(emptyMap<String, RunningSession>()) }
                LaunchedEffect(owner, agentHome) {
                    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            running = withContext(Dispatchers.IO) {
                                BACKENDS.flatMap { it.running(agentHome) }
                                    .associateBy { session -> session.sessionId }
                            }
                            delay(ROSTER_INTERVAL_MS)
                        }
                    }
                }

                SessionTree(
                    projects = listed,
                    running = running,
                    current = active?.sessionId,
                    openTabs = open.mapTo(mutableSetOf()) { it.sessionId },
                    // The last tab does not close, so no row offers to.
                    canClose = open.size > 1,
                    expanded = expandedProjects,
                    onToggle = { path ->
                        expandedProjects =
                            if (path in expandedProjects) expandedProjects - path
                            else expandedProjects + path
                    },
                    // Opening a tab binds a socket, seeds a trust answer and
                    // may stage the agent's home, none of which belongs on the
                    // thread the tap arrived on.
                    onOpenChat = { project: Project, chat: Chat ->
                        close()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                tabs.resume(chat, File(project.path))
                            }
                        }
                    },
                    // Continuing costs whatever is typed and not sent -- the
                    // line it types is appended to it, and the agent it starts
                    // instead takes the process holding it down. So the prompt
                    // is read first, and anything but an empty one is left for
                    // the operator to send or clear.
                    onContinueHere = { project: Project, chat: Chat ->
                        val prompt = view?.getRenderer()?.getViewportText()
                            ?.let { active?.backend?.promptText(it) }
                        when {
                            prompt == null -> Toast.makeText(
                                context,
                                "Cannot see the prompt. Scroll to the bottom and try again.",
                                Toast.LENGTH_LONG,
                            ).show()

                            prompt.isNotEmpty() -> Toast.makeText(
                                context,
                                "Unsent text in the prompt. Send it or clear it first.",
                                Toast.LENGTH_LONG,
                            ).show()

                            else -> {
                                close()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        tabs.continueHere(chat, File(project.path))
                                    }
                                }
                            }
                        }
                    },
                    // The drawer stays open: closing an agent is a thing done to
                    // a list, and the next row is usually the next tap.
                    onCloseChat = { chat: Chat -> tabs.closeSession(chat.sessionId) },
                    onNewChat = { project: Project ->
                        close()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                tabs.start(File(project.path), project.name)
                            }
                        }
                    },
                    onDismiss = close,
                )
            }
        }
    }

    // The terminal takes the keyboard when it arrives and when the tab changes,
    // so a tab switch leaves something to type into. It is also told to resume:
    // a terminal drawn for the first time in a frame another one is hidden in
    // renders and is not composited, and this is the same call the app makes on
    // its way back from the background, which is what puts such a terminal on
    // the screen.
    LaunchedEffect(view) {
        view?.onResumeView()
        view?.showKeyboard()
    }
}

/**
 * What the band says: the document's filename, and the selected lines beside it.
 *
 * One-based, because this is the one place the lines are drawn for a person
 * rather than sent to an agent, and it is also the only place the operator can
 * see what the agent has been told.
 */
private fun bandLabel(put: Surfaces.Parked): String {
    val name = put.document.path.substringAfterLast('/')
    val selection = put.selection ?: return name
    return "$name  " + selection.label()
}

/** How often the drawer rereads the roster while it is open. */
private const val ROSTER_INTERVAL_MS = 2_000L

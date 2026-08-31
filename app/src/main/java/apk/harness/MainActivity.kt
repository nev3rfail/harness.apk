package apk.harness

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
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
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStateAtLeast
import apk.harness.agents.BACKENDS
import apk.harness.agents.OpenTab
import apk.harness.agents.RunningSession
import apk.harness.agents.mergedProjects
import apk.harness.agents.withOpenTabs
import apk.harness.bootstrap.Bootstrap
import apk.harness.bootstrap.BootstrapProgress
import apk.harness.bootstrap.bothSpellings
import apk.harness.chats.Chat
import apk.harness.chats.Project
import apk.harness.ide.APP_NAME
import apk.harness.ide.IdeServer
import apk.harness.ide.NO_CHAT
import apk.harness.ide.McpEndpoint
import apk.harness.ide.PanelServer
import apk.harness.ide.Report
import apk.harness.ide.Surface
import apk.harness.ide.Surfaces
import apk.harness.ide.Tools
import apk.harness.ide.label
import apk.harness.ide.reportFor
import apk.harness.intents.Action
import apk.harness.intents.Handoff
import apk.harness.intents.appFor
import apk.harness.intents.fire
import apk.harness.ui.BootstrapScreen
import apk.harness.ui.DrawerEdgeStrip
import apk.harness.ui.DrawerSide
import apk.harness.ui.FileTree
import apk.harness.ui.HarnessTheme
import apk.harness.ui.Header
import apk.harness.ui.InputToolbar
import apk.harness.ui.SessionTree
import apk.harness.ui.SideDrawer
import apk.harness.ui.SurfacePanel
import apk.harness.ui.TerminalPane
import apk.harness.ui.UndoWindowMillis
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
        // Nothing standing in the shade outlives the process that raised it.
        // Every agent is a child of this app, so an ask whose agent has gone
        // points at a conversation that is not there, and building the tabs is
        // already the event that replaces every one of them.
        AgentService.clearAsks(this)

        // The stage's spellings rather than the platform's, so the paths the
        // agent is told about and the paths the app compares against are one
        // set. The tab holder builds an agent of its own; this one is here for
        // the directories, which every agent of this app agrees on.
        val agent = Agent(this)
        val home = agent.home

        tools = Tools(
            surfaces = surfaces,
            // Resolved before the operator is asked, and fired only after. Both
            // are the activity's because both need a Context; everything that
            // decides whether either may happen is off in `apk.harness.intents`.
            describeHandoff = { handoff -> appFor(this, handoff) },
            fireHandoff = { handoff -> fire(this, handoff) },
            // The whole of what a handoff may carry out of the app: the two
            // directories the agent can write, each under both spellings, since
            // the agent has been taught one of them and Android reports the
            // other.
            writable = bothSpellings(this, agent.home) + bothSpellings(this, agent.tmp),
            // Raw bytes for a diff to line up against the proposed text; a
            // missing or unreadable file throws, which is the "no before text"
            // case a diff needs to detect rather than mask with a placeholder.
            readFile = { path -> File(path).readText() },
            // The same file as a document: fenced when it is source, or a
            // description in place of content that should not be drawn --
            // binary, oversized, or unreadable. What a tree row shows, and it
            // carries the offset between its lines and the file's.
            readDocument = { path -> documentFor(File(path)) },
            // The one Android thing an ask needs: a chat resolved to a tab, a
            // title cut to a line, and a post. What is worth asking, and whether
            // to ask at all, is decided in `Tools`.
            callOperator = ::askOperator,
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
                        agentHome = home,
                        // Zero at this boundary means "unasked": the renderer leaves the
                        // terminal library its own default rather than disabling history.
                        scrollbackBytes = TerminalSettings.scrollbackBytes(home) ?: 0L,
                        onViewCreated = { key, view -> views[key] = view },
                        onViewReleased = { key -> views.remove(key) },
                        openLink = ::follow,
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
        forgetParked = { key -> surfaces.forget(key) },
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

    /**
     * A tap on a notification, arriving at the instance already running.
     *
     * The extra is a session id rather than a tab key: keys are counted from one
     * per process and mean nothing across a restart, and a conversation can be
     * moved to a different tab while that tab keeps its key. A session no tab
     * holds is left alone -- the agent that asked was a child process of this app
     * and died with it, so there is no conversation to come back to, and opening
     * its transcript would start a fresh agent, which the drawer already offers
     * deliberately.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(AgentService.EXTRA_SESSION)?.let { tabs?.show(it) }
    }

    /** The one terminal on screen, or null before there is one. */
    private fun showing(): GhosttyGLSurfaceView? =
        views.values.firstOrNull { it.visibility == View.VISIBLE }

    override fun onDestroy() {
        super.onDestroy()
        tabs?.stopAll()
        panels.stop()
        AgentService.clearAsks(this)
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
     * Follows something already on screen out of the app: a hyperlink in the
     * terminal, a source mark on a card.
     *
     * No confirmation. The tap is the person acting, on something they can see,
     * and a `view` on a bare URI is the one fire that needs nobody's permission
     * anyway.
     */
    private fun follow(uri: String) {
        fire(this, Handoff(Action.View, uri = uri))
    }

    /**
     * Raises the ask for the chat [owner] and answers with the name it went out
     * under.
     *
     * [NO_CHAT] is a session that found the app-wide config rather than a chat's
     * own, so it is named for the app and its tap carries no conversation. A key
     * with no tab is a chat that closed while its call was in flight, and gets no
     * notification: the tap would land nowhere.
     *
     * Called on whichever thread the tool call arrived on. Reading a `StateFlow`
     * and posting a notification are both safe off the main thread.
     */
    private fun askOperator(owner: Long, message: String): String? {
        if (owner == NO_CHAT) {
            AgentService.ask(this, owner, APP_NAME, message, null)
            return APP_NAME
        }
        val tab = tabs?.tabs?.value?.firstOrNull { it.key == owner } ?: return null
        // A chat nobody named is labelled with one of its own prompts, so the
        // title is cut here rather than trusted to be short. It is the one thing
        // that tells three of these apart, which is why the agent supplies no
        // title of its own to compete with it.
        val title = tab.label.lineSequence().first().take(TITLE_LIMIT)
        AgentService.ask(this, owner, title, message, tab.sessionId)
        return title
    }

    private companion object {
        /** Where the agent keeps its projects, its lockfiles and its settings. */
        const val AGENT_CONFIG_DIRECTORY = ".claude"

        /** What a notification draws on its first line. */
        const val TITLE_LIMIT = 40
    }
}

@Composable
private fun HarnessScreen(
    tabs: AgentTabs,
    surfaces: Surfaces,
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
        if (File(agentHome, "capture").exists()) {
            java.io.FileOutputStream(File(agentHome, "pty.log"), true).buffered()
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

    // Where each document was scrolled to, keyed by the chat it belongs to and
    // the document itself: a chat shows many documents over its life and they
    // are not the same length, so where one was left is not where another
    // starts. Here rather than in Surfaces because nothing outside the
    // composition reads it, and this composition outlives every dialog it opens
    // -- which is what a parked document needs, since parking it destroys the
    // panel that was drawing it.
    val offsets = remember { mutableStateMapOf<Pair<Long, String>, Int>() }

    // The document on screen belongs to a chat, so it cannot stay over another
    // one. Switching parks it for its own chat, which draws that chat's band.
    // Arriving at a chat also answers its ask, however the operator got here: a
    // person reading the conversation is the whole of what the ask wanted.
    LaunchedEffect(activeKey) {
        surfaces.switchTo(activeKey)
        AgentService.clearAsk(context, activeKey)
    }

    // The undo window. It runs where the band is drawn, in a composition that
    // outlives every dialog it opens, and it is keyed on the entry, so a second
    // close starts a second window. A chat switch takes the entry away and ends
    // it, which is why Surfaces needs no scope of its own for this.
    val closing = parked[activeKey]?.takeIf { it.closing }
    LaunchedEffect(activeKey, closing) {
        if (closing == null) return@LaunchedEffect
        delay(UndoWindowMillis)
        surfaces.forget(activeKey)
    }

    // What each chat's agent was last told. The notification mirrors the state,
    // so what decides whether one goes out is whether it would say something
    // other than what that chat already has.
    val told = remember { mutableMapOf<Long, Report>() }
    // The chat last looked at, so a switch to another one can be recognised.
    var watched by remember { mutableStateOf(NO_CHAT) }
    LaunchedEffect(surface, selection, documentOwner, parked, open, activeKey) {
        val switched = watched != activeKey
        watched = activeKey
        // Every open chat, rather than the one on screen: a document a
        // background agent showed belongs to that agent, and it is the one told
        // about it. A chat is the unit here, so NO_CHAT is left out by having no
        // tab of its own -- a surface the app put up itself has no agent to tell.
        open.forEach { tab ->
            val report = reportFor(
                key = tab.key,
                visible = surface,
                owner = documentOwner,
                selection = selection,
                parked = parked[tab.key],
            )
            if (report == null) return@forEach
            // A chat arriving on screen is told its own state again: it is the
            // one party that has just changed what it is looking at.
            if (report == told[tab.key] && !(switched && tab.key == activeKey)) return@forEach
            // Recorded only once it has gone out. A report kept as told and then
            // dropped is never retried, because the guard above suppresses the
            // next identical one.
            if (announce(tabs, tab.key, report)) told[tab.key] = report
        }
    }

    var filesOpen by remember { mutableStateOf(false) }
    var chatsOpen by remember { mutableStateOf(false) }
    var expandedFiles by remember { mutableStateOf(emptySet<String>()) }
    // Bumped when a file is copied in. The tree reads its rows off the root and
    // the open set, and a file arriving changes neither, so this is what makes a
    // copy show up in a drawer that stays open.
    var filesRevision by remember { mutableStateOf(0) }
    var expandedProjects by remember { mutableStateOf(emptySet<String>()) }
    // Beside expansion rather than inside the tree: the drawer's composition is
    // disposed when the drawer closes, and revealing lasts as long as expansion
    // does, which is longer. Two sets rather than one carrying encoded entries,
    // because `path in expandedProjects` is asked in three places and an encoded
    // member would make every one of them wrong.
    var revealedProjects by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    // Both pickers are the platform's, and both are remembered here rather than
    // inside a drawer: a drawer's composition goes away while its picker is on
    // screen, and a launcher that goes with it has nothing to deliver to.
    //
    // The contracts are remembered too. A launcher registers against the contract
    // it is handed, so a fresh instance every recomposition is a fresh
    // registration every recomposition, and this screen recomposes on a timer.
    val treeContract = remember { ActivityResultContracts.OpenDocumentTree() }
    val documentsContract = remember { ActivityResultContracts.OpenMultipleDocuments() }

    // A folder becomes a project. The path is proved before a tab is made, and a
    // refusal changes nothing that is running.
    val folderPicker = rememberLauncherForActivityResult(treeContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val picked = withContext(Dispatchers.IO) { pickedFolder(uri) }) {
                is Picked.Refused ->
                    Toast.makeText(context, picked.reason, Toast.LENGTH_LONG).show()

                is Picked.Folder -> {
                    val directory = File(picked.path)
                    // Starting an agent binds a socket and may stage the agent's
                    // home, neither of which belongs on the thread a result
                    // arrives on.
                    withContext(Dispatchers.IO) { tabs.start(directory, directory.name) }
                }
            }
        }
    }

    // What a folder outside the app needs to be worth opening. Without it the app
    // writes into shared storage and reads back only what it wrote, so a folder
    // someone else filled reads as empty.
    //
    // Asked for on the way to the picker rather than at launch, most sessions
    // never leaving the agent's own home. An agent forked before the grant keeps
    // the view it was forked with, which costs nothing: what follows the grant is
    // a new agent in a new tab.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // The platform is asked again rather than the result being read. A
        // request the operator never answered -- a back press, a system dialog
        // taking the screen -- arrives as an empty answer, and an empty answer
        // is one every permission in it passes.
        when {
            holdsStorage(context) ->
                if (runCatching { folderPicker.launch(null) }.isFailure) {
                    Toast.makeText(context, NO_PICKER, Toast.LENGTH_LONG).show()
                }

            // Refused twice is refused for good: the platform stops drawing the
            // dialog, so a control that only asks again is one that does
            // nothing. Its own settings screen is where the answer can still be
            // changed.
            !asksAgain(context) ->
                if (runCatching { context.startActivity(appSettings(context)) }.isFailure) {
                    Toast.makeText(context, NO_STORAGE, Toast.LENGTH_LONG).show()
                }

            else -> Toast.makeText(context, NO_STORAGE, Toast.LENGTH_LONG).show()
        }
    }

    // Files are copied into the project the drawer is showing, and named in the
    // prompt so that nobody has to type them.
    val filePicker = rememberLauncherForActivityResult(documentsContract) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val destination = active?.directory ?: agentHome
        val session = active?.session
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                copyInto(context.contentResolver, uris, destination)
            }
            // Whatever landed, the tree is stale.
            filesRevision++
            if (copied.isEmpty()) {
                Toast.makeText(context, NOTHING_COPIED, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Appended to whatever the prompt holds, with no carriage return.
            // Nothing is sent and nothing is lost, which is why this asks the
            // prompt no questions first.
            session?.write(copied.joinToString(" ", postfix = " ") { "@" + it })
        }
    }

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
                        // A tap answers the same way a pull up does on both
                        // faces: a parked band restores, a closing band uncloses.
                        onOpen = { surfaces.restore(activeKey) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        label = bandLabel(put),
                        fill = if (put.closing) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                        // One lambda that reads the face, rather than one lambda
                        // per face: a gesture modifier keyed on what it is
                        // attached to keeps the block it was given first.
                        onClose = {
                            if (parked[activeKey]?.closing == true) surfaces.forget(activeKey)
                            else surfaces.close(activeKey)
                        },
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
            // Which document's scroll offset this panel reads and writes.
            val place = documentOwner to (shown as? Surface.Document)?.path.orEmpty()
            val document = shown as? Surface.Document
            // Closing a document keeps it for a few seconds behind a band, so a
            // wrong tap can be undone. Anything else is discarded outright:
            // there is nothing to come back to and a question left waiting is
            // answered by going away.
            val leave = {
                if (document != null && documentOwner != NO_CHAT) surfaces.close(documentOwner)
                else surfaces.dismiss()
            }
            Dialog(
                onDismissRequest = leave,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    // A document takes back for itself: back is a put it away,
                    // not a throw it away, and the dialog's own handling cannot
                    // tell one from the other -- an outside tap reaches the same
                    // callback, and the two have to do different things.
                    //
                    // An outside tap on a document does nothing at all. The
                    // panel fills the screen, so outside is a sliver at its
                    // edge, and a stray touch there dropping a held selection is
                    // the worst trade in the panel.
                    //
                    // A diff and a handoff keep both. Each is a question an
                    // agent is blocked on, and going away is itself the safe
                    // answer.
                    dismissOnBackPress = document == null,
                    dismissOnClickOutside = document == null,
                ),
            ) {
                if (document != null) {
                    // A document a chat owns parks, which is what the chevron in
                    // the header does. One nobody owns has nowhere to park -- the
                    // band is drawn from the entry for the chat on screen -- so
                    // back does what the header does for it instead, and closes.
                    BackHandler {
                        if (documentOwner == NO_CHAT) surfaces.dismiss() else surfaces.park()
                    }
                }
                SurfacePanel(
                    surface = shown,
                    onDecide = { diff, decision -> surfaces.decide(diff, decision) },
                    // A handoff answers with a boolean rather than a
                    // DiffDecision, because the fire either happens or does not.
                    onAnswer = { asked, allowed -> surfaces.answer(asked, allowed) },
                    onDismiss = leave,
                    // Offered only for a document a chat owns: parking is
                    // coming back to it later, and the band that comes back
                    // from is drawn per chat.
                    onPark = if (documentOwner == NO_CHAT) null else surfaces::park,
                    // Keyed by the chat the document belongs to rather than by
                    // the chat on screen: a document a background agent showed
                    // parks for its own chat and comes back where that chat
                    // left it.
                    scroll = offsets[place] ?: 0,
                    onScroll = { offset -> offsets[place] = offset },
                    // Held in Surfaces rather than in the panel: the document is
                    // put away and talked about afterwards, and the panel that
                    // made the selection is gone by then.
                    selection = selection,
                    onSelect = surfaces::select,
                    // The same way out of the app a terminal link takes. A
                    // document's cells cite sources, and a citation nobody can
                    // follow is a string.
                    openExternal = openLink,
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
                    // The tree follows the chat on screen: the active tab's
                    // working directory is the directory the conversation is
                    // filed under. With no active tab there is no working
                    // directory to follow, which is the state at boot before the
                    // first tab is made.
                    root = active?.directory ?: agentHome,
                    expanded = expandedFiles,
                    revision = filesRevision,
                    onToggle = { file ->
                        expandedFiles =
                            if (file.path in expandedFiles) expandedFiles - file.path
                            else expandedFiles + file.path
                    },
                    // A click handler runs on the main thread, and this reads up
                    // to a mebibyte. The panel opens when the document is built.
                    onPick = { file ->
                        scope.launch {
                            val rendered = withContext(Dispatchers.IO) { documentFor(file) }
                            // The chat on screen owns it. The operator opened it
                            // while looking at that conversation, which is the
                            // conversation the file is at hand for, and a
                            // document with no owner is one no agent is told
                            // about and no band can bring back.
                            surfaces.show(
                                Surface.Document(
                                    file.path,
                                    rendered.markdown,
                                    rendered.lineOffset,
                                ),
                                activeKey,
                            )
                        }
                    },
                    // The drawer stays open: the files land in the tree it is
                    // showing, and watching them arrive is the confirmation.
                    // Every kind, because what is worth having at hand is the
                    // operator's judgement rather than a filter's.
                    // A device with no picker is one this can only apologise
                    // for, which is better than a header tap that ends the app.
                    onAdd = {
                        if (runCatching { filePicker.launch(arrayOf("*/*")) }.isFailure) {
                            Toast.makeText(context, NO_PICKER, Toast.LENGTH_LONG).show()
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
                //
                // Null while the read is outstanding, which is a different thing
                // from an empty list. An empty list is a home holding no
                // history, and `withOpenTabs` turns one into a row for every
                // open tab, so a list drawn before it is read names a project
                // and a conversation out of nothing read.
                val projects by produceState<List<Project>?>(null, agentHome) {
                    value = withContext(Dispatchers.IO) { mergedProjects(BACKENDS, agentHome) }
                }
                // Every tab is a row whether or not a transcript names it: a tab
                // with no row is one nothing can reach, switch to or close.
                // Null carries through: there is nothing to add a tab's row to
                // until there is a list.
                val listed = remember(projects, open) {
                    projects?.let { read ->
                        withOpenTabs(
                            read,
                            open.map {
                                OpenTab(it.sessionId, it.directory, it.label, it.backend.id)
                            },
                        )
                    }
                }

                // The conversation on screen is what the drawer is opened to
                // look away from, so the project holding it is open when the
                // drawer is. Added to what is expanded rather than replacing
                // it: a project opened by hand stays open.
                LaunchedEffect(listed, active?.sessionId) {
                    listed?.firstOrNull { project ->
                        project.chats.any { it.sessionId == active?.sessionId }
                    }?.let { expandedProjects = expandedProjects + it.key }
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

                // An outstanding read is drawn as a wait rather than as a
                // tree of nothing. The panel around it is the tree's own --
                // its surface, its inset and its way out -- so the drawer is
                // the same drawer before and after the list arrives. The
                // header carries no subtitle: that line counts projects and
                // chats, and there is nothing yet to count.
                if (listed == null) {
                    MaterialSurface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                            Header(title = "Chats", subtitle = null, onDismiss = close)
                            HorizontalDivider()
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    return@SideDrawer
                }

                SessionTree(
                    projects = listed,
                    // The agent's HOME is the app's files directory, under both
                    // spellings, so a row folds whichever one its transcript
                    // recorded.
                    homes = remember(agentHome) { bothSpellings(context, agentHome) },
                    running = running,
                    current = active?.sessionId,
                    openTabs = open.mapTo(mutableSetOf()) { it.sessionId },
                    // The last tab does not close, so no row offers to.
                    canClose = open.size > 1,
                    expanded = expandedProjects,
                    revealed = revealedProjects,
                    onToggle = { path ->
                        if (path in expandedProjects) {
                            expandedProjects = expandedProjects - path
                            // Collapsing is how a reveal is put back, so a
                            // project reopened comes back capped -- including
                            // one the effect above force-opens, which only ever
                            // adds to what is expanded.
                            revealedProjects = revealedProjects - path
                        } else {
                            expandedProjects = expandedProjects + path
                        }
                    },
                    onReveal = { path -> revealedProjects = revealedProjects + path },
                    // Opening a tab binds a socket, seeds a trust answer and
                    // may stage the agent's home, none of which belongs on the
                    // thread the tap arrived on. A chat whose transcript names
                    // no directory opens in the agent's home: resuming resolves
                    // a conversation by its id wherever the transcript is
                    // filed, and the home is a directory that exists.
                    onOpenChat = { project: Project, chat: Chat ->
                        close()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                tabs.resume(chat, File(project.path ?: agentHome.path))
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
                                        tabs.continueHere(
                                            chat,
                                            File(project.path ?: agentHome.path),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    // The drawer stays open: closing an agent is a thing done to
                    // a list, and the next row is usually the next tap.
                    onCloseChat = { chat: Chat -> tabs.closeSession(chat.sessionId) },
                    onNewChat = { project: Project ->
                        // A project naming no directory draws no way to start a
                        // conversation in it, so this is a guard rather than a
                        // path anyone takes.
                        val directory = project.path ?: return@SessionTree
                        close()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                tabs.start(File(directory), project.name)
                            }
                        }
                    },
                    // The drawer closes on the way out, as it does for every
                    // other control that puts a conversation on the screen.
                    onOpenFolder = {
                        close()
                        val launch =
                            if (holdsStorage(context)) ({ folderPicker.launch(null) })
                            else ({ storagePermission.launch(STORAGE) })
                        if (runCatching { launch() }.isFailure) {
                            Toast.makeText(context, NO_PICKER, Toast.LENGTH_LONG).show()
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
    //
    // The resume waits for the window, because a tab can be made while another
    // activity is in front -- a picker is one -- and a terminal told to resume
    // behind that activity is told while there is no surface to resume onto.
    // Waiting means the call lands whichever order the two arrive in, and it is
    // a wait rather than a repeat: the Activity resumes the terminal on screen
    // itself, and a keyboard raised on every return from every other app is one
    // nobody asked for.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(view, lifecycle) {
        lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
            view?.onResumeView()
            view?.showKeyboard()
        }
    }
}

/**
 * Tells one chat's agent what the app believes about its document, and answers
 * whether the report reached it.
 *
 * Every notification goes to exactly one agent: the operator is talking to the
 * terminal they can see, and one document announced to four agents is three
 * agents told about a file they never showed. [owner] is a tab's key, so a chat
 * that has gone finds no editor and is sent nothing.
 *
 * The lines travel as they are held, zero-based and inclusive, because that is
 * what the notification speaks.
 */
private fun announce(tabs: AgentTabs, owner: Long, report: Report): Boolean {
    val ide = tabs.tabs.value.firstOrNull { it.key == owner }?.ide ?: return false
    val lines = report.lines
    val text = report.text
    return if (lines == null || text == null) ide.reportSelection(report.path)
    else ide.reportSelection(report.path, lines.first, lines.last, text)
}

/**
 * What the band says: the document's filename, and the selected lines beside it.
 *
 * One-based, because this is the one place the lines are drawn for a person
 * rather than sent to an agent, and it is also the only place the operator can
 * see what the agent has been told. They are the file's lines for the same
 * reason: the band and the wire have to agree, so the document's own offset is
 * applied here too. A document that only describes a file holds no line of it
 * and shows its name alone.
 *
 * A closing band names what the gesture does instead. The document is closed and
 * the range has been retracted, so there is nothing to read back.
 */
private fun bandLabel(put: Surfaces.Parked): String {
    val name = put.document.path.substringAfterLast('/')
    if (put.closing) return "Pull up to unclose  $name"
    val selection = put.selection ?: return name
    val label = selection.label(put.document.lineOffset) ?: return name
    return "$name  " + label
}

/** How often the drawer rereads the roster while it is open. */
private const val ROSTER_INTERVAL_MS = 2_000L

/**
 * The folder [uri] names, or a refusal saying why it names none.
 *
 * The picker answers with a document and the agent takes a working directory, so
 * the id is read for a path and that path is proved. A refusal carries the step
 * it failed at, because the operator picked something and is owed an account of
 * what became of it.
 *
 * The proof is contained in the root the id named rather than in shared storage
 * at large, so a folder reached through a link into another volume is refused
 * along with one reached into somewhere that is not storage at all.
 */
private fun pickedFolder(uri: Uri): Picked {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return Picked.Refused("That picker did not name a folder.")
    val primary = Environment.getExternalStorageDirectory().path
    val authority = uri.authority.orEmpty()

    val root = documentRoot(authority, id, primary) ?: return Picked.Refused(
        if (authority == EXTERNAL_STORAGE) "That picker named a folder this cannot read."
        else "$authority holds no folder on this device.",
    )
    val path = documentPath(authority, id, primary)
        ?: return Picked.Refused("That folder leads outside $root.")
    return openFolder(path, root)
}

/**
 * [uris] copied into [directory], answering the names they took there.
 *
 * Nothing is overwritten. A name is taken by creating the file rather than by
 * asking whether it exists, so two copies running at once cannot both decide
 * that one name is free -- the drawer stays open while a copy runs, and starting
 * a second is a tap away.
 *
 * The bytes go to a name of this attempt's own and are moved onto the claimed
 * one when they are all there, so a copy that stops partway leaves nothing for
 * the agent to read as a file.
 *
 * A document that cannot be read is skipped rather than ending the rest. The
 * answer names what arrived, which is what the operator is told about.
 */
private fun copyInto(
    resolver: ContentResolver,
    uris: List<Uri>,
    directory: File,
): List<String> {
    val copied = mutableListOf<String>()
    for (uri in uris) {
        val name = freeName(documentName(resolver, uri)) {
            !runCatching { File(directory, it).createNewFile() }.getOrDefault(false)
        }
        val partial = File(directory, "$name$PARTIAL-${System.nanoTime()}")
        val written = runCatching {
            resolver.openInputStream(uri)?.use { source ->
                partial.outputStream().use(source::copyTo)
                true
            } ?: false
        }.getOrDefault(false)

        // Onto the claimed name, which is an empty file this made and holds.
        if (written && partial.renameTo(File(directory, name))) copied += name
        else runCatching { partial.delete(); File(directory, name).delete() }
    }
    return copied
}

/**
 * What [uri] is called, as a name a directory can hold.
 *
 * A provider names its own documents, and one that names none leaves the last
 * part of the URI, which is the only other thing that stands for the document.
 * Either way it is another app's text, so it is made a plain name before it
 * decides where any bytes land.
 */
private fun documentName(resolver: ContentResolver, uri: Uri): String {
    val offered = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row ->
            if (row.moveToFirst()) row.getString(0) else null
        }
    }.getOrNull()

    return plainName(offered ?: uri.lastPathSegment.orEmpty())
}

/** What a file being copied is called until all of it is there. */
private const val PARTIAL = ".harness-part"

/**
 * True when the app may read and write shared storage as one filesystem.
 *
 * Both, because a project is read from and written to, and the platform hands
 * out the two views separately.
 *
 * Asked through the checker rather than of the permission alone, because the two
 * can disagree: an app operation turned off leaves the permission granted and
 * every read empty, which is the state this control exists to keep out of.
 */
private fun holdsStorage(context: Context): Boolean = STORAGE.all {
    PermissionChecker.checkSelfPermission(context, it) == PermissionChecker.PERMISSION_GRANTED
}

/**
 * True when the platform will still draw a dialog for these.
 *
 * A permission refused twice is refused for good, and the request that follows
 * returns without asking anyone. False says the operator has to be sent
 * somewhere they can still answer.
 */
private fun asksAgain(context: Context): Boolean {
    val activity = context as? Activity ?: return true
    return STORAGE.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

/** This app's own page in the platform's settings. */
private fun appSettings(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** What a project outside the agent's own home needs to be a project. */
private val STORAGE = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
)

/** What the operator is told when nothing chosen could be read. */
private const val NOTHING_COPIED = "Nothing was copied."

/** What the operator is told when the platform offers no picker to open. */
private const val NO_PICKER = "This device has no file picker."

/**
 * What the operator is told when a folder is picked with nothing to read it.
 *
 * It names where the answer can be changed, because a permission refused twice
 * is one the platform stops asking about, and the dialog not appearing is
 * otherwise the whole of what the control does.
 */
private const val NO_STORAGE =
    "Without access to storage a folder outside the app reads as empty. " +
        "Android's settings for this app is where that is turned on."

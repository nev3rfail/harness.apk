package apk.harness

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import apk.harness.ide.DiffDecision
import apk.harness.ide.APP_NAME
import apk.harness.ide.IdeServer
import apk.harness.ide.McpEndpoint
import apk.harness.ide.PanelServer
import apk.harness.ide.Surface
import apk.harness.ide.Surfaces
import apk.harness.ide.Tools
import apk.harness.ui.FileDrawer
import apk.harness.ui.FileTree
import apk.harness.ui.HarnessTheme
import apk.harness.ui.InputToolbar
import apk.harness.ui.SurfacePanel
import apk.harness.ui.documentFor
import com.ghostty.android.renderer.GhosttyGLSurfaceView
import com.ghostty.android.renderer.TerminalEventListener
import com.ghostty.android.terminal.TerminalSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The harness.
 *
 * A terminal running the agent, and an IDE the agent connects back to. The agent
 * draws its own interface in the terminal; anything it wants drawn properly it
 * asks the app for, and that arrives here as a [Surface] over the top.
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: TerminalSession
    private lateinit var ide: IdeServer
    private lateinit var panels: PanelServer
    private val surfaces = Surfaces()
    private var surfaceView: GhosttyGLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Started before anything else it protects.
        AgentService.start(this)

        val home = filesDir

        val tools = Tools(
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

        // The agent finds an editor by reading lockfiles out of its own config
        // directory, so the server has to write into the home the agent is given.
        ide = IdeServer(
            workspace = home,
            lockDirectory = File(home, ".claude/ide"),
            endpoint = McpEndpoint(APP_NAME, tools::editorDefinitions, tools::call),
        )
        ide.start()

        // The panels the agent chooses for itself travel the other way in, as a
        // server it is configured with rather than an editor it attaches to.
        panels = PanelServer(
            workspace = home,
            endpoint = McpEndpoint(APP_NAME, tools::panelDefinitions, tools::call),
        )
        panels.start()

        session = Agent(this).session()

        enableEdgeToEdge()
        setContent {
            HarnessTheme {
                HarnessScreen(
                    session = session,
                    surfaces = surfaces,
                    root = home,
                    onSurfaceViewCreated = { surfaceView = it },
                    openLink = { openExternally(it) },
                    copyText = { text -> runOnUiThread { copyToClipboard(text) } },
                    pasteText = ::clipboardAsInput,
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView?.onPauseView()
    }

    override fun onResume() {
        super.onResume()
        surfaceView?.onResumeView()
    }

    override fun onDestroy() {
        super.onDestroy()
        session.stop()
        ide.stop()
        panels.stop()
        AgentService.stop(this)
    }

    /**
     * Hands a URI to whatever on the device handles it. This is the embed story
     * for anything the app has no business drawing itself: a `geo:` link is a
     * map application, `tel:` is the dialer.
     */
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
}

@Composable
private fun HarnessScreen(
    session: TerminalSession,
    surfaces: Surfaces,
    root: File,
    onSurfaceViewCreated: (GhosttyGLSurfaceView) -> Unit,
    openLink: (String) -> Unit,
    copyText: (String) -> Unit,
    pasteText: () -> String,
) {
    var view by remember { mutableStateOf<GhosttyGLSurfaceView?>(null) }
    // spike: with a `capture` file in the home, every byte the agent writes is
    // kept, so a screen that goes wrong can be replayed.
    val context = androidx.compose.ui.platform.LocalContext.current
    val capture = remember {
        val home = context.filesDir
        if (java.io.File(home, "capture").exists()) {
            java.io.FileOutputStream(java.io.File(home, "pty.log"), true).buffered()
        } else {
            null
        }
    }

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    val surface by surfaces.visible.collectAsState()

    var drawerOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // The terminal stays mounted underneath: a panel is a look at something,
        // not a change of screen, and the agent keeps running behind it.
        // The status bar inset lives here, so the terminal's first line is never
        // under the bar when the bar is showing.
        Column(modifier = Modifier.fillMaxSize().imePadding().statusBarsPadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f),
                factory = { context ->
                    GhosttyGLSurfaceView(context).also { created ->
                        view = created
                        onSurfaceViewCreated(created)
                        created.onModifiersConsumed = {
                            ctrlActive = false
                            altActive = false
                        }
                        created.setEventListener(object : TerminalEventListener {
                            override fun onSurfaceReady(cols: Int, rows: Int) {
                                if (session.isRunning.value) {
                                    session.resize(cols, rows)
                                    return
                                }
                                session.start(
                                    cols = cols,
                                    rows = rows,
                                    onOutput = { bytes, length ->
                                        capture?.run { write(bytes, 0, length); flush() }
                                        created.getRenderer().processInput(bytes, length)
                                        // The agent asks for the clipboard with
                                        // OSC 52, which arrives in its output
                                        // rather than through a tool.
                                        created.getRenderer().takeClipboardWrite()
                                            ?.let(copyText)
                                    },
                                )
                            }

                            override fun onInput(bytes: ByteArray) = session.write(bytes)

                            override fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float) {}

                            override fun onKeyboardOverlayStateChanged(expanded: Boolean) {}

                            override fun onHyperlinkClicked(uri: String) = openLink(uri)

                            override fun onTextSelected(text: String) = copyText(text)
                        })
                    }
                },
            )
            InputToolbar(
                onKey = { session.write(it) },
                onPaste = { pasteText().takeIf { text -> text.isNotEmpty() }?.let(session::write) },
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

        // The terminal renders on a surface composited above its own window, so
        // nothing drawn in that window can cover it. A panel therefore gets a
        // window of its own.
        surface?.let { shown ->
            Dialog(
                onDismissRequest = surfaces::dismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                SurfacePanel(
                    surface = shown,
                    onDecide = { diff, decision -> surfaces.decide(diff, decision) },
                    onDismiss = surfaces::dismiss,
                )
            }
        }

        // A drawer of its own rather than a Surface. Surfaces holds one surface
        // and abandons the last, and abandoning a diff rejects it -- so routing
        // the tree through it would answer for the agent every time the operator
        // opened a file.
        if (drawerOpen) {
            FileDrawer(onClosed = { drawerOpen = false }) { close ->
                FileTree(
                    root = root,
                    expanded = expanded,
                    onToggle = { file ->
                        expanded =
                            if (file.path in expanded) expanded - file.path
                            else expanded + file.path
                    },
                    // A click handler runs on the main thread, and this reads up
                    // to a mebibyte. The panel opens when the document is built.
                    onPick = { file ->
                        scope.launch {
                            val document = withContext(Dispatchers.IO) { documentFor(file) }
                            surfaces.show(Surface.FileView(file.path, document))
                        }
                    },
                    onDismiss = close,
                )
            }
        }
    }

    LaunchedEffect(view) { view?.showKeyboard() }
}

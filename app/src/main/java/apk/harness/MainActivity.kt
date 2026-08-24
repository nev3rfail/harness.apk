package apk.harness

import android.content.ActivityNotFoundException
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import apk.harness.ui.HarnessTheme
import apk.harness.ui.InputToolbar
import apk.harness.ui.SurfacePanel
import com.ghostty.android.renderer.GhosttyGLSurfaceView
import com.ghostty.android.renderer.TerminalEventListener
import com.ghostty.android.terminal.TerminalSession
import java.io.File

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

        val home = filesDir

        val tools = Tools(
            surfaces = surfaces,
            openExternal = ::openExternally,
            readFile = { path -> File(path).readText() },
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
                    onSurfaceViewCreated = { surfaceView = it },
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
}

@Composable
private fun HarnessScreen(
    session: TerminalSession,
    surfaces: Surfaces,
    onSurfaceViewCreated: (GhosttyGLSurfaceView) -> Unit,
) {
    var view by remember { mutableStateOf<GhosttyGLSurfaceView?>(null) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    val surface by surfaces.visible.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // The terminal stays mounted underneath: a panel is a look at something,
        // not a change of screen, and the agent keeps running behind it.
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
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
                                        created.getRenderer().processInput(bytes, length)
                                    },
                                )
                            }

                            override fun onInput(bytes: ByteArray) = session.write(bytes)

                            override fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float) {}

                            override fun onKeyboardOverlayStateChanged(expanded: Boolean) {}
                        })
                    }
                },
            )
            InputToolbar(
                onKey = { session.write(it) },
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
    }

    LaunchedEffect(view) { view?.showKeyboard() }
}

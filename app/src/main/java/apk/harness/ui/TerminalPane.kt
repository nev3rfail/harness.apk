package apk.harness.ui

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ghostty.android.renderer.GhosttyGLSurfaceView
import com.ghostty.android.renderer.TerminalEventListener
import com.ghostty.android.terminal.TerminalSession
import java.io.OutputStream

/**
 * One agent's terminal.
 *
 * There is one of these per tab and they are all composed at once; only the
 * showing one is visible. A hidden one loses its OpenGL context and keeps its
 * terminal -- the native renderer holds the terminal apart from the context
 * exactly so that it survives one being recreated, which is the same path the
 * app takes every time it goes to the background and returns.
 *
 * [visible] rather than composing only the showing tab, because dropping the
 * view would take the terminal with it and the agent behind it would keep
 * writing to a pty nobody was reading.
 */
@Composable
fun TerminalPane(
    session: TerminalSession,
    visible: Boolean,
    scrollbackBytes: Long,
    /** Where every byte the agent writes is kept, when the operator asked for that. */
    capture: OutputStream?,
    onCreated: (GhosttyGLSurfaceView) -> Unit,
    onReleased: (GhosttyGLSurfaceView) -> Unit,
    onModifiersConsumed: () -> Unit,
    openLink: (String) -> Unit,
    copyText: (String) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            GhosttyGLSurfaceView(context, maxScrollbackBytes = scrollbackBytes).also { created ->
                onCreated(created)
                created.onModifiersConsumed = onModifiersConsumed
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
                                // The agent asks for the clipboard with OSC 52,
                                // which arrives in its output rather than
                                // through a tool.
                                created.getRenderer().takeClipboardWrite()?.let(copyText)
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
        update = { view ->
            val wanted = if (visible) View.VISIBLE else View.GONE
            if (view.visibility == wanted) return@AndroidView
            // The GL thread is stopped on the way out and started on the way
            // back, in that order relative to the visibility change, so the
            // thread is never running against a surface that is gone.
            if (visible) {
                view.visibility = View.VISIBLE
                view.onResumeView()
            } else {
                view.onPauseView()
                view.visibility = View.GONE
            }
        },
        onRelease = { view -> onReleased(view) },
    )
}

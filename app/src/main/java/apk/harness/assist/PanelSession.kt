package apk.harness.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.service.voice.VoiceInteractionSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import apk.harness.bootstrap.Bootstrap
import apk.harness.ui.HarnessTheme
import java.io.File

/**
 * The panel in the voice interaction window. That window is a plain Dialog, and
 * a ComposeView finds its lifecycle and saved-state owners on the decor view,
 * so the session is both.
 */
class PanelSession(context: Context) :
    VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, PanelHost {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val inSession = true

    private var state by mutableStateOf<PanelState?>(null)
    private var content: View? = null
    /** Whether the session is shown, as far as its own shows and hides tell. */
    private var visible = false
    /** Set by a hide the panel makes for itself, and cleared by the onHide that reads it. */
    private var handingOver = false
    private val keyguard by lazy { context.getSystemService(KeyguardManager::class.java) }
    // Not the view's own queue: a view that is detached by then never runs it.
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        val window = window.window!!
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@PanelSession))
            setContent { HarnessTheme { state?.let { AskPanel(it, this@PanelSession) } } }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}

                // A window removed while the session is shown ends with no onHide;
                // that is a dismiss.
                override fun onViewDetachedFromWindow(view: View) {
                    if (!visible) return
                    Log.i(TAG, "the window went while shown")
                    dismiss()
                }
            })
            content = this
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "show flags=$showFlags own=${args?.getBoolean(Assistant.PANEL_REQUEST)}")
        val show = classifyShow(
            locked = keyguard.isKeyguardLocked,
            shown = visible && state != null,
            ownRequest = args?.getBoolean(Assistant.PANEL_REQUEST) == true,
            staged = Bootstrap(context).isInstalled(),
        )
        when (show) {
            Show.Locked -> { state?.dismiss(); state = PanelState.locked() }
            Show.NotStaged -> state = PanelState.notStaged(context)
            Show.Fresh -> state = PanelState().also { it.begin(context); it.listenIfGranted() }
            Show.Listen -> state?.listenIfGranted()
            Show.OwnReShow -> {}
        }
        visible = true
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        Log.i(TAG, "hide")
        visible = false
        val current = state
        val handedOver = handingOver
        main.removeCallbacks(expire)
        endContext()
        // The lock screen, home, back, the scrim and the close control all end
        // here; a hide the panel made for itself hands the panel over instead.
        when {
            handedOver -> handingOver = false
            current?.escalating == true -> current.run?.hidden()
            else -> current?.dismiss()
        }
        // Screenshots waiting to be sent go with the panel.
        if (!handedOver) File(context.cacheDir, ASSIST_CACHE).deleteRecursively()
        state = null
        window.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        endContext()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (state?.escalating == true) return
        dismiss()
    }

    /** Esc from a hardware keyboard is back; left to the window, it removes it without a hide. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyDown(keyCode, event)
        onBackPressed()
        return true
    }

    override fun dismiss() = hide()

    override fun start(intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    override fun keepScreenOn(on: Boolean) {
        val window = window.window ?: return
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Asks the platform for this summon's screen context: the panel draws
     * nothing from the next frame on, then the flags are cleared and the session
     * shown again, marked as the panel's own request.
     */
    override fun askContext(text: Boolean, shot: Boolean) {
        val current = state ?: return
        current.request = ContextRequest(text, shot)
        val flags = SHOW_WITH_ASSIST or if (shot) SHOW_WITH_SCREENSHOT else 0
        content?.visibility = View.INVISIBLE
        // Two frames: the first draws the panel invisible, the second follows it.
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback {
                Assistant.service?.setDisabledShowContext(0)
                Assistant.service?.showSession(Assistant.requestArgs(), flags)
                main.removeCallbacks(expire)
                main.postDelayed(expire, CONTEXT_WAIT)
            }
        }
    }

    /** Two seconds after the request: whatever has not arrived is dropped. */
    private val expire = Runnable {
        val current = state
        val (next, outcome) = timeOut(current?.request)
        current?.request = next
        if (outcome.close) endContext()
    }

    /**
     * [delivery] through this summon's request: true when it is to be taken.
     * The platform delivers on every summon, and once, right after an install,
     * with context no one asked for.
     */
    private fun delivered(delivery: Delivery): Boolean {
        val current = state ?: return false
        val (next, outcome) = deliver(current.request, delivery)
        current.request = next
        if (outcome.close) {
            main.removeCallbacks(expire)
            endContext()
        }
        return outcome.take
    }

    private fun endContext() {
        Assistant.service?.setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        content?.visibility = View.VISIBLE
    }

    override fun onHandleAssist(assist: AssistState) {
        val structure = assist.assistStructure
        Log.i(TAG, "assist focused=${assist.isFocused} structure=${structure != null} windows=${structure?.windowNodeCount}")
        if (!assist.isFocused) return
        Log.i(TAG, "assist taken=${delivered(Delivery.Structure)}")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val taken = delivered(Delivery.Screenshot)
        Log.i(TAG, "screenshot ${screenshot?.width}x${screenshot?.height} taken=$taken")
        if (!taken || screenshot == null) return
        // The image the next message carries.
        val file = File(context.cacheDir, "$ASSIST_CACHE/screenshot.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { screenshot.copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    override fun requestMic() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            state?.voice?.start()
        } else {
            // A session cannot ask for permissions, and a hidden one can start nothing.
            startAssistantActivity(Intent(context, MicActivity::class.java))
        }
    }
}

private const val CONTEXT_WAIT = 2_000L
/** Under the cache directory: screenshots taken for the panel. */
private const val ASSIST_CACHE = "assist"

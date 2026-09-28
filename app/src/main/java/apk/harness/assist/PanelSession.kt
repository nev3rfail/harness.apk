package apk.harness.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
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
            content = this
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "show flags=$showFlags own=${args?.getBoolean(Assistant.PANEL_REQUEST)}")
        if (state == null) state = PanelState().also { it.begin(context) }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        Log.i(TAG, "hide")
        state?.dismiss()
        state = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBackPressed() {
        Log.i(TAG, "back reached the session")
        super.onBackPressed()
    }

    override fun dismiss() = hide()

    /**
     * Asks the platform for this summon's screen context: the panel draws
     * nothing from the next frame on, then the flags are cleared and the session
     * shown again, marked as the panel's own request.
     */
    override fun askContext(text: Boolean, shot: Boolean) {
        val flags = SHOW_WITH_ASSIST or if (shot) SHOW_WITH_SCREENSHOT else 0
        content?.visibility = View.INVISIBLE
        // Two frames: the first draws the panel invisible, the second follows it.
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback {
                Assistant.service?.setDisabledShowContext(0)
                Assistant.service?.showSession(Assistant.requestArgs(), flags)
                content?.postDelayed(::endContext, 2_000)
            }
        }
    }

    private fun endContext() {
        Assistant.service?.setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        content?.visibility = View.VISIBLE
    }

    override fun onHandleAssist(assist: AssistState) {
        val structure = assist.assistStructure
        Log.i(TAG, "assist focused=${assist.isFocused} structure=${structure != null} windows=${structure?.windowNodeCount} url=${assist.assistContent?.webUri}")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Log.i(TAG, "screenshot ${screenshot?.width}x${screenshot?.height}")
        screenshot ?: return
        // The image the next message carries; Task 13 scales and encodes it.
        val file = File(context.cacheDir, "assist/screenshot.png").apply { parentFile?.mkdirs() }
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

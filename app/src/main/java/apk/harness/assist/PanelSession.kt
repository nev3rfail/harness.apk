package apk.harness.assist

import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.service.voice.VoiceInteractionSession
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import apk.harness.bootstrap.Bootstrap
import apk.harness.ui.HarnessTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Counts hides, so a delivery parked before a show is known to be this summon's. */
    private var summon = 0
    /**
     * With "Always", what a summon delivered before its show made the panel,
     * applied to it at that show.
     */
    private val early = ArrayList<Parked>()

    /** The panel whose mic request MicActivity is answering. */
    private var micAsker: PanelState? = null
    /** The handed-over panel a grant came back for before its re-show claimed it. */
    private var grantFor: PanelState? = null

    private class Parked(val summon: Int, val at: Long, val file: File?, val apply: (PanelState) -> Unit)

    /** The screen going off hides a shown panel, as the lock screen does where one is set. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (visible) hide()
        }
    }

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch {
            Assistant.mic.collect { answer ->
                when (answer) {
                    MicAnswer.Started -> Assistant.handoverEvent(HandoverEvent.MicStarted)
                    is MicAnswer.Answered -> {
                        Assistant.handoverEvent(HandoverEvent.MicFinished)
                        // Only the panel that asked takes the answer; a handed-over
                        // one takes a grant once its re-show has claimed it.
                        val asker = micAsker
                        micAsker = null
                        if (asker != null) {
                            asker.micBlocked = answer.blocked
                            if (answer.granted) {
                                if (state === asker) asker.micGranted() else if (state == null) grantFor = asker
                            }
                        }
                    }
                    MicAnswer.Stopped -> Assistant.handoverEvent(HandoverEvent.MicStopped)
                    null -> return@collect
                }
                Assistant.mic.value = null
            }
        }
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
                // that is a dismiss, made here since the hide may never reach onHide.
                override fun onViewDetachedFromWindow(view: View) {
                    if (!visible) return
                    Log.i(TAG, "the window went while shown")
                    drop()
                    hide()
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
            shown = panelShown(visible, state?.gate),
            ownRequest = args?.getBoolean(Assistant.PANEL_REQUEST) == true,
            staged = Bootstrap(context).isInstalled(),
        )
        // A summon of the operator's own ends a panel handed over for the
        // microphone, and no answer or grant from its request reaches another.
        if (show != Show.OwnReShow) {
            Assistant.handoverEvent(HandoverEvent.Superseded)
            micAsker = null
            grantFor = null
        }
        when (show) {
            Show.Locked -> { drop(); state = PanelState.locked() }
            Show.NotStaged -> state = PanelState.notStaged(context)
            Show.Fresh -> {
                drop()
                state = PanelState().also { it.begin(context); it.listenIfGranted() }
            }
            Show.Listen -> state?.let { it.refreshOtherAppsMic(); it.listenIfGranted() }
            Show.OwnReShow -> if (state == null) {
                // The re-show after the mic handover takes its panel back; with
                // none left to take, the session has nothing to draw.
                val claimed = Assistant.claim()
                state = claimed
                if (claimed == null) {
                    hide()
                    return
                }
                // The handover's hide ended any context request, and nothing more comes for
                // it: chips with nothing attached go off, as at the end of a wait.
                claimed.request = timeOut(claimed.request).first
                claimed.requestEnded()
                claimed.refreshOtherAppsMic()
                if (grantFor === claimed) claimed.micGranted()
                grantFor = null
            }
        }
        val current = state
        val parked = early.toList()
        early.clear()
        if ((show == Show.Fresh || show == Show.Listen) && current != null && always()) {
            // Both chips start on; what this summon delivers is taken with no request.
            current.screenText = true
            current.screenshot = true
            val now = SystemClock.uptimeMillis()
            parked.forEach {
                if (parkedApplies(it.summon, summon, it.at, now)) it.apply(current) else it.file?.delete()
            }
            // What has not come by the wait turns its chip off, as for a request.
            main.removeCallbacks(alwaysEnded)
            main.postDelayed(alwaysEnded, CONTEXT_WAIT)
        } else parked.forEach { it.file?.delete() }
        visible = true
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        Log.i(TAG, "hide")
        visible = false
        summon++
        discardParked()
        val handedOver = handingOver
        main.removeCallbacks(expire)
        main.removeCallbacks(alwaysEnded)
        endContext()
        // The lock screen, home, back, the scrim and the close control all end
        // here; a hide the panel made for itself hands the panel over instead.
        if (handedOver) handingOver = false
        else {
            drop()
            // Screenshots waiting to be sent go with the panel.
            File(context.cacheDir, ASSIST_CACHE).deleteRecursively()
        }
        state = null
        window.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onHide()
    }

    override fun onDestroy() {
        context.unregisterReceiver(screenOff)
        main.removeCallbacksAndMessages(null)
        endContext()
        // An encode that lands after this finds its summon over and deletes its file.
        summon++
        discardParked()
        if (state != null) {
            drop()
            File(context.cacheDir, ASSIST_CACHE).deleteRecursively()
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBackPressed() {
        state?.back(this) ?: dismiss()
    }

    /** Esc from a hardware keyboard is back; left to the window, it removes it without a hide. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyDown(keyCode, event)
        onBackPressed()
        return true
    }

    override fun dismiss() = hide()

    /** Ends the panel's state: a hide during an escalation reaches its stop, else the run is dismissed. */
    private fun drop() {
        val current = state ?: return
        state = null
        current.hide()
    }

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
        // A frame after the panel has gone or been replaced asks nothing, and
        // draws whatever panel is there again.
        val stale = { !visible || state !== current }
        // Two frames: the first draws the panel invisible, the second follows it.
        Choreographer.getInstance().postFrameCallback {
            if (stale()) { endContext(); return@postFrameCallback }
            Choreographer.getInstance().postFrameCallback {
                if (stale()) { endContext(); return@postFrameCallback }
                Assistant.service?.setDisabledShowContext(Assistant.disabled(context) and flags.inv())
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
        if (outcome.close) {
            endContext()
            current?.requestEnded()
        }
    }

    /** The wait after an "Always" summon; a request opened since then keeps its own. */
    private val alwaysEnded = Runnable {
        val current = state ?: return@Runnable
        if (current.request?.open != true) current.requestEnded()
    }

    private fun discardParked() {
        early.forEach { it.file?.delete() }
        early.clear()
    }

    /**
     * Whether [delivery] is to be taken: through this summon's open request,
     * else with "Always". The platform delivers on every summon, and once,
     * right after an install, with context no one asked for. With no panel yet,
     * "Always" parks it for the show.
     */
    private fun taken(delivery: Delivery): Boolean {
        val current = state ?: return always()
        val outcome = current.takeDelivery(delivery, always())
        if (outcome.close) {
            main.removeCallbacks(expire)
            endContext()
        }
        return outcome.take
    }

    /** The flags back at the setting's own value, so "Always" is kept, and the panel drawn again. */
    private fun endContext() {
        Assistant.service?.setDisabledShowContext(Assistant.disabled(context))
        content?.visibility = View.VISIBLE
    }

    private fun always(): Boolean = Assistant.settings(context).let { it.consented && it.screenContext == ScreenContext.Always }

    /**
     * [apply] to the panel a delivery is for: the current one, or with
     * "Always" and no panel yet, the one this summon's show makes.
     */
    private fun toPanel(file: File?, apply: (PanelState) -> Unit) {
        val current = state
        if (current != null) apply(current) else early += Parked(summon, SystemClock.uptimeMillis(), file, apply)
    }

    /** 29+: the focused app's structure only, so in split screen the text is that app's. */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(assist: AssistState) {
        val structure = assist.assistStructure
        Log.i(TAG, "assist focused=${assist.isFocused} structure=${structure != null} windows=${structure?.windowNodeCount}")
        if (!assist.isFocused) return
        screenCame(structure, assist.assistContent)
    }

    /** Below 29, the one app's structure. From 29 the override above takes every delivery and never calls this one. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        Log.i(TAG, "assist structure=${structure != null} windows=${structure?.windowNodeCount}")
        screenCame(structure, content)
    }

    private fun screenCame(structure: AssistStructure?, content: AssistContent?) {
        val taken = taken(Delivery.Structure)
        Log.i(TAG, "assist taken=$taken")
        if (!taken) return
        val flat = structure?.let { flattenScreen(screenNodes(it), content?.webUri?.toString()) }
        val text = flat?.takeIf { it.isNotEmpty() }?.let { Attachment.ScreenText(appLabel(context, structure), it) }
        Log.i(TAG, "screen text of ${flat?.length ?: 0} characters")
        toPanel(null) { it.screenTextCame(text) }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val taken = taken(Delivery.Screenshot)
        Log.i(TAG, "screenshot ${screenshot?.width}x${screenshot?.height} taken=$taken")
        if (!taken) return
        if (screenshot == null) {
            toPanel(null) { it.screenshotCame(null) }
            return
        }
        val asked = state
        val askedIn = summon
        val deliveredAt = SystemClock.uptimeMillis()
        Assistant.runs.launch {
            // An encode that fails is a screenshot that did not come. Exceptions
            // are caught; Errors propagate.
            val encoded = try {
                encodeScreenshot(context, screenshot)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w(TAG, "the screenshot was not encoded", failure)
                null
            }
            withContext(Dispatchers.Main) {
                // A panel dismissed or replaced during the encode does not take it.
                val current = state
                when {
                    askedIn != summon -> encoded?.file?.delete()
                    current == null && asked == null ->
                        early += Parked(askedIn, deliveredAt, encoded?.file) { it.screenshotCame(encoded) }
                    current != null && (current === asked || asked == null) -> current.screenshotCame(encoded)
                    else -> encoded?.file?.delete()
                }
            }
        }
    }

    /**
     * A session cannot ask for permissions, so MicActivity asks. It is started
     * while shown, since a hidden session can start nothing; the permission
     * dialog lands under the session window, so the session then hides as a
     * handover and MicActivity's answer re-shows it.
     */
    override fun requestMic() {
        val current = state ?: return
        // startAssistantActivity is 26+; below it the panel points at the app's settings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            current.micBlocked = true
            return
        }
        // A fresh task each time, so nothing an earlier request left behind answers for this one.
        val started = runCatching {
            startAssistantActivity(
                Intent(context, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        if (started.isFailure) {
            Log.w(TAG, "the microphone request did not start", started.exceptionOrNull())
            current.notice = MIC_NOT_ASKED
            return
        }
        micAsker = current
        Assistant.handOver(current)
        handingOver = true
        hide()
    }
}

private const val MIC_NOT_ASKED = "The microphone could not be asked for."

/** Under the cache directory: screenshots taken for the panel. */
private const val ASSIST_CACHE = "assist"

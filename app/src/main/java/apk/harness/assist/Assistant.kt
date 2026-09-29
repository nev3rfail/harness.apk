package apk.harness.assist

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** What outlives any one panel: the running service, the runs and their stops. */
object Assistant {
    /** The service while it is ready, which is when its calls take effect. */
    @Volatile
    var service: AssistantService? = null

    /**
     * Where every run's process is read, written and stopped. A hidden session
     * or a finished activity ends its own scope; a stop it asked for carries on
     * here. A failure in one job is logged and leaves the others running.
     */
    val runs = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, failure -> Log.e(TAG, "a run's job failed", failure) },
    )

    /** The ids of the shares a panel holds, whose directories a clear leaves alone. */
    val sharesInUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The answer [MicActivity] brings back, taken and cleared by the panel that asked. */
    val mic = MutableStateFlow<MicAnswer?>(null)

    /** [AskActivity]'s panel across a recreation, main thread only. */
    var recreated: PanelState? = null

    /** Marks a show the panel asked for itself. */
    const val PANEL_REQUEST = "apk.harness.panel.REQUEST"

    fun requestArgs(): Bundle = Bundle().apply { putBoolean(PANEL_REQUEST, true) }

    private const val ASSISTANT_FILE = "assistant.json"

    fun settings(context: Context): AssistantSettings =
        readAssistant(File(context.noBackupFilesDir, ASSISTANT_FILE))

    /** Records a microphone request; true when one was made before it. */
    fun askingMic(context: Context): Boolean {
        val current = settings(context)
        if (!current.micAsked) save(context, current.copy(micAsked = true))
        return current.micAsked
    }

    /** The context the service keeps disabled while the panel asks for none. */
    fun disabled(context: Context): Int = settings(context).let { disabledContext(it.screenContext, it.consented) }

    /** Writes [settings] and tells the running service at once. */
    fun save(context: Context, settings: AssistantSettings) {
        runCatching { writeAssistant(File(context.noBackupFilesDir, ASSISTANT_FILE), settings) }
            .onFailure { Log.w(TAG, "the assistant settings were not written", it) }
        service?.setDisabledShowContext(disabledContext(settings.screenContext, settings.consented))
    }

    // Main thread only. The handed-over panel lives here, not in the session
    // object, so the re-show finds it.
    private val main = Handler(Looper.getMainLooper())
    private var handed: PanelState? = null
    private var pending: Handover? = null

    fun handOver(state: PanelState) {
        handed = state
        val (started, outcome) = beginHandover()
        pending = started
        act(outcome)
    }

    fun handoverEvent(event: HandoverEvent) {
        val current = pending ?: return
        val (next, outcome) = handover(current, event)
        pending = next
        act(outcome)
    }

    /** The panel handed over, taken by its own re-show. */
    fun claim(): PanelState? {
        handoverEvent(HandoverEvent.ReShown)
        return handed.also { handed = null }
    }

    private fun act(outcome: HandoverOutcome) {
        when (outcome) {
            is HandoverOutcome.After -> main.postDelayed({ handoverEvent(HandoverEvent.Timer(outcome.generation)) }, outcome.millis)
            HandoverOutcome.Dismiss -> { handed?.dismiss(); handed = null }
            HandoverOutcome.Keep, HandoverOutcome.Wait -> {}
        }
    }
}

sealed interface MicAnswer {
    /** MicActivity is up, and its dialog on the way. */
    data object Started : MicAnswer
    /** [blocked]: denied, and the system will not ask again. */
    data class Answered(val granted: Boolean, val blocked: Boolean) : MicAnswer
    data object Stopped : MicAnswer
}

/** The panel's working directory, under the agent's home. */
const val ASSISTANT_DIRECTORY = "assistant"

internal const val TAG = "AskAgent"

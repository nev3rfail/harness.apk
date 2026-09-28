package apk.harness.assist

import android.os.Bundle
import android.util.Log
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

    /** The answer [MicActivity] brings back, taken and cleared by the panel that asked. */
    val mic = MutableStateFlow<MicAnswer?>(null)

    /** [AskActivity]'s panel across a recreation, main thread only. */
    var recreated: PanelState? = null

    /** Marks a show the panel asked for itself. */
    const val PANEL_REQUEST = "apk.harness.panel.REQUEST"

    fun requestArgs(): Bundle = Bundle().apply { putBoolean(PANEL_REQUEST, true) }
}

sealed interface MicAnswer {
    data class Answered(val granted: Boolean) : MicAnswer
    data object Stopped : MicAnswer
}

/** The panel's working directory, under the agent's home. */
const val ASSISTANT_DIRECTORY = "assistant"

internal const val TAG = "AskAgent"

package apk.harness.assist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apk.harness.Agent
import apk.harness.agents.BACKENDS
import java.io.File
import java.util.UUID

/** One panel's state. A plain holder: the session provides no ViewModelStoreOwner. */
class PanelState(val sessionId: String = UUID.randomUUID().toString()) {
    var field by mutableStateOf("")
    var status by mutableStateOf<String?>(null)
    var run: HeadlessRun? = null
        private set
    var voice: Voice? = null
        private set

    /** Starts the process at once, so its start overlaps the question. */
    fun begin(context: Context) {
        val app = context.applicationContext
        val agent = Agent(app)
        val backend = BACKENDS.first()
        run = HeadlessRun(backend, agent.home, sessionId) { arguments ->
            agent.headless(File(agent.home, ASSISTANT_DIRECTORY), backend, arguments)
        }.also { it.begin() }
        voice = Voice(app, onPartial = { field = it }, onFinal = { field = it }, onListening = {})
    }

    fun dismiss() {
        run?.dismiss()
        voice?.destroy()
    }
}

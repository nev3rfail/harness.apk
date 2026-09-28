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
    var conversation by mutableStateOf(Conversation())
    /** Whether a message went to the agent, so the conversation has a transcript to open. */
    var sent = false
        private set
    var run: HeadlessRun? = null
        private set
    var voice: Voice? = null
        private set

    /** Starts the process at once, so its start overlaps the question. */
    fun begin(context: Context) {
        val app = context.applicationContext
        val agent = Agent(app)
        val backend = BACKENDS.first()
        run = HeadlessRun(
            backend = backend,
            home = agent.home,
            sessionId = sessionId,
            startProcess = { arguments ->
                agent.headless(File(agent.home, ASSISTANT_DIRECTORY), backend, arguments)
            },
            onEvent = { conversation = thread(conversation, ThreadEvent.Agent(it)) },
            onExit = { code, stderr -> conversation = thread(conversation, ThreadEvent.Exited(code, stderr)) },
        ).also { it.begin() }
        voice = Voice(app, onPartial = { field = it }, onFinal = { field = it }, onListening = {})
    }

    // `this.field`: a bare `field` in a getter is the backing field.
    val canSend: Boolean get() = !conversation.inFlight && this.field.isNotBlank()

    fun send() {
        if (!canSend) return
        val text = field
        conversation = thread(conversation, ThreadEvent.Sent(text, emptyList()))
        field = ""
        sent = true
        run?.send(listOf(text), emptyList())
    }

    fun dismiss() {
        run?.stop(StopKind.Dismiss, sent)
        voice?.destroy()
    }
}

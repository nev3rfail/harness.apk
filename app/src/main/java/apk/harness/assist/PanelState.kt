package apk.harness.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apk.harness.Agent
import apk.harness.MainActivity
import apk.harness.agents.BACKENDS
import apk.harness.bootstrap.Bootstrap
import java.io.File
import java.util.UUID

/** One panel's state. A plain holder: the session provides no ViewModelStoreOwner. */
class PanelState(val sessionId: String = UUID.randomUUID().toString()) {
    enum class Gate { Open, Locked, NotStaged }

    var field by mutableStateOf("")
    var conversation by mutableStateOf(Conversation())
    var gate by mutableStateOf(Gate.Open)
    var escalating by mutableStateOf(false)
    var listening by mutableStateOf(false)
    /** One line under the field: a recognizer error, a start that found no app. */
    var notice by mutableStateOf<String?>(null)
    /** This summon's open request for screen context; a delivery outside one is dropped. */
    var request: ContextRequest? = null
    /** Whether a message went to the agent, so the conversation has a transcript to open. */
    var sent = false
        private set
    var run: HeadlessRun? = null
        private set
    var voice: Voice? = null
        private set
    private lateinit var app: Context

    /** Starts the process at once, so its start overlaps the question. */
    fun begin(context: Context) {
        app = context.applicationContext
        val app = app
        val agent = Agent(app)
        val backend = BACKENDS.first()
        run = HeadlessRun(
            backend = backend,
            home = agent.home,
            sessionId = sessionId,
            startProcess = { arguments ->
                // Restages the resolver files MainActivity otherwise stages at its start.
                Bootstrap(app).start()
                agent.headless(File(agent.home, ASSISTANT_DIRECTORY), backend, arguments)
            },
            onEvent = { conversation = threaded(conversation, ThreadEvent.Agent(it)) },
            onExit = { code, stderr -> conversation = threaded(conversation, ThreadEvent.Exited(code, stderr)) },
        ).also { it.begin() }
        voice?.destroy()
        voice = Voice(app, onPartial = { field = it }, onFinal = { field = it; send() }, onListening = { listening = it })
    }

    // `this.field`: a bare `field` in a getter is the backing field.
    val canSend: Boolean get() =
        gate == Gate.Open && !escalating && !conversation.inFlight && this.field.isNotBlank()

    fun send() {
        if (!canSend) return
        val text = field
        conversation = threaded(conversation, ThreadEvent.Sent(text, emptyList()))
        field = ""
        notice = null
        sent = true
        run?.send(listOf(text), emptyList())
    }

    fun listenIfGranted() {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice?.start()
    }

    /** The mic button: stops a listen, starts one, or asks the host for the permission. */
    fun mic(host: PanelHost) {
        when {
            listening -> voice?.stop()
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice?.start()
            else -> host.requestMic()
        }
    }

    /** Start, then dismiss, so the panel does not sit above what it opened. */
    fun startOutside(host: PanelHost, intent: Intent) {
        if (host.start(intent)) host.dismiss() else notice = NOTHING_OPENS
    }

    fun openApp(host: PanelHost) = startOutside(host,
        Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun signIn(host: PanelHost) = startOutside(host, openTab(app, File(Agent(app).home, ASSISTANT_DIRECTORY), null))

    fun openLink(host: PanelHost, url: String) = startOutside(host,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun escalate(host: PanelHost) {}

    fun dismiss() {
        run?.stop(StopKind.Dismiss, sent)
        voice?.destroy()
    }

    companion object {
        const val NOTHING_OPENS = "Nothing on this phone opens that."

        fun locked() = PanelState().apply { gate = Gate.Locked }

        fun notStaged(context: Context) = PanelState().apply { app = context.applicationContext; gate = Gate.NotStaged }
    }
}

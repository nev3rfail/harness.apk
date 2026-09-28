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
import apk.harness.agents.HeadlessImage
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
    /** The chips: screen context asked for, or attached, for the next message. */
    var screenText by mutableStateOf(false)
    var screenshot by mutableStateOf(false)
    /** What goes with the next message. */
    var attachments by mutableStateOf(listOf<Attachment>())
    /** The base64 size of every image the conversation has carried. */
    var sentImages = listOf<Long>()
    /** A request for screen context got nothing: a secure app, or the screen's use turned off. */
    var nothingCame by mutableStateOf(false)
    /** A disclosure drawn in place of the thread. */
    var disclosure by mutableStateOf<Disclosing?>(null)
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
        gate == Gate.Open && !escalating && !conversation.inFlight && (this.field.isNotBlank() || attachments.isNotEmpty())

    /** The field and the attachments as one message: the screen text first, each its own text block. */
    fun send() {
        if (!canSend) return
        val text = field
        val screen = attachments.filterIsInstance<Attachment.ScreenText>().firstOrNull()?.let { screenBlock(it.app, it.text) }
        val shots = attachments.filterIsInstance<Attachment.Screenshot>()
        conversation = threaded(conversation, ThreadEvent.Sent(text, attachments.map { it.line }))
        field = ""
        notice = null
        sent = true
        sentImages = sentImages + shots.map { base64Size(it.bytes) }
        attachments = emptyList()
        screenText = false
        screenshot = false
        nothingCame = false
        run?.send(listOfNotNull(screen, text.takeIf { it.isNotBlank() }), shots.map { HeadlessImage(it.file, "image/jpeg") })
    }

    /** A chip asks for this summon's screen context, after the disclosure when there is no consent. */
    fun chip(host: PanelHost, text: Boolean, shot: Boolean) {
        if (!Assistant.settings(app).consented) {
            disclosure = Disclosing.Screen(text, shot)
            return
        }
        nothingCame = false
        if (text) screenText = true
        if (shot) screenshot = true
        host.askContext(text, shot)
    }

    /** "Allow" on the screen disclosure: consent recorded, then the request it stood in front of. */
    fun allow(host: PanelHost) {
        val asked = disclosure as? Disclosing.Screen ?: return
        disclosure = null
        Assistant.save(app, Assistant.settings(app).copy(consented = true))
        chip(host, asked.text, asked.shot)
    }

    /** Back or the scrim: a disclosure on screen is declined, else the panel is dismissed. */
    fun back(host: PanelHost) {
        when {
            escalating -> {}
            disclosure != null -> disclosure = null
            else -> host.dismiss()
        }
    }

    /** The screen text a summon delivered, or null when nothing came. */
    fun screenTextCame(attachment: Attachment.ScreenText?) {
        if (attachment == null) {
            nothingCame = true
            screenText = false
            attachments = attachments.filterNot { it is Attachment.ScreenText }
        } else {
            screenText = true
            attachments = replacing(attachments, attachment)
        }
    }

    /**
     * The screenshot a summon delivered, or null when nothing came. One the
     * conversation has no room for is deleted, and the panel says so.
     */
    fun screenshotCame(attachment: Attachment.Screenshot?) {
        when {
            attachment == null -> {
                nothingCame = true
                screenshot = false
                attachments = attachments.filterNot { it is Attachment.Screenshot }
            }
            !screenshotFits(sentImages, base64Size(attachment.bytes)) -> {
                attachment.file.delete()
                screenshot = false
                notice = NO_ROOM_FOR_IMAGES
            }
            else -> {
                screenshot = true
                attachments.filterIsInstance<Attachment.Screenshot>().forEach { it.file.delete() }
                attachments = replacing(attachments, attachment)
            }
        }
    }

    fun listenIfGranted() {
        if (gate != Gate.Open || escalating) return
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

    /**
     * Stops the run so its transcript ends on a closed turn, opens the tab on
     * it, then dismisses. A panel that sent nothing opens a tab with no session.
     */
    fun escalate(host: PanelHost) {
        if (escalating || gate != Gate.Open) return
        escalating = true
        voice?.destroy()
        val directory = File(Agent(app).home, ASSISTANT_DIRECTORY)
        var opened = false
        // begin() makes the run before the panel is drawn with the gate open.
        run!!.stop(
            StopKind.Escalate,
            sent,
            onOpen = { session -> opened = host.start(openTab(app, directory, session)) },
            // Nothing took the tab: the panel stays and says so, on a new run
            // that resumes the conversation, since this one has been stopped.
            onDismiss = {
                if (opened) host.dismiss()
                else {
                    escalating = false
                    notice = NOTHING_OPENS
                    begin(app)
                }
            },
        )
    }

    fun dismiss() {
        run?.stop(StopKind.Dismiss, sent)
        voice?.destroy()
    }

    companion object {
        const val NOTHING_OPENS = "Nothing on this phone opens that."
        const val NO_ROOM_FOR_IMAGES = "This conversation holds as many images as it can carry."

        fun locked() = PanelState().apply { gate = Gate.Locked }

        fun notStaged(context: Context) = PanelState().apply { app = context.applicationContext; gate = Gate.NotStaged }
    }
}

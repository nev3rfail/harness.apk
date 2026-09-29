package apk.harness.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apk.harness.Agent
import apk.harness.MainActivity
import apk.harness.agents.BACKENDS
import apk.harness.agents.HeadlessImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One panel's state. A plain holder: the session provides no ViewModelStoreOwner. */
class PanelState(val sessionId: String = UUID.randomUUID().toString()) {
    enum class Gate { Open, Locked, NotStaged }

    var field by mutableStateOf("")
    var conversation by mutableStateOf(Conversation())
    var gate by mutableStateOf(Gate.Open)
    var escalating by mutableStateOf(false)
    var listening by mutableStateOf(false)
    /** One line under the field: a start that found no app, a screenshot with no room. */
    var notice by mutableStateOf<String?>(null)
    /** The recognizer's own line under the field, cleared by the next listen. */
    var voiceProblem by mutableStateOf<String?>(null)
    /** The microphone denied for good: only the app's settings can grant it now. */
    var micBlocked by mutableStateOf(false)
    /** The field when the listen began; what is heard goes after it. */
    private var typedBeforeListen = ""
    /** Whether a grant from the mic disclosure starts a listen; one for other apps' voice input does not. */
    private var listenOnGrant = true
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
    /** On Android 11, while the app is the assistant without the microphone: other apps' voice input needs the grant. */
    var otherAppsMic by mutableStateOf(false)
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
        clearShares(app)
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
        voice = Voice(
            app,
            onPartial = { field = afterTyped(typedBeforeListen, it) },
            onFinal = { field = afterTyped(typedBeforeListen, it); send() },
            onProblem = { voiceProblem = it },
            onListening = {
                if (it && !listening) typedBeforeListen = field
                listening = it
            },
        )
        otherAppsMic = asksMicForOtherApps(app)
    }

    /** Selected text another app handed over, drawn above the field and quoted in the first message. */
    var quote by mutableStateOf<String?>(null)
    /** How many of a share's streams are still being encoded or copied. */
    var copying by mutableStateOf(0)
    /** A share's streams not read or not copied, each named with the reason. */
    var refused by mutableStateOf(listOf<Refusal>())
    /** Set when the panel opens on a quote: the field takes focus and the keyboard comes up once. */
    var focusField by mutableStateOf(false)
    /** The share's directory under the inbox, deleted if the panel goes with nothing sent. */
    private var inbox: File? = null
    /** The share's directory under the cache, holding its encoded images; deleted as [inbox] is. */
    private var shareCache: File? = null
    /** The share's id, which names its directories and keeps them from [clearShares] while the panel is up. */
    private var shareId: String? = null
    /** The file that marks the share as not yet sent, so a panel that never got to dismiss leaves [inbox] to the next clear. */
    private var unsentMark: File? = null
    private var preparing: Job? = null
    /** A send tapped during the copy, made once it ends. */
    private var sendWhenReady = false

    /**
     * Opens on what was handed over: the quote and the field at once, then, on
     * the IO dispatcher while the activity holds the grant, each stream's type,
     * name and size asked of its provider, the images encoded and the files
     * copied. Nothing is sent until the operator sends.
     */
    fun fromShare(context: Context, request: AskRequest<Uri>) {
        quote = request.quote
        field = request.field
        focusField = request.quote != null
        refused = request.refused
        if (request.empty) {
            notice = NOTHING_SHARED
            return
        }
        if (request.streams.isEmpty()) return
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString().also { shareId = it }
        Assistant.sharesInUse += id
        val cache = File(shareCacheOf(app), id).also { shareCache = it }
        val box = File(inboxOf(app), id).also { inbox = it }
        val mark = File(unsentOf(app), id).also { unsentMark = it }
        copying = request.streams.size
        preparing = Assistant.runs.launch {
            // Declared outside the try, so a cancelled preparation deletes what it encoded.
            val encoded = ArrayList<EncodedImage?>()
            try {
                // Before anything is written into the share's directories.
                runCatching { mark.parentFile?.mkdirs(); mark.createNewFile() }
                val answers = request.streams.map { ensureActive(); it to describe(resolver, it.uri) }
                val silent = answers.filter { it.second == null }.map { (stream, _) -> Refusal(stream.name ?: "shared", NO_ANSWER, null) }
                val described = answers.mapNotNull { it.second }
                val (images, files) = described.partition { isImage(it.type) }
                for (image in images) {
                    if (!roomForBlocks(encoded.map { it?.bytes })) break
                    ensureActive()
                    encoded += encodeImage(resolver, image, cache)
                }
                val split = shareImages(encoded.map { it?.bytes })
                val blocks = split.blocks.map { images[it] to encoded[it]!! }
                // Past the budget, past the twentieth, or not decodable: copied as
                // files, with the ones never encoded.
                val asFiles = split.files.map { images[it] } + images.drop(encoded.size)
                split.files.forEach { encoded[it]?.file?.delete() }
                val copied = copyIn(resolver, asFiles + files, box)
                withContext(Dispatchers.Main) {
                    attachments = attachments +
                        blocks.map { (stream, image) -> Attachment.Image(image.file, image.mediaType, image.bytes, safeName(stream.name)) } +
                        copied.files.map { Attachment.Document(it) }
                    refused = refused + silent + copied.refused
                    copying = 0
                    if (sendWhenReady) {
                        sendWhenReady = false
                        send()
                    }
                }
            } catch (cancelled: CancellationException) {
                encoded.forEach { it?.file?.delete() }
                throw cancelled
            } finally {
                // However the preparation ends, a send no longer waits for it.
                withContext(NonCancellable + Dispatchers.Main) { copying = 0 }
            }
        }
    }

    // `this.field`: a bare `field` in a getter is the backing field.
    val canSend: Boolean get() =
        gate == Gate.Open && !escalating && !conversation.inFlight &&
            (this.field.isNotBlank() || quote != null || attachments.isNotEmpty() || copying > 0)

    /**
     * The quote, the field, the copied files' paths and the attachments as one
     * message: the screen text first, each its own text block. During a share's
     * copy the send waits for it.
     */
    fun send() {
        if (!canSend) return
        if (copying > 0) {
            sendWhenReady = true
            return
        }
        val text = field
        val copiedPaths = attachments.filterIsInstance<Attachment.Document>().map { it.file.absolutePath }
        val screen = attachments.filterIsInstance<Attachment.ScreenText>().firstOrNull()?.let { screenBlock(it.app, it.text) }
        // Screen text is its own block, ahead of the operator's.
        val texts = listOfNotNull(screen, compose(quote, text, copiedPaths).ifBlank { null })
        val images = attachments.mapNotNull {
            when (it) {
                is Attachment.Screenshot -> HeadlessImage(it.file, "image/jpeg")
                is Attachment.Image -> HeadlessImage(it.file, it.mediaType)
                else -> null
            }
        }
        val lines = listOfNotNull(if (quote != null) SELECTED_TEXT else null) + attachments.map { it.line }
        conversation = threaded(conversation, ThreadEvent.Sent(text, lines))
        field = ""
        notice = null
        voiceProblem = null
        // From here the share's files are the conversation's.
        unsentMark?.let { mark -> Assistant.runs.launch { mark.delete() } }
        unsentMark = null
        sent = true
        sentImages = sentImages + attachments.mapNotNull {
            when (it) {
                is Attachment.Screenshot -> base64Size(it.bytes)
                is Attachment.Image -> base64Size(it.bytes)
                else -> null
            }
        }
        quote = null
        attachments = emptyList()
        screenText = false
        screenshot = false
        nothingCame = false
        // The run deletes the image files once written; with none, they go here.
        run?.send(texts, images) ?: images.forEach { it.file.delete() }
    }

    /**
     * A chip asks for this summon's screen context, after the disclosure when
     * there is no consent. A selected chip is turned off, with what it attached.
     */
    fun chip(host: PanelHost, text: Boolean, shot: Boolean) {
        if (text && screenText) {
            screenText = false
            attachments = attachments.filterNot { it is Attachment.ScreenText }
            return
        }
        if (shot && screenshot) {
            screenshot = false
            attachments = dropScreenshot()
            return
        }
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

    /**
     * Whether [delivery] is taken, and whether it closed the open request.
     * While a request is open it alone decides, so a chip turned off stays off;
     * with none, [always] takes every delivery.
     */
    fun takeDelivery(delivery: Delivery, always: Boolean): ContextOutcome {
        val open = request?.open == true
        val (next, outcome) = deliver(request, delivery)
        request = next
        return outcome.copy(take = outcome.take || (!open && always))
    }

    /** The request closed by its wait: a chip it left with nothing attached goes off. */
    fun requestEnded() {
        if (screenText && attachments.none { it is Attachment.ScreenText }) {
            screenText = false
            nothingCame = true
        }
        if (screenshot && attachments.none { it is Attachment.Screenshot }) {
            screenshot = false
            nothingCame = true
        }
    }

    /** An attachment's remove control: it leaves the next message with its file, and a screen one turns its chip off. */
    fun remove(attachment: Attachment) {
        when (attachment) {
            is Attachment.ScreenText -> screenText = false
            is Attachment.Screenshot -> { screenshot = false; attachment.file.delete() }
            is Attachment.Image -> attachment.file.delete()
            is Attachment.Document -> attachment.file.delete()
        }
        attachments = attachments - attachment
    }

    /** [attachments] without the screenshot, its file deleted. */
    private fun dropScreenshot(): List<Attachment> {
        attachments.filterIsInstance<Attachment.Screenshot>().forEach { it.file.delete() }
        return attachments.filterNot { it is Attachment.Screenshot }
    }

    /** The screen text a summon delivered, or null when nothing came. Only an open panel takes it. */
    fun screenTextCame(attachment: Attachment.ScreenText?) {
        if (gate != Gate.Open) return
        if (attachment == null) {
            nothingCame = true
            screenText = false
            attachments = attachments.filterNot { it is Attachment.ScreenText }
        } else {
            screenText = true
            nothingCame = false
            attachments = replacing(attachments, attachment)
        }
    }

    /**
     * The screenshot a summon delivered, or null when nothing came. One the
     * conversation has no room for is deleted, and the panel says so. Only an
     * open panel takes it.
     */
    fun screenshotCame(attachment: Attachment.Screenshot?) {
        when {
            gate != Gate.Open -> attachment?.file?.delete()
            attachment == null -> {
                nothingCame = true
                screenshot = false
                attachments = dropScreenshot()
            }
            !screenshotFits(sentImages, base64Size(attachment.bytes)) -> {
                attachment.file.delete()
                screenshot = false
                notice = NO_ROOM_FOR_IMAGES
            }
            else -> {
                screenshot = true
                nothingCame = false
                attachments = replacing(dropScreenshot(), attachment)
            }
        }
    }

    fun listenIfGranted() {
        if (gate != Gate.Open || escalating) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice?.start()
    }

    /** The mic button: stops a listen, starts one, or shows the mic disclosure before the request. */
    fun mic(host: PanelHost) {
        when {
            listening -> voice?.stop()
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice?.start()
            else -> disclosure = Disclosing.Mic(otherApps = false)
        }
    }

    /** "Continue" on the mic disclosure: the host asks for the permission. */
    fun micContinue(host: PanelHost) {
        listenOnGrant = (disclosure as? Disclosing.Mic)?.otherApps != true
        disclosure = null
        host.requestMic()
    }

    /** The microphone granted: the line for other apps goes, and the panel listens if the mic button asked. */
    fun micGranted() {
        otherAppsMic = false
        micBlocked = false
        if (listenOnGrant) voice?.start()
    }

    /** The app's own settings page, where a microphone denied for good is granted. */
    fun openAppSettings(host: PanelHost) = startOutside(host,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** The operator's Android 11 grant state may have changed while the panel was away. */
    fun refreshOtherAppsMic() {
        if (gate == Gate.Open) otherAppsMic = asksMicForOtherApps(app)
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
        dropUnsentShare()
        run?.stop(StopKind.Dismiss, sent)
        voice?.destroy()
    }

    /**
     * Home, the lock screen or the host going: an escalation carries on to its
     * stop and opens no tab, anything else is a dismiss. The share not sent
     * goes either way.
     */
    fun hide() {
        if (!escalating) return dismiss()
        dropUnsentShare()
        run?.hidden()
    }

    /**
     * A share not sent goes with the panel: its copies and its encoded images,
     * now and again once the preparation has ended. Once sent, the files are
     * the conversation's.
     */
    private fun dropUnsentShare() {
        if (!sent && inbox != null) {
            val job = preparing
            // The mark last, so a panel gone before the delete ends leaves the rest to the next clear.
            val directories = listOfNotNull(inbox, shareCache, unsentMark)
            job?.cancel()
            Assistant.runs.launch { directories.forEach { it.deleteRecursively() } }
            // A copy or an encode the cancel has not stopped yet may still write into them.
            job?.invokeOnCompletion { Assistant.runs.launch { directories.forEach { it.deleteRecursively() } } }
        }
        shareId?.let { Assistant.sharesInUse -= it }
    }

    companion object {
        const val NOTHING_SHARED = "Nothing came with the share."
        private const val SELECTED_TEXT = "Selected text"
        /**
         * Under the cache directory, one directory per share: its images,
         * encoded for the message. Apart from the session's `assist`, which
         * each hide deletes.
         */
        private const val SHARE_CACHE = "share"
        const val NOTHING_OPENS = "Nothing on this phone opens that."
        const val NO_ROOM_FOR_IMAGES = "This conversation holds as many images as it can carry."

        private fun shareCacheOf(app: Context) = File(app.cacheDir, SHARE_CACHE)
        private fun inboxOf(app: Context) = File(File(Agent(app).home, ASSISTANT_DIRECTORY), "inbox")
        /** One empty file per share not yet sent, named by its id; outside the agent's home. */
        private fun unsentOf(app: Context) = File(app.noBackupFilesDir, "share-unsent")

        /** Clears, off the main thread, what earlier shares left that no panel holds. */
        fun clearShares(app: Context) {
            val unsent = unsentOf(app)
            val inbox = inboxOf(app)
            val cache = shareCacheOf(app)
            Assistant.runs.launch { clearAbandoned(unsent, inbox, cache, Assistant.sharesInUse) }
        }

        fun locked() = PanelState().apply { gate = Gate.Locked }

        fun notStaged(context: Context) = PanelState().apply { app = context.applicationContext; gate = Gate.NotStaged }
    }
}

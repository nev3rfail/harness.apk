package apk.harness.assist

import android.util.Log
import apk.harness.Roster
import apk.harness.agents.AgentBackend
import apk.harness.agents.HeadlessEvent
import apk.harness.agents.HeadlessImage
import apk.harness.agents.ProcTable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One conversation's agent process: started at show, sent every message on
 * stdin, read line by line on stdout, and stopped by [escalation]. A process
 * that exited on its own is started again on the next send, resuming the
 * conversation once it has a transcript. Callbacks run on the main thread.
 */
class HeadlessRun(
    private val backend: AgentBackend,
    private val home: File,
    val sessionId: String,
    private val startProcess: suspend (List<String>) -> Process,
    private val onEvent: (HeadlessEvent) -> Unit,
    private val onExit: (code: Int, stderr: List<String>) -> Unit,
) {
    private val lock = Mutex()
    private val tail = ArrayDeque<String>()
    @Volatile private var process: Process? = null
    @Volatile private var inFlight = false
    /** The started process's pid, from headless.sh's first stderr line. */
    @Volatile var pid: Int? = null
        private set
    private var stopping: StopState? = null
    private var onOpen: (String?) -> Unit = {}
    private var onDismiss: () -> Unit = {}

    fun begin() {
        Assistant.runs.launch { lock.withLock { running() } }
    }

    /** Writes one message after the process is up; the image files go once written. */
    fun send(texts: List<String>, images: List<HeadlessImage>) {
        inFlight = true
        Assistant.runs.launch {
            lock.withLock {
                val process = running()
                if (process != null) {
                    runCatching { backend.writeMessage(process.outputStream, texts, images) }
                        .onFailure { failure ->
                            // Part of a line may be on stdin, so the process is
                            // ended and the next send starts another. Its exit
                            // shows the failure with the rest of stderr.
                            Log.w(TAG, "the message did not reach the agent", failure)
                            remember("the message did not reach the agent: ${failure.message ?: failure}")
                            process.destroyForcibly()
                        }
                }
                images.forEach { it.file.delete() }
            }
        }
    }

    /** Starts the stop of [kind]; a dismiss asked for during an escalation hides it. */
    fun stop(kind: StopKind, sent: Boolean, onOpen: (String?) -> Unit = {}, onDismiss: () -> Unit = {}) {
        // Not behind the write lock: a dismiss closes stdin under a write in
        // progress rather than waiting for it.
        val begin = synchronized(this) {
            if (stopping != null) return@synchronized false
            this.onOpen = onOpen
            this.onDismiss = onDismiss
            stopping = StopState(kind, alive = process?.isAlive == true, turnInFlight = inFlight, sessionId = sessionId.takeIf { sent })
            true
        }
        val event = when {
            begin -> StopEvent.Begin
            kind == StopKind.Dismiss -> StopEvent.Hidden
            else -> return
        }
        Assistant.runs.launch { feed(event) }
    }

    /** Set by a hide; the tab is not opened after one, however late it lands. */
    @Volatile private var hiddenDuringStop = false

    /** Home or the lock screen hid the panel during an escalation. */
    fun hidden() {
        hiddenDuringStop = true
        Assistant.runs.launch { feed(StopEvent.Hidden) }
    }

    private suspend fun running(): Process? {
        process?.takeIf { it.isAlive }?.let { return it }
        if (synchronized(this) { stopping != null }) return null
        val started = runCatching { startProcess(backend.headless(sessionId, backend.hasTranscript(home, sessionId))) }
            .onFailure { failure ->
                Log.w(TAG, "the agent did not start", failure)
                main { onExit(-1, listOf(failure.message ?: failure.toString())) }
            }
            .getOrNull() ?: return null
        // A stop that began while this one was starting found nothing alive and
        // has finished; what it did not see is ended here.
        val stopped = synchronized(this) {
            if (stopping == null) process = started
            stopping != null
        }
        if (stopped) {
            started.destroyForcibly()
            return null
        }
        val reader = Assistant.runs.launch { read(started) }
        Assistant.runs.launch { drainErrors(started) }
        Assistant.runs.launch {
            val code = runInterruptible { started.waitFor() }
            reader.join()
            inFlight = false
            if (synchronized(this@HeadlessRun) { stopping != null }) feed(StopEvent.Exited)
            else main { onExit(code, synchronized(tail) { tail.toList() }) }
        }
        return started
    }

    private fun read(process: Process) = drain(process.inputStream) { line ->
        val event = backend.parseEvent(line)
        if (event == HeadlessEvent.Malformed) Log.w(TAG, "a line of ${line.length} characters is not an event")
        if (event is HeadlessEvent.Finished) {
            inFlight = false
            feed(StopEvent.Result)
        }
        main { onEvent(event) }
    }

    private fun drainErrors(process: Process) = drain(process.errorStream) { line ->
        // headless.sh names the started process's pid before the agent runs.
        val named = pidOf(line)
        if (named != null) {
            pid = named
            return@drain
        }
        remember(line)
    }

    private fun remember(line: String) = synchronized(tail) {
        tail.addLast(line)
        if (tail.size > STDERR_LINES) tail.removeFirst()
    }

    /**
     * Every line of [stream] to [onLine]. A stop closes the streams under a
     * blocked read, which throws; that ends the stream like EOF.
     */
    private fun drain(stream: InputStream, onLine: (String) -> Unit) {
        try {
            stream.bufferedReader().forEachLine(onLine)
        } catch (_: IOException) {
        }
    }

    private fun feed(event: StopEvent) {
        val (kind, actions) = synchronized(this) {
            val actions = feedLocked(event)
            stopping?.kind to actions
        }
        actions.forEach { perform(it, kind) }
    }

    private fun feedLocked(event: StopEvent): List<StopAction> {
        val current = stopping ?: return emptyList()
        val (next, actions) = escalation(current, event)
        stopping = next
        return actions
    }

    private fun perform(action: StopAction, kind: StopKind?) {
        val process = process
        when (action) {
            // Behind the write lock, so it never lands inside a message still
            // streaming onto stdin.
            StopAction.Interrupt -> Assistant.runs.launch {
                lock.withLock {
                    runCatching {
                        process?.outputStream?.let { backend.writeInterrupt(it, UUID.randomUUID().toString()) }
                    }
                }
            }
            // An escalation closes stdin after the message being written; a
            // dismiss closes it at once, under a write in progress.
            StopAction.CloseStdin ->
                if (kind == StopKind.Escalate) Assistant.runs.launch { lock.withLock { runCatching { process?.outputStream?.close() } } }
                else runCatching { process?.outputStream?.close() }
            StopAction.Terminate -> {
                signalChildren(SIGTERM)
                process?.destroy()
            }
            StopAction.Kill -> {
                signalChildren(android.os.Process.SIGNAL_KILL)
                process?.destroyForcibly()
            }
            is StopAction.After -> Assistant.runs.launch {
                delay(action.millis)
                feed(StopEvent.Timer(action.generation))
            }
            is StopAction.Settle -> Assistant.runs.launch {
                settle(action.sessionId)
                feed(StopEvent.Settled)
            }
            // The tab is started while the panel is still shown, and the panel
            // dismissed only after. A hide during the settle has already made
            // the sequencer finish without an Open.
            is StopAction.Open -> main {
                // A hide between Settled and this block leaves the tab unopened.
                if (!hiddenDuringStop) {
                    onOpen(action.sessionId)
                    onDismiss()
                }
            }
            // The sequencer puts Dismiss only right after Open, which posts it.
            StopAction.Dismiss -> {}
        }
    }

    /**
     * [signal] to every child of the started process. Under the x86_64 tracer
     * the started process is the tracer and the agent its child: a signal to the
     * tracer kills the agent through `PTRACE_O_EXITKILL` and never reaches the
     * agent itself.
     */
    private fun signalChildren(signal: Int) {
        val parent = pid ?: return
        val table = ProcTable()
        table.pids().filter { table.stat(it)?.ppid == parent }.forEach { android.os.Process.sendSignal(it, signal) }
    }

    /**
     * Waits, up to [SETTLE_WAIT], for the roster to stop naming [sessionId]:
     * a tab refuses a conversation the roster names live elsewhere.
     */
    private suspend fun settle(sessionId: String?) {
        sessionId ?: return
        Roster.start(home)
        withTimeoutOrNull(SETTLE_WAIT) {
            while (Roster.readNow().any { it.sessionId == sessionId }) delay(100)
        }
    }

    private fun main(block: () -> Unit) {
        Assistant.runs.launch(Dispatchers.Main) { block() }
    }

    private companion object {
        const val STDERR_LINES = 20
        const val SETTLE_WAIT = 1_000L
        /** `android.os.Process` names SIGNAL_KILL and SIGNAL_QUIT only. */
        const val SIGTERM = 15
    }
}

/** The pid in the `harness-pid <n>` line headless.sh writes to stderr first, or null. */
internal fun pidOf(line: String): Int? =
    line.takeIf { it.startsWith("harness-pid ") }?.removePrefix("harness-pid ")?.trim()?.toIntOrNull()

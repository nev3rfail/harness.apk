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
    /** Whether the stop interrupted the turn in flight, so its result reads as interrupted. */
    @Volatile private var interruptSent = false
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
        interruptSent = false
        Assistant.runs.launch {
            lock.withLock {
                val process = running()
                if (process != null) {
                    // Set after the process is the current one, so the exit of
                    // one that ended before this send does not clear it.
                    inFlight = true
                    runCatching { backend.writeMessage(process.outputStream, texts, images) }
                        .onFailure { failure ->
                            // Part of a line may be on stdin, so the process is
                            // ended and the next send starts another. Its exit
                            // shows the failure with the rest of stderr.
                            Log.w(TAG, "the message did not reach the agent", failure)
                            remember("the message did not reach the agent: ${failure.message ?: failure}")
                            kill(process)
                        }
                }
                images.forEach { it.file.delete() }
            }
        }
    }

    /** Starts the stop of [kind]; a dismiss asked for during an escalation hides it. */
    fun stop(kind: StopKind, sent: Boolean, onOpen: (String?) -> Unit = {}, onDismiss: () -> Unit = {}) {
        val begin = synchronized(this) {
            if (stopping != null) {
                // A dismiss during a stop is a hide: the tab is not opened after it.
                if (kind == StopKind.Dismiss) hiddenDuringStop = true
                return@synchronized false
            }
            this.onOpen = onOpen
            this.onDismiss = onDismiss
            stopping = StopState(kind, alive = process?.alive == true, turnInFlight = inFlight, sessionId = sessionId.takeIf { sent })
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
        synchronized(this) {
            if (stopping == null) return
            hiddenDuringStop = true
        }
        Assistant.runs.launch { feed(StopEvent.Hidden) }
    }

    private suspend fun running(): Process? {
        // A process being stopped takes no more messages.
        if (synchronized(this) { stopping != null }) return null
        process?.takeIf { it.alive }?.let { return it }
        pid = null
        synchronized(tail) { tail.clear() }
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
            kill(started)
            return null
        }
        val reader = Assistant.runs.launch { read(started) }
        val errors = Assistant.runs.launch { drainErrors(started) }
        Assistant.runs.launch {
            val code = runInterruptible { started.waitFor() }
            // A descendant still holding the pipes keeps a read blocked past the
            // exit; closing them ends it.
            if (withTimeoutOrNull(READ_WAIT) { reader.join(); errors.join() } == null) {
                runCatching { started.inputStream.close() }
                runCatching { started.errorStream.close() }
                reader.join()
                errors.join()
            }
            // A process the run has replaced says nothing about the current one.
            if (process !== started) return@launch
            inFlight = false
            if (synchronized(this@HeadlessRun) { stopping != null }) {
                feed(StopEvent.Exited)
                killSurvivors()
            } else {
                // A process that exits on its own takes the tools' processes
                // seen at its turns' ends with it.
                killSurvivors()
                main { onExit(code, synchronized(tail) { tail.toList() }) }
            }
        }
        return started
    }

    private fun read(process: Process) = drain(process.inputStream) { line ->
        val event = backend.parseEvent(line, interruptSent)
        if (event == HeadlessEvent.Malformed) Log.w(TAG, "a line of ${line.length} characters is not an event")
        if (event is HeadlessEvent.Finished) {
            // A tool's process still running at the turn's end is reparented
            // once the agent exits, so it is noted while it is under the tree.
            witness(descendantsNow())
            inFlight = false
            feed(StopEvent.Result)
        }
        main { onEvent(event) }
    }

    private fun drainErrors(process: Process) {
        var named = false
        drain(process.errorStream) { line ->
            // headless.sh names the started process's pid on its first line,
            // before the agent runs; no later line is read as a pid.
            if (!named) {
                named = true
                pidOf(line)?.let {
                    if (this.process === process) pid = it
                    return@drain
                }
            }
            remember(line)
        }
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
                        process?.outputStream?.let {
                            interruptSent = true
                            backend.writeInterrupt(it, UUID.randomUUID().toString())
                        }
                    }
                }
            }
            // An escalation closes stdin after the message being written. A
            // dismiss closes it at once, on a coroutine of its own: stdin is a
            // synchronized stream, so the close waits behind a write blocked on
            // a full pipe, and the signals and the kill timer must not.
            StopAction.CloseStdin -> {
                // The agent may exit on the close, and its tools' processes
                // then have no parent to be found under.
                witness(descendantsNow())
                if (kind == StopKind.Escalate) Assistant.runs.launch { lock.withLock { runCatching { process?.outputStream?.close() } } }
                else Assistant.runs.launch { runCatching { process?.outputStream?.close() } }
            }
            // destroy() closes stdin too, so it runs on a coroutine of its own as well.
            StopAction.Terminate -> {
                signalChildren(SIGTERM)
                Assistant.runs.launch { process?.destroy() }
            }
            StopAction.Kill -> {
                signalChildren(android.os.Process.SIGNAL_KILL)
                killSurvivors()
                Assistant.runs.launch { process?.let(::kill) }
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
        }
    }

    /**
     * [signal] to every descendant of the started process while it is alive, so
     * the pid it named is not one reused since. Under the x86_64 tracer the
     * started process is the tracer and the agent its child: a signal to the
     * tracer kills the agent through `PTRACE_O_EXITKILL` and never reaches the
     * agent itself. A tool's own processes are the agent's descendants.
     */
    private fun signalChildren(signal: Int) {
        val found = descendantsNow()
        witness(found)
        found.keys.forEach { android.os.Process.sendSignal(it, signal) }
    }

    /**
     * SIGKILL to [target] by the pid headless.sh named, while it runs, so the
     * pid is still its own; [Process.destroy] when no pid was named for it.
     */
    private fun kill(target: Process) {
        val named = pid.takeIf { process === target }
        if (named != null && target.alive) android.os.Process.sendSignal(named, android.os.Process.SIGNAL_KILL)
        else target.destroy()
    }

    /** Every descendant of the started process while it is alive, pid to start time. */
    private fun descendantsNow(): Map<Int, Long> {
        val parent = pid ?: return emptyMap()
        if (process?.alive != true) return emptyMap()
        val table = ProcTable()
        val stats = table.pids().mapNotNull { child -> table.stat(child)?.let { child to it } }.toMap()
        return descendants(parent, stats.mapValues { it.value.ppid }).associateWith { stats.getValue(it).procStart }
    }

    /**
     * The descendants seen at each turn's end and during a stop, pid to start
     * time. A tool's process outlives the agent once it is reparented, so the
     * stop and an exit of the agent's own end it by this list rather than by
     * the tree.
     */
    private val seen = HashMap<Int, Long>()

    private fun witness(found: Map<Int, Long>) = synchronized(seen) { seen.putAll(found) }

    /** SIGKILL to every process [seen] that is still the one seen: same pid, same start time. */
    private fun killSurvivors() {
        val table = ProcTable()
        val survivors = synchronized(seen) { seen.toMap().also { seen.clear() } }
        survivors.forEach { (child, start) ->
            if (table.stat(child)?.procStart == start) android.os.Process.sendSignal(child, android.os.Process.SIGNAL_KILL)
        }
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
        /** How long the readers may outlast the exit before their pipes are closed. */
        const val READ_WAIT = 1_000L
        /** `android.os.Process` names SIGNAL_KILL and SIGNAL_QUIT only. */
        const val SIGTERM = 15
    }
}

/** Whether the process runs: `exitValue` throws until it has exited. */
private val Process.alive: Boolean get() = runCatching { exitValue(); false }.getOrDefault(true)

/** The pid in the `harness-pid <n>` line headless.sh writes to stderr first, or null. */
internal fun pidOf(line: String): Int? =
    line.takeIf { it.startsWith("harness-pid ") }?.removePrefix("harness-pid ")?.trim()?.toIntOrNull()

/** Every process under [parent] in [parents] (pid to parent pid), at any depth. */
internal fun descendants(parent: Int, parents: Map<Int, Int>): List<Int> {
    val children = parents.entries.groupBy({ it.value }, { it.key })
    val found = LinkedHashSet<Int>()
    val queue = ArrayDeque(listOf(parent))
    while (queue.isNotEmpty()) {
        children[queue.removeFirst()]?.forEach { if (it != parent && found.add(it)) queue.addLast(it) }
    }
    return found.toList()
}

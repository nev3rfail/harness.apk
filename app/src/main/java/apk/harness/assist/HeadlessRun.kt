package apk.harness.assist

import android.util.Log
import apk.harness.agents.AgentBackend
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One conversation's agent process, started at show and stopped at dismiss. */
class HeadlessRun(
    private val backend: AgentBackend,
    private val home: File,
    val sessionId: String,
    private val startProcess: suspend (List<String>) -> Process,
) {
    private val started = CompletableDeferred<Process?>()
    private val tail = ArrayDeque<String>()
    @Volatile var pid: Int? = null
        private set

    fun begin() {
        Assistant.runs.launch {
            val process = runCatching { startProcess(backend.headless(sessionId, backend.hasTranscript(home, sessionId))) }
                .onFailure { Log.w(TAG, "the agent did not start", it) }
                .getOrNull()
            started.complete(process)
            process ?: return@launch
            Log.i(TAG, "started")
            launch { drain(process.inputStream) { Log.i(TAG, "out $it") } }
            launch {
                drain(process.errorStream) { line ->
                    // headless.sh names the pid of the process started, before the agent runs.
                    val named = pidOf(line)
                    if (named != null) {
                        pid = named
                        Log.i(TAG, "started pid $named")
                        return@drain
                    }
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > STDERR_LINES) tail.removeFirst()
                    }
                }
            }
            launch { Log.i(TAG, "exit ${process.waitFor()}") }
        }
    }

    /** Nobody is looking: stdin closed and SIGTERM at once, SIGKILL after 2 s. */
    fun dismiss() {
        Assistant.runs.launch {
            val process = started.await() ?: return@launch
            runCatching { process.outputStream.close() }
            process.destroy()
            delay(2_000)
            if (process.isAlive) process.destroyForcibly()
        }
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

    private companion object {
        const val STDERR_LINES = 20
    }
}

/** The pid in the `harness-pid <n>` line headless.sh writes to stderr first, or null. */
internal fun pidOf(line: String): Int? =
    line.takeIf { it.startsWith("harness-pid ") }?.removePrefix("harness-pid ")?.trim()?.toIntOrNull()

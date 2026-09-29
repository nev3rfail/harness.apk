package apk.harness.assist

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Copied(val files: List<File>, val refused: List<Refusal>)

/**
 * [streams] copied into [into], each under a safe, unique name, within the
 * limits before and during the copy. A file that crosses a limit mid-copy, or
 * cannot be read, is deleted and refused. Cancelling stops the open or the
 * read in flight.
 */
suspend fun copyIn(resolver: ContentResolver, streams: List<HandedStream>, into: File): Copied = withContext(Dispatchers.IO) {
    val plan = planCopies(streams.map { it.size })
    val refused = plan.refused.mapTo(ArrayList()) { refusal(streams[it], OVER) }
    val files = ArrayList<File>()
    val taken = HashSet<String>()
    var share = 0L
    if (plan.copy.isNotEmpty()) into.mkdirs()
    for ((position, index) in plan.copy.withIndex()) {
        val stream = streams[index]
        val name = uniqueName(safeName(stream.name), taken).also { taken += it }
        val file = File(into, name)
        when (val outcome = copyOne(resolver, stream, file, share)) {
            is Outcome.Done -> { files += file; share += outcome.bytes }
            Outcome.Unreadable -> { file.delete(); refused += refusal(stream, UNREADABLE) }
            is Outcome.Over -> {
                file.delete()
                refused += refusal(stream, OVER)
                if (outcome.limit == Limit.Share) {
                    plan.copy.drop(position + 1).forEach { refused += refusal(streams[it], OVER) }
                    break
                }
            }
        }
    }
    Copied(files, refused)
}

private sealed interface Outcome {
    data class Done(val bytes: Long) : Outcome
    data class Over(val limit: Limit) : Outcome
    data object Unreadable : Outcome
}

private suspend fun copyOne(resolver: ContentResolver, stream: HandedStream, file: File, shareBefore: Long): Outcome = runCatching {
    readShared({ resolver.openAssetFileDescriptor(Uri.parse(stream.uri), "r", it) }) { descriptor ->
        descriptor.createInputStream().use { source ->
            file.outputStream().use { out ->
                val buffer = ByteArray(CHUNK)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    bytes += read
                    limitCrossed(bytes, shareBefore + bytes)?.let { return@readShared Outcome.Over(it) }
                    out.write(buffer, 0, read)
                }
                Outcome.Done(bytes)
            }
        }
    } ?: Outcome.Unreadable
}.getOrElse { failure ->
    file.delete()
    if (failure is CancellationException) throw failure
    Outcome.Unreadable
}

/**
 * The descriptor [open] gives, handed to [read], or null when it gives none.
 * A cancel of the caller cancels the open through its signal and closes the
 * descriptor from another thread, which ends a read blocked on a sender's pipe.
 */
suspend fun <T> readShared(open: (CancellationSignal) -> AssetFileDescriptor?, read: suspend (AssetFileDescriptor) -> T): T? {
    val signal = CancellationSignal()
    val descriptor = closedOnCancel({ signal.cancel() }) { open(signal) } ?: return null
    return descriptor.use { closedOnCancel(it) { read(it) } }
}

/**
 * [block] with [resource] closed from another thread once the caller is
 * cancelled while [block] runs; a [block] that ends leaves it open. The
 * failure of a read the close ended is thrown as the cancel.
 */
suspend fun <T> closedOnCancel(resource: Closeable, block: suspend () -> T): T = coroutineScope {
    val ended = AtomicBoolean(false)
    // Undispatched, so its finally runs even for a caller cancelled before it starts.
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!ended.get()) runCatching { resource.close() }
        }
    }
    try {
        block()
    } catch (failure: Throwable) {
        ensureActive()
        throw failure
    } finally {
        ended.set(true)
        closer.cancel()
    }
}

private fun refusal(stream: HandedStream, why: String) = Refusal(safeName(stream.name), why, stream.size?.takeIf { it >= 0 })

private const val CHUNK = 64 * 1024
private const val OVER = "over the limit of 100 MB a file and 500 MB a share; not copied"
private const val UNREADABLE = "could not be read; not copied"

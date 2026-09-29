package apk.harness.assist

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
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
 * cannot be read, is refused and leaves no file. Cancelling stops the open or the
 * read in flight.
 */
suspend fun copyIn(resolver: ContentResolver, streams: List<SharedStream>, into: File): Copied = withContext(Dispatchers.IO) {
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
            Outcome.Unreadable -> refused += refusal(stream, UNREADABLE)
            is Outcome.Over -> {
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

sealed interface Outcome {
    data class Done(val bytes: Long) : Outcome
    data class Over(val limit: Limit) : Outcome
    data object Unreadable : Outcome
}

private suspend fun copyOne(resolver: ContentResolver, stream: SharedStream, file: File, shareBefore: Long): Outcome =
    copyOpened({ resolver.openAssetFileDescriptor(stream.uri, "r", it) }, file, shareBefore)

/**
 * What [open] gives copied into [file], counting the bytes against the file
 * limit and, after [shareBefore], the share's. A copy that crosses a limit,
 * or cannot be read, leaves no file. Cancelling stops the open or the read in
 * flight.
 */
suspend fun copyOpened(open: (CancellationSignal) -> AssetFileDescriptor?, file: File, shareBefore: Long): Outcome = runCatching {
    readShared(open) { descriptor ->
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
}.also { if (it !is Outcome.Done) file.delete() }

/**
 * The descriptor [open] gives, handed to [read], or null when it gives none.
 * A cancel of the caller cancels the open through its signal and closes the
 * descriptor from another thread, which ends a read blocked on a sender's pipe.
 * The descriptor is this function's: it is closed when [read] ends, and a
 * descriptor opened for a caller cancelled by then is closed at once.
 */
suspend fun <T> readShared(open: (CancellationSignal) -> AssetFileDescriptor?, read: suspend (AssetFileDescriptor) -> T): T? {
    val signal = CancellationSignal()
    val descriptor = openedUnlessCancelled({ signal.cancel() }) { open(signal) } ?: return null
    return descriptor.use { closedOnCancel(it) { read(it) } }
}

/**
 * What [open] gives, with [cancel] closed from another thread once the caller
 * is cancelled while it runs. A resource [open] returns to a caller cancelled
 * by then is closed rather than returned, since the caller never gets it.
 */
suspend fun <R : Closeable> openedUnlessCancelled(cancel: Closeable, open: suspend () -> R?): R? {
    var opened: R? = null
    try {
        return closedOnCancel(cancel) { open().also { opened = it } }
    } catch (failure: Throwable) {
        runCatching { opened?.close() }
        throw failure
    }
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

private fun refusal(stream: SharedStream, why: String) = Refusal(safeName(stream.name), why, stream.size?.takeIf { it >= 0 })

private const val CHUNK = 64 * 1024
private const val OVER = "over the limit of 100 MB a file and 500 MB a share; not copied"
private const val UNREADABLE = "could not be read; not copied"

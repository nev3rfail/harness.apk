package apk.harness.assist

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Copied(val files: List<File>, val refused: List<Refusal>)

/**
 * [streams] copied into [into], each under a safe, unique name, within the
 * limits before and during the copy. A file that crosses a limit mid-copy, or
 * cannot be read, is deleted and refused. Cancelling stops at the next chunk.
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

private suspend fun copyOne(resolver: ContentResolver, stream: HandedStream, file: File, shareBefore: Long): Outcome {
    val input = runCatching { resolver.openInputStream(Uri.parse(stream.uri)) }.getOrNull() ?: return Outcome.Unreadable
    return runCatching {
        input.use { source ->
            file.outputStream().use { out ->
                val buffer = ByteArray(CHUNK)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    bytes += read
                    limitCrossed(bytes, shareBefore + bytes)?.let { return@runCatching Outcome.Over(it) }
                    out.write(buffer, 0, read)
                }
                Outcome.Done(bytes)
            }
        }
    }.getOrElse { failure ->
        file.delete()
        if (failure is CancellationException) throw failure
        Outcome.Unreadable
    }
}

private fun refusal(stream: HandedStream, why: String) = Refusal(safeName(stream.name), why, stream.size)

private const val CHUNK = 64 * 1024
private const val OVER = "over the limit of 100 MB a file and 500 MB a share; not copied"
private const val UNREADABLE = "could not be read; not copied"

package apk.harness.assist

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [intent] read into a [Handed]: the selected text, `EXTRA_TEXT` and
 * `EXTRA_SUBJECT` as char sequences, and the streams -- the `ClipData` items,
 * then `EXTRA_STREAM` for SEND or its list for SEND_MULTIPLE -- as the `Uri`
 * objects the intent delivered. Each stream is decided by [streamRefusal],
 * with [isOwn] and the grant [sharedUris] reads, and no provider is asked
 * about any of them here; [describe] asks, off the main thread.
 */
fun handedOf(intent: Intent, isOwn: (authority: String) -> Boolean): Handed<Uri> {
    val action = intent.action.orEmpty()
    val clip = intent.clipData
    val clipItems = (0 until (clip?.itemCount ?: 0)).map { clip!!.getItemAt(it) }
    val extras: List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
    val flagged = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    val uris = if (action == Intent.ACTION_PROCESS_TEXT) emptyList()
    else sharedUris(extras, clipItems.mapNotNull { it.uri }, flagged)
    return Handed(
        action = action,
        selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
        clipText = if (action == Intent.ACTION_SEND) clipItems.firstNotNullOfOrNull { it.text?.toString() } else null,
        // getAuthority() is the decoded authority, which the resolver opens by.
        streams = decided(uris) { uri, granted -> streamRefusal(uri.scheme, uri.authority, granted, isOwn, asWritten(uri)) }
            .map { (uri, refused) -> HandedStream(uri, safeName(uri.lastPathSegment), refused) },
    )
}

/** Whether [uri], printed and parsed again, has the same scheme, decoded authority, decoded path and query. */
private fun asWritten(uri: Uri): Boolean {
    val parsed = Uri.parse(uri.toString())
    return parsed.scheme == uri.scheme && parsed.authority == uri.authority && parsed.path == uri.path && parsed.query == uri.query
}

/**
 * [uri]'s type from `ContentResolver.getType` and its name and size from one
 * `OpenableColumns` query, on the IO dispatcher. A cancel cancels the query
 * through its signal; `getType` takes none. What the provider does not answer
 * is null.
 */
suspend fun describe(resolver: ContentResolver, uri: Uri): SharedStream = withContext(Dispatchers.IO) {
    val type = runCatching { resolver.getType(uri) }.getOrNull()
    var name: String? = null
    var size: Long? = null
    val signal = CancellationSignal()
    try {
        openedUnlessCancelled({ signal.cancel() }) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)
        }?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameAt >= 0 && !cursor.isNull(nameAt)) name = cursor.getString(nameAt)
                if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt)
            }
        }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
    }
    SharedStream(uri, type, name, size)
}

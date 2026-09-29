package apk.harness.assist

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat

/**
 * [intent] read into a [Handed]: the selected text, `EXTRA_TEXT` and
 * `EXTRA_SUBJECT` as char sequences, and the streams -- the `ClipData` items,
 * then `EXTRA_STREAM` for SEND or its list for SEND_MULTIPLE -- each with its
 * type and one `OpenableColumns` query for its name and size. A stream
 * [streamRefusal] refuses, by [isOwn] and the grant [sharedUris] reads, is
 * refused before any resolver call.
 */
fun handedOf(intent: Intent, resolver: ContentResolver, isOwn: (authority: String) -> Boolean): Handed<Uri> {
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
        streams = uris.map { (uri, granted) -> stream(resolver, uri, granted, isOwn) },
    )
}

private fun stream(resolver: ContentResolver, uri: Uri, granted: Boolean, isOwn: (String) -> Boolean): HandedStream<Uri> {
    // getAuthority() is the decoded authority, which the resolver opens by.
    streamRefusal(uri.scheme, uri.authority, granted, isOwn, asWritten(uri))?.let { return HandedStream(uri, null, safeName(uri.lastPathSegment), null, it) }
    val type = runCatching { resolver.getType(uri) }.getOrNull()
    var name: String? = null
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameAt >= 0 && !cursor.isNull(nameAt)) name = cursor.getString(nameAt)
                if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt)
            }
        }
    }
    return HandedStream(uri, type, name, size)
}

/** Whether [uri], printed and parsed again, has the same scheme, decoded authority, decoded path and query. */
private fun asWritten(uri: Uri): Boolean {
    val parsed = Uri.parse(uri.toString())
    return parsed.scheme == uri.scheme && parsed.authority == uri.authority && parsed.path == uri.path && parsed.query == uri.query
}

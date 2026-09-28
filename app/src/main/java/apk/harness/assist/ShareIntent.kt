package apk.harness.assist

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat

/**
 * [intent] read into a [Handed]: the selected text, `EXTRA_TEXT` and
 * `EXTRA_SUBJECT` as char sequences, and the streams -- `EXTRA_STREAM` for
 * SEND, the list for SEND_MULTIPLE, the `ClipData` items when those are empty
 * -- each with its type and one `OpenableColumns` query for its name and size.
 */
fun handedOf(intent: Intent, resolver: ContentResolver): Handed {
    val action = intent.action.orEmpty()
    val clip = intent.clipData
    val clipItems = (0 until (clip?.itemCount ?: 0)).map { clip!!.getItemAt(it) }
    val extras: List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
    val uris = if (action == Intent.ACTION_PROCESS_TEXT) emptyList() else extras.ifEmpty { clipItems.mapNotNull { it.uri } }
    return Handed(
        action = action,
        selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
        clipText = if (action == Intent.ACTION_SEND) clipItems.firstNotNullOfOrNull { it.text?.toString() } else null,
        streams = uris.map { stream(resolver, it) },
    )
}

private fun stream(resolver: ContentResolver, uri: Uri): HandedStream {
    // Only a content URI is asked about; askRequest refuses every other scheme.
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return HandedStream(uri.toString(), null, null, null)
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
    return HandedStream(uri.toString(), type, name, size)
}

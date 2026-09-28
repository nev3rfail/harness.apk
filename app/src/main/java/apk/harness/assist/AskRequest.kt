package apk.harness.assist

/** A stream not read or not copied, named as the panel shows it under the attachments. */
data class Refusal(val name: String, val why: String, val size: Long?)

const val NOT_SHARED = "not a file another app shared; not read"
const val OWN_FILES = "one of this app's own files; not read"

/** What the panel opens with. Nothing is sent until the operator sends. */
data class AskRequest(
    /** Selected text, drawn above the field. */
    val quote: String?,
    val field: String,
    val images: List<HandedStream>,
    /** Every other stream, copied into the inbox and named by path. */
    val files: List<HandedStream>,
    val refused: List<Refusal> = emptyList(),
) {
    // `this.field`: a bare `field` in a getter is the backing field.
    val empty: Boolean get() = quote == null && this.field.isEmpty() && images.isEmpty() && files.isEmpty()
}

/**
 * [handed] as the panel's opening state. A stream is read only through a
 * `content:` URI of another app's provider: any other scheme, and an authority
 * [isOwn] answers for, would be read with this app's own rights -- its
 * `<package>.files` provider exposes the whole home -- rather than the
 * sender's grant. Those are refused and named with the reason.
 */
fun askRequest(handed: Handed, isOwn: (authority: String) -> Boolean): AskRequest {
    if (handed.action == ACTION_PROCESS_TEXT) {
        return AskRequest(handed.selected?.takeIf { it.isNotBlank() }, "", emptyList(), emptyList())
    }
    val text = (handed.text ?: handed.clipText)?.takeIf { it.isNotBlank() }
    val subject = handed.subject?.takeIf { it.isNotBlank() }
    val refused = ArrayList<Refusal>()
    val streams = handed.streams.filter { stream ->
        val why = when {
            !stream.uri.startsWith("content://") -> NOT_SHARED
            isOwn(stream.uri.removePrefix("content://").substringBefore('/').substringAfterLast('@')) -> OWN_FILES
            else -> null
        }
        if (why != null) refused += Refusal(stream.name ?: stream.uri, why, stream.size)
        why == null
    }
    val (images, files) = streams.partition { it.type?.startsWith("image/") == true }
    return AskRequest(null, listOfNotNull(subject, text).joinToString("\n"), images, files, refused)
}

/** Which of a share's images, by index, go as blocks and which are copied as files. */
data class ShareImages(val blocks: List<Int>, val files: List<Int>)

/**
 * A share's images by their [encoded] sizes, null for one that did not decode.
 * A share opens a new conversation, so nothing was sent before it; past the
 * budget or the twentieth, and undecodable, go as files.
 */
fun shareImages(encoded: List<Long?>): ShareImages {
    val decoded = encoded.indices.filter { encoded[it] != null }
    val split = imageBudget(emptyList(), decoded.map { base64Size(encoded[it]!!) })
    val blocks = split.blocks.map { decoded[it] }
    return ShareImages(blocks, encoded.indices.filter { it !in blocks })
}

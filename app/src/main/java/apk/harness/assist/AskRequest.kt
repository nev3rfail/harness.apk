package apk.harness.assist

/** A stream not read or not copied, named as the panel shows it under the attachments. */
data class Refusal(val name: String, val why: String, val size: Long?)

const val NOT_SHARED = "not a file another app shared; not read"
const val OWN_FILES = "one of this app's own files; not read"
const val NOT_GRANTED = "the sharing app did not grant access to this file; not read"
const val NOT_AS_WRITTEN = "its address reads as another file once written out; not read"
const val TOO_MANY = "past the first 100 files of one share; not read"
const val SENDER_CANNOT_READ = "the sharing app may not read this file itself; not read"
const val OPENS_WITHOUT_GRANT = "this app could open it without the sharing app's grant; not read"

/** At most this many of a share's streams are decided and read. */
const val STREAM_LIMIT = 100

/**
 * What the panel opens with. Nothing is sent until the operator sends. Each of
 * [streams] passed [streamRefusal]: a `content:` URI that reads the same once
 * printed and parsed, of another app's provider, below Android 15 in
 * `ClipData` under the grant flag, and allowed by the sender's-access question
 * the activity asks.
 * Its type, name and size are not known yet.
 */
data class AskRequest<out U>(
    /** Selected text, drawn above the field. */
    val quote: String?,
    val field: String,
    /** The streams to read: images become attachments, every other one is copied into the inbox. */
    val streams: List<HandedStream<U>>,
    val refused: List<Refusal> = emptyList(),
) {
    // `this.field`: a bare `field` in a getter is the backing field.
    val empty: Boolean get() = quote == null && this.field.isEmpty() && streams.isEmpty()
}

/** Whether a stream of [type] goes as an image. */
fun isImage(type: String?): Boolean = type?.startsWith("image/") == true

/**
 * [named] each with its refusal from [refusal], or [TOO_MANY] past the first
 * [STREAM_LIMIT], which [refusal] is not asked about.
 */
fun <U> decided(named: List<Pair<U, Boolean>>, refusal: (U, Boolean) -> String?): List<Pair<U, String?>> =
    named.mapIndexed { index, (uri, granted) -> uri to if (index >= STREAM_LIMIT) TOO_MANY else refusal(uri, granted) }

/**
 * The stream URIs a share names, [clip] first, each with whether it counts as
 * granted: a `ClipData` URI of an intent [flagged] with
 * `FLAG_GRANT_READ_URI_PERMISSION`. The platform fills `ClipData` from
 * `EXTRA_STREAM` for an ordinary `SEND` or `SEND_MULTIPLE`; a URI only in
 * [extras] counts as not granted. The flag says only that the sender asked for
 * a grant, not that the sender may read the URI itself; [streamRefusal] asks
 * that last. Where [senderDecides], as on Android 15 and later, that last
 * question is the whole test, and every URI counts as granted.
 */
fun <U> sharedUris(extras: List<U>, clip: List<U>, flagged: Boolean, senderDecides: Boolean = false): List<Pair<U, Boolean>> =
    (clip + extras).distinct().map { it to (senderDecides || flagged && it in clip) }

/**
 * Why a stream is not read, or null, from its [scheme] and [authority] as
 * `android.net.Uri` decodes them and whether it came [granted]. A stream is
 * read only through a `content:` URI of another app's provider under the
 * sender's grant: any other scheme, an authority [isOwn] answers for, and a
 * URI with no grant would be read with this app's own rights -- its
 * `<package>.files` provider exposes the whole home, and it can read all
 * files -- rather than the sender's. The resolver opens a provider by the
 * decoded authority with any `<userId>@` prefix dropped, as
 * `ContentProvider.getAuthorityWithoutUserId` drops it, so the check takes the
 * same one. A URI not [asWritten] -- one whose string, parsed again, differs
 * in scheme, authority, path or query, as a `Uri` built with a `/` in its
 * authority does -- is refused before any other question, since the platform
 * checks grants against the parsed string. A stream past every other question
 * is refused for the reason [sender] gives, the sender's own access to it,
 * asked last.
 */
fun streamRefusal(
    scheme: String?,
    authority: String?,
    granted: Boolean,
    isOwn: (authority: String) -> Boolean,
    asWritten: Boolean,
    sender: () -> String?,
): String? {
    val provider = authority?.substringAfterLast('@')
    return when {
        !scheme.equals("content", ignoreCase = true) || provider.isNullOrEmpty() -> NOT_SHARED
        !asWritten -> NOT_AS_WRITTEN
        isOwn(provider) -> OWN_FILES
        !granted -> NOT_GRANTED
        else -> sender()
    }
}

/** A URI's scheme, decoded authority, decoded path and query, as `android.net.Uri` reads them. */
data class UriParts(val scheme: String?, val authority: String?, val path: String?, val query: String?)

/** Whether a URI [delivered] as an object reads the same once printed and [reparsed]. */
fun asWritten(delivered: UriParts, reparsed: UriParts): Boolean = delivered == reparsed

/**
 * Whether this app could open a provider without anyone's grant: it is
 * [exported] and asks no [readPermission], or one the app [holds], or the app
 * holds the read permission of one of its [pathReadPermissions].
 */
fun opensWithoutGrant(
    exported: Boolean,
    readPermission: String?,
    pathReadPermissions: List<String?>,
    holds: (permission: String) -> Boolean,
): Boolean = exported && (readPermission == null || holds(readPermission) || pathReadPermissions.any { it != null && holds(it) })

/** [handed] as the panel's opening state; each stream [HandedStream.refused] is named with its reason. */
fun <U> askRequest(handed: Handed<U>): AskRequest<U> {
    if (handed.action == ACTION_PROCESS_TEXT) {
        return AskRequest(handed.selected?.takeIf { it.isNotBlank() }, "", emptyList())
    }
    val text = (handed.text ?: handed.clipText)?.takeIf { it.isNotBlank() }?.let(::cutWithLine)
    val subject = handed.subject?.takeIf { it.isNotBlank() }?.let(::cutWithLine)
    val (refusedStreams, streams) = handed.streams.partition { it.refused != null }
    val refused = refusedStreams.map { Refusal(it.name ?: "shared", it.refused!!, null) }
    return AskRequest(null, listOfNotNull(subject, text).joinToString("\n"), streams, refused)
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

/**
 * Whether an image encoded after [encoded] could still go as a block: fewer
 * than twenty are blocks and none that decoded went past the budget. Past
 * that, the share's other images are copied as files without being encoded.
 */
fun roomForBlocks(encoded: List<Long?>): Boolean {
    val split = shareImages(encoded)
    return split.blocks.size < MESSAGE_IMAGES && split.files.all { encoded[it] == null }
}

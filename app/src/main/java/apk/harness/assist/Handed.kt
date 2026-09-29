package apk.harness.assist

/**
 * One stream handed over: its URI, the object the intent delivered, and what
 * the sender's provider says about it. A stream [refused] before any resolver call carries the reason, the URI's
 * last path segment as its name, and no type or size.
 */
data class HandedStream<out U>(val uri: U, val type: String?, val name: String?, val size: Long?, val refused: String? = null)

/** What an intent handed over, read out of it and nothing more. */
data class Handed<out U>(
    val action: String,
    /** `EXTRA_PROCESS_TEXT`. */
    val selected: String? = null,
    /** `EXTRA_TEXT`. */
    val text: String? = null,
    val subject: String? = null,
    /** A `SEND`'s text when it came only in `ClipData`. */
    val clipText: String? = null,
    val streams: List<HandedStream<U>> = emptyList(),
)

const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
const val ACTION_SEND = "android.intent.action.SEND"
const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
val SHARE_ACTIONS = setOf(ACTION_PROCESS_TEXT, ACTION_SEND, ACTION_SEND_MULTIPLE)

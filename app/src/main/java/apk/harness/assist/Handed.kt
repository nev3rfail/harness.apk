package apk.harness.assist

/** One stream handed over: its URI, and what the sender's provider says about it. */
data class HandedStream(val uri: String, val type: String?, val name: String?, val size: Long?)

/** What an intent handed over, read out of it and nothing more. */
data class Handed(
    val action: String,
    /** `EXTRA_PROCESS_TEXT`. */
    val selected: String? = null,
    /** `EXTRA_TEXT`. */
    val text: String? = null,
    val subject: String? = null,
    /** A `SEND`'s text when it came only in `ClipData`. */
    val clipText: String? = null,
    val streams: List<HandedStream> = emptyList(),
)

const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
const val ACTION_SEND = "android.intent.action.SEND"
const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
val SHARE_ACTIONS = setOf(ACTION_PROCESS_TEXT, ACTION_SEND, ACTION_SEND_MULTIPLE)

package apk.harness.agents

/** What one stdout line of a headless run says, as the panel needs it. */
sealed interface HeadlessEvent {
    /** The run names its conversation; written at the start of every turn. */
    data class Started(val sessionId: String) : HeadlessEvent
    /** A new answer segment: the model's next message within the turn. */
    data object MessageStart : HeadlessEvent
    data class TextDelta(val index: Int, val text: String) : HeadlessEvent
    data object Thinking : HeadlessEvent
    /** A tool call: its name and one short line of its input. */
    data class ToolUse(val name: String, val input: String) : HeadlessEvent
    data class Denied(val toolUseId: String, val tool: String, val message: String?) : HeadlessEvent
    data class Retrying(val attempt: Int) : HeadlessEvent
    /** The turn's end. [errors] is what to show when not [ok]. */
    data class Finished(
        val ok: Boolean,
        val text: String?,
        val denials: List<Denial>,
        val errors: List<String>,
        val signedOut: Boolean,
    ) : HeadlessEvent
    data object Ignored : HeadlessEvent
    /** A line that does not parse, such as one launcher.local.sh echoes. */
    data object Malformed : HeadlessEvent
}

/** A tool call the run denied, as the turn's result lists it. */
data class Denial(val toolUseId: String, val tool: String)

/** An image to send, already encoded into [file] under the cache directory. */
data class HeadlessImage(val file: java.io.File, val mediaType: String)

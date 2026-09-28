package apk.harness.agents

import apk.harness.agents.HeadlessEvent.*
import org.json.JSONArray
import org.json.JSONObject

/** One line of Claude Code's `--output-format stream-json`. */
fun parseClaudeEvent(line: String): HeadlessEvent {
    val event = runCatching { JSONObject(line) }.getOrNull() ?: return Malformed
    return when (event.string("type")) {
        "system" -> system(event)
        "stream_event" -> stream(event.optJSONObject("event"))
        "assistant" -> toolUse(event)
        "result" -> result(event)
        else -> Ignored
    }
}

private fun system(event: JSONObject): HeadlessEvent = when (event.string("subtype")) {
    "init" -> event.string("session_id")?.let(::Started) ?: Ignored
    "thinking_tokens" -> Thinking
    "permission_denied" -> Denied(event.string("tool_use_id") ?: "", event.string("tool_name") ?: "", event.string("message"))
    "api_retry" -> Retrying(event.optInt("attempt", 1))
    else -> Ignored
}

private fun stream(event: JSONObject?): HeadlessEvent = when (event?.string("type")) {
    "message_start" -> MessageStart
    "content_block_delta" -> {
        val delta = event.optJSONObject("delta")
        when (delta?.string("type")) {
            "text_delta" -> TextDelta(event.optInt("index", 0), delta.opt("text") as? String ?: "")
            "thinking_delta" -> Thinking
            else -> Ignored
        }
    }
    else -> Ignored
}

// A complete assistant event is one block. Text is read from the deltas, so only
// a tool call is taken from here.
private fun toolUse(event: JSONObject): HeadlessEvent {
    val block = event.optJSONObject("message")?.optJSONArray("content")?.optJSONObject(0) ?: return Ignored
    if (block.string("type") != "tool_use") return Ignored
    return ToolUse(block.string("name") ?: "", shortInput(block.optJSONObject("input")))
}

private fun result(event: JSONObject): HeadlessEvent {
    // The panel's own interrupt ends a turn this way; it is not an error.
    val interrupted = event.string("terminal_reason") == "aborted_streaming"
    val ok = event.opt("is_error") != true || interrupted
    val text = event.string("result")
    val listed = strings(event.optJSONArray("errors"))
    val errors = when {
        ok -> emptyList()
        listed.isEmpty() && text != null -> listOf(text)
        else -> listed
    }
    val denials = objects(event.optJSONArray("permission_denials"))
        .map { Denial(it.string("tool_use_id") ?: "", it.string("tool_name") ?: "") }
    return Finished(ok, text, denials, errors, signedOut = !ok && errors.any(::saysSignedOut))
}

/** Set from the signed-out run the device writes (Task 7). */
private val SIGNED_OUT = listOf("Not logged in", "Please run /login")

private fun saysSignedOut(message: String) = SIGNED_OUT.any { message.contains(it, ignoreCase = true) }

// The field a person would name the call by, for the tools that have one;
// otherwise the input's first text.
private val INPUT_KEYS = listOf("command", "file_path", "path", "pattern", "url", "query")
private const val INPUT_CHARS = 80

private fun shortInput(input: JSONObject?): String {
    input ?: return ""
    val value = INPUT_KEYS.firstNotNullOfOrNull { input.string(it) }
        ?: input.keys().asSequence().firstNotNullOfOrNull { input.string(it) }
        ?: return ""
    val line = value.replace(Regex("\\s+"), " ").trim()
    return if (line.length <= INPUT_CHARS) line else line.take(INPUT_CHARS - 1) + "…"
}

private fun strings(array: JSONArray?): List<String> =
    (0 until (array?.length() ?: 0)).mapNotNull { array!!.opt(it) as? String }

private fun objects(array: JSONArray?): List<JSONObject> =
    (0 until (array?.length() ?: 0)).mapNotNull { array!!.optJSONObject(it) }

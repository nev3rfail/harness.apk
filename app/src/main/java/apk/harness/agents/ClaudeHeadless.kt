package apk.harness.agents

import apk.harness.agents.HeadlessEvent.*
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** One line of Claude Code's `--output-format stream-json`. */
fun parseClaudeEvent(line: String): HeadlessEvent {
    val event = runCatching { JSONObject(line) }.getOrNull() ?: return Malformed
    // A subagent's own events name the tool call that started it; the panel
    // shows only the main conversation.
    if (event.opt("parent_tool_use_id") is String) return Ignored
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
    val text = event.string("result")
    val listed = strings(event.optJSONArray("errors"))
    // The panel's own interrupt ends a turn one of these ways; it is not an
    // error. The diagnostic lines are all such a result lists.
    val interrupted = event.string("terminal_reason") == "aborted_streaming" ||
        event.string("subtype") == "error_during_execution" &&
        listed.isNotEmpty() && listed.all { it.startsWith("[ede_diagnostic]") }
    val ok = event.opt("is_error") != true || interrupted
    val errors = when {
        ok -> emptyList()
        listed.isNotEmpty() -> listed
        text != null -> listOf(text)
        else -> listOf(event.string("subtype") ?: UNNAMED_ERROR)
    }
    val denials = objects(event.optJSONArray("permission_denials"))
        .map { Denial(it.string("tool_use_id") ?: "", it.string("tool_name") ?: "") }
    return Finished(ok, text, denials, errors, signedOut = !ok && errors.any(::saysSignedOut))
}

/** Shown for a failed turn whose result names nothing. */
private const val UNNAMED_ERROR = "The agent reported an error."

/** What the result of a run with no account signed in says. */
private const val SIGNED_OUT = "Not logged in"

private fun saysSignedOut(message: String) = message.contains(SIGNED_OUT, ignoreCase = true)

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

/**
 * One `user` message on [out], streamed: the JSON around each image, and the
 * image's bytes through a base64 encoder over a view of [out] that never closes
 * it, so no message is held whole in memory. An empty text writes no block,
 * which the API refuses. Every image file is opened before the first byte is
 * written, so one that cannot be read throws with nothing written.
 */
fun writeClaudeMessage(out: OutputStream, texts: List<String>, images: List<HeadlessImage>) {
    val streams = ArrayList<InputStream>()
    try {
        images.forEach { streams += it.file.inputStream() }
        writeOpened(out, texts, images.zip(streams))
    } finally {
        streams.forEach { runCatching { it.close() } }
    }
}

private fun writeOpened(out: OutputStream, texts: List<String>, images: List<Pair<HeadlessImage, InputStream>>) {
    fun put(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
    put("""{"type":"user","message":{"role":"user","content":[""")
    var first = true
    fun separate() { if (!first) put(","); first = false }
    for (text in texts) {
        if (text.isEmpty()) continue
        separate()
        put("""{"type":"text","text":${JSONObject.quote(text)}}""")
    }
    for ((image, stream) in images) {
        separate()
        put("""{"type":"image","source":{"type":"base64","media_type":${JSONObject.quote(image.mediaType)},"data":"""")
        // Closing the encoder writes its padding; the view keeps out open.
        Base64.getEncoder().wrap(Unclosed(out)).use { encoder -> stream.copyTo(encoder) }
        put("\"}}")
    }
    put("""]},"parent_tool_use_id":null}""" + "\n")
    out.flush()
}

/** The control request that interrupts the turn in flight, as one line on [out]. */
fun writeClaudeInterrupt(out: OutputStream, requestId: String) {
    val line = JSONObject()
        .put("type", "control_request")
        .put("request_id", requestId)
        .put("request", JSONObject().put("subtype", "interrupt"))
        .toString()
    out.write((line + "\n").toByteArray(Charsets.UTF_8))
    out.flush()
}

/** [out] with a close that only flushes, and chunks passed straight through. */
private class Unclosed(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = out.flush()
}

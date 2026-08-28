package apk.harness.ide

import android.util.Base64
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/** What the app calls itself when it introduces itself to the agent. */
const val APP_NAME = "harness.apk"

/**
 * The chat a call came from, or the absence of one.
 *
 * A tool call arrives with no caller, so each channel is given one: the editor
 * binds its tab's key, and the panel server maps the token it was presented
 * back to the chat it handed that token to. [NO_CHAT] is what a call carries
 * when nothing is asking on a chat's behalf -- the app itself, or a session
 * started outside it that found the app-wide config in its working directory.
 *
 * Zero, because `AgentTabs` counts keys from one, so no chat can ever hold it.
 */
const val NO_CHAT = 0L

/**
 * A secret the agent presents to prove it is the one that read the file naming
 * the port, rather than anything else on the device that found it open.
 */
fun newToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

private const val TOKEN_BYTES = 32

/** The block shape every tool result is made of. */
fun textBlock(text: String): JSONObject =
    JSONObject().put("type", "text").put("text", text)

fun textContent(text: String): JSONObject =
    JSONObject().put("content", JSONArray().put(textBlock(text)))

fun errorContent(message: String): JSONObject =
    JSONObject()
        .put("content", JSONArray().put(textBlock(message)))
        .put("isError", true)

/**
 * One side of an MCP conversation, with no transport of its own.
 *
 * The agent reaches the app two ways -- as the editor it attaches to, and as a
 * server it is configured with -- and the two carry different tools but the same
 * exchange. This is that exchange.
 */
class McpEndpoint(
    private val serverName: String,
    private val definitions: () -> JSONArray,
    /** Runs one tool for one chat: its name, its arguments, and who is asking. */
    private val call: suspend (String, JSONObject, Long) -> JSONObject,
) {

    /**
     * Answers one message, or nothing at all if it was a notification.
     *
     * [owner] is the chat the message came from, which only `tools/call` needs
     * and which is the transport's to know: the editor has one chat for its
     * whole life, and the panel server has one per token. Everything else here
     * answers the same way for everyone.
     */
    suspend fun handle(request: JSONObject, owner: Long): JSONObject? {
        val method = request.optString("method")
        val hasId = request.has("id") && !request.isNull("id")
        val params = request.optJSONObject("params") ?: JSONObject()

        val result: JSONObject = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion", PROTOCOL_VERSION))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", serverName).put("version", VERSION))

            "ping" -> JSONObject()

            "tools/list" -> JSONObject().put("tools", definitions())

            "tools/call" -> call(
                params.optString("name"),
                params.optJSONObject("arguments") ?: JSONObject(),
                owner,
            )

            else -> {
                // Notifications carry no id and want no answer.
                if (!hasId) return null
                return JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", request.get("id"))
                    .put("error", JSONObject()
                        .put("code", METHOD_NOT_FOUND)
                        .put("message", "unknown method: $method"))
            }
        }

        if (!hasId) return null
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", request.get("id"))
            .put("result", result)
    }

    private companion object {
        const val VERSION = "0.1.0"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val METHOD_NOT_FOUND = -32601
    }
}

package apk.harness.ide

import android.util.Base64
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.IProtocol
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Makes the app an IDE that `claude --ide` can attach to.
 *
 * The agent discovers an editor by reading `<port>.lock` files out of
 * `~/.claude/ide`, then opens a WebSocket to that port and speaks MCP over it.
 * So the app listens on the loopback interface, advertises what it can render as
 * MCP tools, and leaves a lockfile naming the port.
 *
 * See docs/ide-protocol.md for where the shape of all this comes from.
 */
class IdeServer(
    private val workspace: File,
    private val lockDirectory: File,
    private val surfaces: Surfaces,
    private val openExternal: (String) -> Boolean,
    private val readFile: (String) -> String,
) {
    private val authToken = newAuthToken()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lockFile: File? = null
    private var server: Server? = null

    val port: Int get() = server?.port ?: 0

    fun start() {
        // Port 0 asks the system for a free one. The lockfile cannot be written
        // until it is known, because its name is the port.
        val listener = Server()
        listener.isReuseAddr = true
        listener.start()
        server = listener

        scope.launch {
            val bound = listener.awaitPort()
            lockDirectory.mkdirs()

            // Only this app writes here, so anything already present belongs to a
            // process that is gone -- the app does not get to tidy up when it is
            // killed. Left alone, the agent would find it first and try to
            // connect to a closed port with a stale token.
            lockDirectory.listFiles { file -> file.name.endsWith(".lock") }
                ?.forEach { it.delete() }

            val file = File(lockDirectory, "$bound.lock")
            file.writeText(
                JSONObject()
                    .put("pid", android.os.Process.myPid())
                    .put("workspaceFolders", JSONArray().put(workspace.absolutePath))
                    .put("ideName", IDE_NAME)
                    .put("transport", "ws")
                    .put("runningInWindows", false)
                    .put("authToken", authToken)
                    .toString(),
            )
            lockFile = file
            Log.i(TAG, "listening on $bound, lockfile ${file.absolutePath}")
        }
    }

    fun stop() {
        lockFile?.delete()
        lockFile = null
        runCatching { server?.stop(CLOSE_TIMEOUT_MS) }
        server = null
        scope.cancel()
    }

    /** Tells the agent what the user has highlighted. */
    fun reportSelection(path: String, startLine: Int, endLine: Int, text: String) {
        val params = JSONObject()
            .put("selection", JSONObject()
                .put("start", JSONObject().put("line", startLine).put("character", 0))
                .put("end", JSONObject().put("line", endLine).put("character", 0)))
            .put("text", text)
            .put("filePath", path)
        notify("selection_changed", params)
    }

    /** Points the agent at a file, the way an editor's "add to chat" does. */
    fun mention(path: String, startLine: Int? = null, endLine: Int? = null) {
        val params = JSONObject().put("filePath", path)
        // Line numbers travel zero-based; the CLI adds one before showing them.
        if (startLine != null) params.put("lineStart", startLine)
        if (endLine != null) params.put("lineEnd", endLine)
        notify("at_mentioned", params)
    }

    private fun notify(method: String, params: JSONObject) {
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()
        server?.connections?.forEach { runCatching { it.send(message) } }
    }

    private inner class Server : WebSocketServer(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        // The agent asks for the `mcp` subprotocol and expects it echoed back.
        listOf(Draft_6455(emptyList<IExtension>(), listOf<IProtocol>(Protocol("mcp")))),
    ) {
        private val bound = CompletableDeferred<Int>()

        suspend fun awaitPort(): Int = bound.await()

        override fun onStart() {
            bound.complete(port)
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val presented = handshake.getFieldValue(AUTH_HEADER)
            if (presented != authToken) {
                Log.w(TAG, "refused a connection presenting the wrong token")
                conn.close(POLICY_VIOLATION, "unauthorized")
                return
            }
            Log.i(TAG, "agent attached")
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "agent detached ($code ${reason.orEmpty()})")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            // Answering can take arbitrarily long -- a diff waits for a person --
            // so nothing is handled on the socket's own thread.
            scope.launch {
                val reply = runCatching { dispatch(JSONObject(message)) }
                    .onFailure { Log.e(TAG, "failed to handle a request", it) }
                    .getOrNull()
                if (reply != null) runCatching { conn.send(reply.toString()) }
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "socket error", ex)
        }
    }

    private suspend fun dispatch(request: JSONObject): JSONObject? {
        val method = request.optString("method")
        val hasId = request.has("id") && !request.isNull("id")
        val params = request.optJSONObject("params") ?: JSONObject()

        val result: JSONObject = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion", PROTOCOL_VERSION))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", IDE_NAME).put("version", VERSION))

            "ping" -> JSONObject()

            "tools/list" -> JSONObject().put("tools", toolDefinitions())

            "tools/call" -> callTool(
                params.optString("name"),
                params.optJSONObject("arguments") ?: JSONObject(),
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

    private suspend fun callTool(name: String, arguments: JSONObject): JSONObject =
        when (name) {
            "openDiff" -> openDiff(arguments)

            "close_tab" -> {
                surfaces.closeTab(arguments.optString("tab_name"))
                textContent("TAB_CLOSED")
            }

            "closeAllDiffTabs" -> {
                surfaces.closeAllTabs()
                textContent("CLOSED_ALL_DIFF_TABS")
            }

            // No language server of the app's own, so nothing is ever wrong.
            "getDiagnostics" -> textContent(JSONArray().toString())

            "openFile" -> {
                val path = arguments.optString("filePath")
                val text = runCatching { readFile(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                surfaces.show(Surface.FileView(path, text))
                textContent("Showing $path")
            }

            "showMarkdown" -> {
                surfaces.show(Surface.Markdown(
                    title = arguments.optString("title", "Note"),
                    text = arguments.optString("markdown"),
                ))
                textContent("Rendered on screen")
            }

            "showPlace" -> {
                surfaces.show(Surface.Place(
                    label = arguments.optString("label", "Here"),
                    latitude = arguments.optDouble("latitude"),
                    longitude = arguments.optDouble("longitude"),
                    zoom = arguments.optDouble("zoom", DEFAULT_ZOOM),
                ))
                textContent("Showing the map")
            }

            "openExternal" -> {
                val uri = arguments.optString("uri")
                if (openExternal(uri)) textContent("Handed $uri to the system")
                else errorContent("nothing on this device handles $uri")
            }

            else -> errorContent("unknown tool: $name")
        }

    private suspend fun openDiff(arguments: JSONObject): JSONObject {
        val path = arguments.optString("new_file_path")
            .ifEmpty { arguments.optString("old_file_path") }
        val proposed = arguments.optString("new_file_contents")
        val current = runCatching { readFile(path) }.getOrDefault("")

        val diff = Surface.Diff(
            tabName = arguments.optString("tab_name", path),
            path = path,
            oldText = current,
            newText = proposed,
        )
        surfaces.show(diff)

        // The reply is read positionally: FILE_SAVED means the second block is
        // the text to use.
        return when (diff.decision.await()) {
            DiffDecision.Accepted -> JSONObject().put("content", JSONArray()
                .put(textBlock("FILE_SAVED"))
                .put(textBlock(proposed)))
            DiffDecision.Rejected -> textContent("DIFF_REJECTED")
        }
    }

    private fun toolDefinitions(): JSONArray {
        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) =
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(required)))

        fun string(description: String) =
            JSONObject().put("type", "string").put("description", description)

        fun number(description: String) =
            JSONObject().put("type", "number").put("description", description)

        return JSONArray()
            .put(tool(
                "openDiff",
                "Show a proposed edit for review. Waits for the person to accept or reject it.",
                JSONObject()
                    .put("old_file_path", string("The file as it stands."))
                    .put("new_file_path", string("The file being written."))
                    .put("new_file_contents", string("The proposed contents."))
                    .put("tab_name", string("A name to close this review by.")),
                listOf("old_file_path", "new_file_path", "new_file_contents", "tab_name"),
            ))
            .put(tool(
                "close_tab",
                "Close a review opened with openDiff.",
                JSONObject().put("tab_name", string("The name the review was opened with.")),
                listOf("tab_name"),
            ))
            .put(tool(
                "closeAllDiffTabs",
                "Close every open review.",
                JSONObject(),
                emptyList(),
            ))
            .put(tool(
                "getDiagnostics",
                "Report problems the editor knows about.",
                JSONObject().put("uri", string("Limit to one file, as a file:// URI.")),
                emptyList(),
            ))
            .put(tool(
                "openFile",
                "Show a file to the person, rendered by the app rather than printed to the terminal.",
                JSONObject().put("filePath", string("Absolute path of the file to show.")),
                listOf("filePath"),
            ))
            .put(tool(
                "showMarkdown",
                "Render markdown on screen: headings, lists, tables, links and code all " +
                    "displayed properly instead of as terminal text. Use this for anything " +
                    "meant to be read rather than scrolled past -- an itinerary, a summary, " +
                    "a comparison.",
                JSONObject()
                    .put("title", string("A short heading for the panel."))
                    .put("markdown", string("The markdown to render.")),
                listOf("markdown"),
            ))
            .put(tool(
                "showPlace",
                "Show a location on a map on screen. Use this whenever the answer involves " +
                    "somewhere in particular.",
                JSONObject()
                    .put("label", string("What is at this location."))
                    .put("latitude", number("Degrees north."))
                    .put("longitude", number("Degrees east."))
                    .put("zoom", number("Map zoom level; higher is closer, around 15 for a street.")),
                listOf("latitude", "longitude"),
            ))
            .put(tool(
                "openExternal",
                "Hand a URI to the device so the right app opens it: a geo: link opens maps, " +
                    "https: opens a browser, tel: the dialer. Use this to leave the harness " +
                    "for something the phone already does well.",
                JSONObject().put("uri", string("The URI to open.")),
                listOf("uri"),
            ))
    }

    private fun textBlock(text: String) =
        JSONObject().put("type", "text").put("text", text)

    private fun textContent(text: String) =
        JSONObject().put("content", JSONArray().put(textBlock(text)))

    private fun errorContent(message: String) =
        JSONObject()
            .put("content", JSONArray().put(textBlock(message)))
            .put("isError", true)

    private fun newAuthToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private companion object {
        const val TAG = "IdeServer"
        const val IDE_NAME = "harness.apk"
        const val VERSION = "0.1.0"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val AUTH_HEADER = "X-Claude-Code-Ide-Authorization"
        const val TOKEN_BYTES = 32
        const val CLOSE_TIMEOUT_MS = 1000
        const val POLICY_VIOLATION = 1008
        const val METHOD_NOT_FOUND = -32601
        const val DEFAULT_ZOOM = 14.0
    }
}

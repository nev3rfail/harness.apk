package apk.harness.ide

import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * So the app listens on the loopback interface, leaves a lockfile naming the
 * port, and answers with the editor's half of [Tools].
 *
 * See docs/ide-protocol.md for where the shape of all this comes from.
 */
class IdeServer(
    private val workspace: File,
    private val lockDirectory: File,
    private val endpoint: McpEndpoint,
    /**
     * The chat this editor belongs to.
     *
     * One editor serves one tab for as long as that tab exists, so the caller of
     * every tool call arriving here is known at the moment the server is built
     * and travels with it. [NO_CHAT] leaves a call unattributed, which is what
     * an editor built outside a tab would be.
     */
    private val owner: Long = NO_CHAT,
) {
    private val authToken = newToken()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lockFile: File? = null
    private var server: Server? = null

    val port: Int get() = server?.port ?: 0

    /**
     * Binds, writes the lockfile, and returns the port.
     *
     * Blocking rather than leaving the port to arrive on a coroutine, because
     * the port is an input to spawning the agent -- it travels to it in
     * `CLAUDE_CODE_SSE_PORT` -- rather than something written to a file for it
     * to find later. The wait is for a loopback bind and is measured in
     * milliseconds.
     *
     * Zero means the socket never bound, which leaves the caller a session with
     * no editor rather than no session.
     */
    fun start(): Int {
        // Port 0 asks the system for a free one. The lockfile cannot be written
        // until it is known, because its name is the port.
        val listener = Server()
        listener.isReuseAddr = true
        listener.start()
        server = listener

        val bound = runCatching {
            runBlocking { withTimeout(BIND_TIMEOUT_MS) { listener.awaitPort() } }
        }.getOrElse {
            Log.e(TAG, "the editor socket did not bind", it)
            return 0
        }

        lockDirectory.mkdirs()
        sweepStaleLocks()

        val file = File(lockDirectory, "$bound.lock")
        file.writeText(
            JSONObject()
                .put("pid", android.os.Process.myPid())
                .put("workspaceFolders", JSONArray().put(workspace.absolutePath))
                .put("ideName", APP_NAME)
                .put("transport", "ws")
                .put("runningInWindows", false)
                .put("authToken", authToken)
                .toString(),
        )
        lockFile = file
        Log.i(TAG, "listening on $bound, lockfile ${file.absolutePath}")
        return bound
    }

    /**
     * Removes the locks of servers that are gone.
     *
     * Only this app writes here, and it does not get to tidy up when it is
     * killed, so a lock naming another process is a closed port with a stale
     * token that the agent would try first. A lock naming *this* process belongs
     * to another tab and is left alone -- sweeping those was right when only one
     * server could exist and is each new tab unhooking its siblings now.
     */
    private fun sweepStaleLocks() {
        val mine = android.os.Process.myPid()
        lockDirectory.listFiles { file -> file.name.endsWith(".lock") }?.forEach { file ->
            val pid = runCatching { JSONObject(file.readText()).optInt("pid") }.getOrDefault(0)
            if (pid != mine) file.delete()
        }
    }

    fun stop() {
        lockFile?.delete()
        lockFile = null
        runCatching { server?.stop(CLOSE_TIMEOUT_MS) }
        server = null
        scope.cancel()
    }

    /**
     * Tells the agent a document is open with nothing highlighted.
     *
     * `filePath` is the only field the notification requires, so the path alone
     * is the whole of it. It is what the agent is told when a selection is
     * cleared and when the panel closes: the app has discarded the selection,
     * and an `ide_selection` still naming a range would be a claim it no longer
     * believes.
     */
    fun reportSelection(path: String) {
        notify("selection_changed", selectionParams(path))
    }

    /**
     * Tells the agent what the user has highlighted.
     *
     * [endLine] is the last selected line, inclusive, because that is what a
     * person points at. Turning that into what the wire wants is
     * [selectionParams]' job, which is also where it can be tested: this class
     * speaks to `android.util.Log` and cannot be run off a device.
     */
    fun reportSelection(path: String, startLine: Int, endLine: Int, text: String) {
        notify("selection_changed", selectionParams(path, startLine, endLine, text))
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
        // The agent dials 127.0.0.1 literally, so the address is spelled out:
        // this device's loopback resolves to ::1, and a server bound there
        // refuses the connection without either side reporting a protocol
        // error. It reads exactly like an editor that was never found.
        InetSocketAddress(InetAddress.getByName(IPV4_LOOPBACK), 0),
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
                val reply = runCatching { endpoint.handle(JSONObject(message), owner) }
                    .onFailure { Log.e(TAG, "failed to handle a request", it) }
                    .getOrNull()
                if (reply != null) runCatching { conn.send(reply.toString()) }
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "socket error", ex)
        }
    }

    private companion object {
        const val TAG = "IdeServer"
        const val IPV4_LOOPBACK = "127.0.0.1"
        const val AUTH_HEADER = "X-Claude-Code-Ide-Authorization"
        const val CLOSE_TIMEOUT_MS = 1000
        const val BIND_TIMEOUT_MS = 5000L
        const val POLICY_VIOLATION = 1008
    }
}

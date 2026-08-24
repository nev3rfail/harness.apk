package apk.harness.ide

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Serves the app's panels to the agent as an MCP server it is configured with.
 *
 * Attaching as an editor is not enough to be reached: of everything an editor
 * offers, the CLI only ever tells the agent that `getDiagnostics` exists. A
 * configured server has no such filter -- every tool it lists is a tool the
 * agent may choose -- so anything meant to be reached for on purpose is served
 * here instead.
 *
 * The transport is MCP over HTTP, which is one POST carrying one JSON-RPC
 * message. That is little enough to answer directly on a socket, and saves the
 * app an HTTP dependency it would otherwise use once.
 */
class PanelServer(
    private val workspace: File,
    private val endpoint: McpEndpoint,
) {
    private val token = newToken()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null
    private var config: File? = null

    val port: Int get() = socket?.localPort ?: 0

    fun start() {
        val listening = ServerSocket(0, BACKLOG, InetAddress.getByName(IPV4_LOOPBACK))
        socket = listening
        writeConfig(listening.localPort)
        Log.i(TAG, "listening on ${listening.localPort}")

        scope.launch {
            while (!listening.isClosed) {
                val client = runCatching { listening.accept() }.getOrNull() ?: break
                scope.launch { serve(client) }
            }
        }
    }

    fun stop() {
        config?.delete()
        config = null
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    /**
     * Tells the agent where to find this server.
     *
     * The port is asked for fresh each run, so the file is written each run too.
     * It names the workspace it sits in, which is the directory the agent is
     * started in, and that is what makes the agent read it at all.
     */
    private fun writeConfig(port: Int) {
        val servers = JSONObject().put(
            SERVER_NAME,
            JSONObject()
                .put("type", "http")
                .put("url", "http://$IPV4_LOOPBACK:$port$ENDPOINT_PATH")
                .put("headers", JSONObject().put(AUTH_HEADER, token)),
        )
        val file = File(workspace, CONFIG_NAME)
        file.writeText(JSONObject().put("mcpServers", servers).toString(2))
        config = file
    }

    private suspend fun serve(client: Socket) {
        client.use {
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input) ?: return
            val output = client.getOutputStream()

            if (request.authorization != token) {
                respond(output, "401 Unauthorized", null)
                return
            }
            if (request.body.isEmpty()) {
                // A GET here is the agent asking to be pushed to, which this
                // server has nothing to say over.
                respond(output, "405 Method Not Allowed", null)
                return
            }

            val reply = runCatching { endpoint.handle(JSONObject(request.body)) }
                .onFailure { Log.e(TAG, "failed to handle a request", it) }
                .getOrNull()

            // A notification is answered by the fact that it arrived.
            if (reply == null) respond(output, "202 Accepted", null)
            else respond(output, "200 OK", reply.toString())
        }
    }

    private class Request(val authorization: String?, val body: String)

    private fun readRequest(input: BufferedInputStream): Request? {
        val header = StringBuilder()
        // Headers end at a blank line, and the body's length is declared in
        // them, so they are read a byte at a time and the body in one go.
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return null
            header.append(byte.toChar())
            if (header.length > MAX_HEADER_BYTES) return null
        }

        var length = 0
        var authorization: String? = null
        for (line in header.lines()) {
            val name = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (name) {
                "content-length" -> length = value.toIntOrNull() ?: 0
                AUTH_HEADER_LOWERCASE -> authorization = value
            }
        }

        if (length <= 0) return Request(authorization, "")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val got = input.read(body, read, length - read)
            if (got == -1) break
            read += got
        }
        return Request(authorization, String(body, 0, read, Charsets.UTF_8))
    }

    private fun respond(output: OutputStream, status: String, body: String?) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private companion object {
        const val TAG = "PanelServer"
        const val SERVER_NAME = "harness"
        const val CONFIG_NAME = ".mcp.json"
        const val ENDPOINT_PATH = "/mcp"
        const val IPV4_LOOPBACK = "127.0.0.1"
        const val AUTH_HEADER = "X-Harness-Authorization"
        const val AUTH_HEADER_LOWERCASE = "x-harness-authorization"
        const val BACKLOG = 4
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}

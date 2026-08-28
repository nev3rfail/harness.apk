package apk.harness.ide

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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
 *
 * One server serves every agent, so the token a request presents is what says
 * who is calling: each chat is handed a config file carrying a token of its
 * own, and [tokens] maps that token back to the chat.
 */
class PanelServer(
    private val workspace: File,
    private val endpoint: McpEndpoint,
) {
    /**
     * The token a session started outside the app presents.
     *
     * Such a session is handed no config path and finds the app-wide
     * `.mcp.json` in its working directory instead, so it reaches the tools
     * with no chat behind it.
     */
    private val appToken = newToken()

    /**
     * Every token this server has issued, and the chat it was issued to.
     *
     * Read on the sockets' threads and written when a tab opens, hence
     * concurrent. A token that is not in here is refused: it is either a guess
     * or a credential from a chat that has since gone.
     */
    private val tokens = ConcurrentHashMap<String, Long>()

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
        tokens.clear()
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    /**
     * The config naming this server for one chat, or null while nothing is
     * listening.
     *
     * One server for every agent, so the token is what says who is calling:
     * each chat gets a file of its own carrying a token of its own, and the
     * server maps the token back to the chat. A port and a socket per agent
     * would answer the same question at a much higher price.
     *
     * The file is handed to the chat's agent on its command line, so it is
     * written where nothing else looks for it -- an agent that stumbled on it
     * would be speaking as a chat that is not its own.
     */
    fun configFor(key: Long): File? {
        val listening = socket ?: return null
        // A key issued a token twice keeps only the newer one, so the file and
        // what the server honours never disagree.
        tokens.values.removeAll { it == key }
        val token = newToken()
        tokens[token] = key

        val directory = File(workspace, PANEL_DIRECTORY)
        directory.mkdirs()
        val file = File(directory, "$key$CONFIG_SUFFIX")
        file.writeText(configText(listening.localPort, token))
        return file
    }

    /**
     * Forgets a chat's config and its token.
     *
     * Called when the chat goes, because a token outliving the chat it was
     * issued to is a credential nobody owns.
     */
    fun discard(key: Long) {
        tokens.values.removeAll { it == key }
        File(File(workspace, PANEL_DIRECTORY), "$key$CONFIG_SUFFIX").delete()
    }

    /**
     * Tells a session started outside the app where to find this server.
     *
     * The agent reads this out of its working directory, which is right for a
     * session started in the workspace and wrong for one started anywhere else,
     * so a session that starts elsewhere is handed [configFor] on its command
     * line instead. The port is asked for fresh each run, so the file is
     * written each run too.
     */
    private fun writeConfig(port: Int) {
        tokens[appToken] = NO_CHAT
        val file = File(workspace, CONFIG_NAME)
        file.writeText(configText(port, appToken))
        config = file
    }

    /** The config an agent reads to reach this server, presenting [token]. */
    private fun configText(port: Int, token: String): String {
        val servers = JSONObject().put(
            SERVER_NAME,
            JSONObject()
                .put("type", "http")
                .put("url", "http://$IPV4_LOOPBACK:$port$ENDPOINT_PATH")
                .put("headers", JSONObject().put(AUTH_HEADER, token)),
        )
        return JSONObject().put("mcpServers", servers).toString(2)
    }

    private suspend fun serve(client: Socket) {
        client.use {
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input) ?: return
            val output = client.getOutputStream()

            val owner = request.authorization?.let { tokens[it] }
            if (owner == null) {
                respond(output, "401 Unauthorized", null)
                return
            }
            if (request.body.isEmpty()) {
                // A GET here is the agent asking to be pushed to, which this
                // server has nothing to say over.
                respond(output, "405 Method Not Allowed", null)
                return
            }

            val reply = runCatching { endpoint.handle(JSONObject(request.body), owner) }
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
        const val PANEL_DIRECTORY = ".claude/panels"
        const val CONFIG_SUFFIX = ".json"
        const val ENDPOINT_PATH = "/mcp"
        const val IPV4_LOOPBACK = "127.0.0.1"
        const val AUTH_HEADER = "X-Harness-Authorization"
        const val AUTH_HEADER_LOWERCASE = "x-harness-authorization"
        const val BACKLOG = 4
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}

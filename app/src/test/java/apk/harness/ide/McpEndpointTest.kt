package apk.harness.ide

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpEndpointTest {

    /** The owner the last call was told about, or null if nothing was called. */
    private var called: Long? = null

    private val endpoint = McpEndpoint(
        serverName = "test",
        definitions = { JSONArray() },
        call = { _, _, owner ->
            called = owner
            textContent("done")
        },
    )

    private fun handle(request: JSONObject, owner: Long = NO_CHAT): JSONObject? =
        runBlocking { endpoint.handle(request, owner) }

    private fun request(method: String, id: Any? = 1): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("method", method)
            .also { if (id != null) it.put("id", id) }

    @Test
    fun `a tools call carries the owner it was built with`() {
        handle(
            request("tools/call").put("params", JSONObject().put("name", "openFile")),
            owner = 7L,
        )

        assertEquals(7L, called)
    }

    @Test
    fun `initialize answers without asking who called`() {
        val reply = handle(request("initialize"))

        assertEquals(
            APP_NAME_UNDER_TEST,
            reply!!.getJSONObject("result").getJSONObject("serverInfo").getString("name"),
        )
        assertNull(called)
    }

    @Test
    fun `a notification is answered with nothing`() {
        assertNull(handle(request("notifications/initialized", id = null)))
    }

    @Test
    fun `an unknown method is an error naming it`() {
        val reply = handle(request("resources/list"))

        assertEquals(
            "unknown method: resources/list",
            reply!!.getJSONObject("error").getString("message"),
        )
    }

    private companion object {
        const val APP_NAME_UNDER_TEST = "test"
    }
}

package apk.harness.tabs

import apk.harness.agents.isSessionId
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tabs that were open, kept across the process so they come back.
 *
 * No order: the drawer groups chats by project and sorts them by recency, so the
 * app has no tab order to keep. [front] is the session id of the tab that was in
 * front. [generation] counts the screens that have written the file, so a screen
 * that never saw its own end cannot write over the one that replaced it.
 */
data class SavedTabs(
    val generation: Long,
    val front: String?,
    val tabs: List<SavedTab>,
)

/** One open tab, as much of it as a new screen needs to open it again. */
data class SavedTab(
    val directory: String,
    val sessionId: String,
    val backend: String,
    val label: String,
)

/** [saved] as the text of the file. */
fun encode(saved: SavedTabs): String = JSONObject().apply {
    put("generation", saved.generation)
    saved.front?.let { put("front", it) }
    put("tabs", JSONArray().apply {
        for (tab in saved.tabs) {
            put(JSONObject().apply {
                put("directory", tab.directory)
                put("sessionId", tab.sessionId)
                put("backend", tab.backend)
                put("label", tab.label)
            })
        }
    })
}.toString()

/**
 * The tabs [text] names, or null for text that is not such a document.
 *
 * A tab whose session id is not one is dropped: the id goes to the CLI's command
 * line when the tab starts, and the file is one any process of the app's user
 * can write.
 */
fun decode(text: String): SavedTabs? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val list = root.optJSONArray("tabs") ?: return null
    val tabs = (0 until list.length()).mapNotNull { index ->
        val tab = list.optJSONObject(index) ?: return@mapNotNull null
        val sessionId = tab.string("sessionId")?.takeIf(::isSessionId) ?: return@mapNotNull null
        SavedTab(
            directory = tab.string("directory") ?: return@mapNotNull null,
            sessionId = sessionId,
            backend = tab.string("backend") ?: return@mapNotNull null,
            label = tab.string("label") ?: "",
        )
    }
    return SavedTabs(
        generation = root.optLong("generation", 0L),
        front = root.string("front")?.takeIf(::isSessionId),
        tabs = tabs,
    )
}

/** The saved tabs in [file], or null when there is no usable file. */
fun readSaved(file: File): SavedTabs? =
    runCatching { file.readText() }.getOrNull()?.let(::decode)

// Read with opt rather than optString, which disagrees across the device
// runtime and the test classpath about a value that is not a string.
private fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotEmpty() }

package apk.harness.ide

import apk.harness.cells.CellProblem
import apk.harness.cells.promoteCells
import apk.harness.ui.markdownBlocks
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the agent can ask the app to do, and the two lists it is offered
 * under.
 *
 * The editor list is what `claude --ide` expects to find and calls as it works:
 * a review before a file changes, the diagnostics around it, the permission mode
 * it is in. The agent never chooses these -- the CLI does -- and of them it is
 * only told `getDiagnostics` exists.
 *
 * The panel list is the opposite: tools the agent picks up because it decided a
 * map or a rendered document is the better answer. Those reach it only through a
 * configured MCP server, so they are named for how they will be searched for --
 * the words that matter are in the name, because a name is all that arrives when
 * a tool is announced without its schema.
 */
class Tools(
    private val surfaces: Surfaces,
    private val openExternal: (String) -> Boolean,
    private val readFile: (String) -> String,
    private val readDocument: (String) -> String,
) {

    fun editorDefinitions(): JSONArray = JSONArray()
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

    fun panelDefinitions(): JSONArray = JSONArray()
        .put(tool(
            "show_map_location_panel",
            "Show a place on a map on the phone screen, with a marker. Use whenever the " +
                "answer involves somewhere in particular: a restaurant, a trailhead, a hotel.",
            JSONObject()
                .put("label", string("What is at this location."))
                .put("latitude", number("Degrees north."))
                .put("longitude", number("Degrees east."))
                .put("zoom", number("Map zoom level; higher is closer, around 15 for a street.")),
            listOf("latitude", "longitude"),
        ))
        .put(tool(
            "show_document_panel",
            "Render a markdown document from a file on the phone screen: headings, lists, " +
                "tables, links and code displayed properly instead of as terminal text, and " +
                "harness-map or harness-table cells drawn as a map and a table. Use for " +
                "anything meant to be read rather than scrolled past -- an itinerary, a " +
                "summary, a comparison table. Write the file first, then show it by path.",
            JSONObject().put("filePath", string("Absolute path of the document to show.")),
            listOf("filePath"),
        ))
        .put(tool(
            "check_document_cells",
            "Check the harness-map and harness-table cells in a markdown file without putting " +
                "anything on the phone screen. Answers with what would not render and why. " +
                "Call this before finishing a turn: showing a document costs the person their " +
                "screen, and checking one costs nothing.",
            JSONObject().put("filePath", string("Absolute path of the document to check.")),
            listOf("filePath"),
        ))
        .put(tool(
            "open_uri_in_phone_app",
            "Hand a URI to the phone so the right installed app opens it: a geo: link opens " +
                "maps, https: a browser, tel: the dialer. Use to leave the harness for " +
                "something the phone already does well.",
            JSONObject().put("uri", string("The URI to open.")),
            listOf("uri"),
        ))

    suspend fun call(name: String, arguments: JSONObject): JSONObject =
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
                val text = runCatching { readDocument(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                surfaces.show(Surface.Document(path, text))
                textContent("Showing $path")
            }

            "show_document_panel" -> {
                val path = arguments.optString("filePath")
                val text = runCatching { readDocument(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                val problems = promoteCells(markdownBlocks(text)).problems
                surfaces.show(Surface.Document(path, text))
                textContent("Showing $path" + report(problems))
            }

            "check_document_cells" -> {
                val path = arguments.optString("filePath")
                val text = runCatching { readDocument(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                val problems = promoteCells(markdownBlocks(text)).problems
                textContent(
                    if (problems.isEmpty()) "Every cell in $path checks"
                    else path + report(problems),
                )
            }

            "show_map_location_panel" -> {
                surfaces.show(Surface.Place(
                    label = arguments.optString("label", "Here"),
                    latitude = arguments.optDouble("latitude"),
                    longitude = arguments.optDouble("longitude"),
                    zoom = arguments.optDouble("zoom", DEFAULT_ZOOM),
                ))
                textContent("Showing the map")
            }

            "open_uri_in_phone_app" -> {
                val uri = arguments.optString("uri")
                if (openExternal(uri)) textContent("Handed $uri to the system")
                else errorContent("nothing on this device handles $uri")
            }

            // The agent tells the editor which permission mode it is in, so an
            // editor can say so on screen. Answered because it is part of the
            // surface; an error here is noise in the agent's log.
            "set_permission_mode" -> textContent("PERMISSION_MODE_SET")

            else -> errorContent("unknown tool: $name")
        }

    /**
     * What did not render, one line each, or nothing when everything did.
     *
     * A tool result is read by a person as often as by an agent, so the line is
     * the one they would count to in the file.
     */
    private fun report(problems: List<CellProblem>): String = problems.joinToString("") { problem ->
        "\n" + problem.line?.let { "line ${it + 1}: " }.orEmpty() + problem.reason
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

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required)))

    private fun string(description: String) =
        JSONObject().put("type", "string").put("description", description)

    private fun number(description: String) =
        JSONObject().put("type", "number").put("description", description)

    private companion object {
        const val DEFAULT_ZOOM = 14.0
    }
}

package apk.harness.ide

import apk.harness.cells.CellProblem
import apk.harness.cells.promoteCells
import apk.harness.intents.Handoff
import apk.harness.intents.HandoffOutcome
import apk.harness.intents.HandoffRefusalException
import apk.harness.intents.confirmationNeeded
import apk.harness.intents.handoffFor
import apk.harness.ui.Rendered
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
    // Who would take a handoff, asked before the person is: the confirmation
    // names the receiving app, and a fire nothing handles is refused as a
    // sentence instead of thrown from inside the platform.
    private val describeHandoff: (Handoff) -> String?,
    private val fireHandoff: (Handoff) -> HandoffOutcome,
    // The directories a file may be handed out of, which are the ones the agent
    // can write. Passed in rather than read here, so everything that decides
    // whether a handoff is allowed runs off the device too.
    private val writable: List<String>,
    private val readFile: (String) -> String,
    // A document rather than its text: only the reader knows what kind of file
    // it rendered, so the offset between the document's lines and the file's
    // comes back with it rather than being guessed here.
    private val readDocument: (String) -> Rendered,
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
            "open_in_phone_app",
            "Hand something to another app on the phone: open a link, a place or a document " +
                "in whatever handles it, share a file through the system share sheet, address " +
                "a mail, put a number in the dialer, open a system settings screen, or bring " +
                "an installed app to the front. action is one of view, share, share_many, " +
                "compose, dial, settings, launch, and defaults to view. Anything that carries " +
                "a file, names an app, or reaches past a plain link asks the person first.",
            JSONObject()
                .put("action", string(
                    "What to do: view, share, share_many, compose, dial, settings, launch."
                ))
                .put("uri", string("For view, compose and dial: the URI to hand over."))
                .put("mime_type", string("For share and share_many: what the content is."))
                .put("extras", JSONObject()
                    .put("type", "object")
                    .put("description",
                        "Values the receiving app reads by name: text, subject, to for a " +
                            "mail or a share; screen for settings, one of developer, " +
                            "app_details, battery. Strings, numbers, booleans and string " +
                            "lists only."))
                .put("package", string("Aim it at one installed app, by package name."))
                .put("files", JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put("description",
                        "Absolute paths inside this app's own directories. Anywhere else is " +
                            "refused.")),
            emptyList(),
        ))

    /**
     * Runs the tool [name] names, for the chat that asked for it.
     *
     * One of these serves every agent, so [owner] is how a surface it puts on
     * screen is attributed to the chat it came up in. It is the channel's to
     * supply: the editor knows its tab, and the panel server knows which token
     * it was presented. [NO_CHAT] is a call nobody's chat made.
     */
    suspend fun call(name: String, arguments: JSONObject, owner: Long): JSONObject =
        when (name) {
            "openDiff" -> openDiff(arguments, owner)

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
                val document = runCatching { readDocument(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                surfaces.show(
                    Surface.Document(path, document.markdown, document.lineOffset),
                    owner,
                )
                textContent("Showing $path")
            }

            "show_document_panel" -> {
                val path = arguments.optString("filePath")
                val document = runCatching { readDocument(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                val problems = promoteCells(markdownBlocks(document.markdown)).problems
                surfaces.show(
                    Surface.Document(path, document.markdown, document.lineOffset),
                    owner,
                )
                textContent("Showing $path" + report(problems))
            }

            "check_document_cells" -> {
                val path = arguments.optString("filePath")
                val document = runCatching { readDocument(path) }
                    .getOrElse { return errorContent("cannot read $path: ${it.message}") }
                val problems = promoteCells(markdownBlocks(document.markdown)).problems
                textContent(
                    if (problems.isEmpty()) "Every cell in $path checks"
                    else path + report(problems),
                )
            }

            "show_map_location_panel" -> {
                surfaces.show(
                    Surface.Place(
                        label = arguments.optString("label", "Here"),
                        latitude = arguments.optDouble("latitude"),
                        longitude = arguments.optDouble("longitude"),
                        zoom = arguments.optDouble("zoom", DEFAULT_ZOOM),
                    ),
                    owner,
                )
                textContent("Showing the map")
            }

            "open_in_phone_app" -> openInPhoneApp(arguments, owner)

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

    /**
     * Hands something to another app, once whoever has to see it first has.
     *
     * Three things happen in order, and the order is the point: the request is
     * checked into a [Handoff] before anything Android exists, the receiving app
     * is resolved so the person is asked about a named app rather than about
     * *an* app, and only then does the fire happen.
     */
    private suspend fun openInPhoneApp(arguments: JSONObject, owner: Long): JSONObject {
        val handoff = handoffFor(arguments, writable).getOrElse {
            return errorContent((it as HandoffRefusalException).refusal.reason)
        }
        val app = describeHandoff(handoff) ?: return errorContent(NOTHING_HANDLES_IT)

        if (confirmationNeeded(handoff)) {
            val asked = Surface.Handoff(handoff, app)
            surfaces.show(asked, owner)
            // The agent asked and a person said no, which is an answer to the
            // question rather than a failure to carry it out.
            if (!asked.decision.await()) return textContent(DECLINED)
        }

        return when (val outcome = fireHandoff(handoff)) {
            is HandoffOutcome.Started -> textContent("${outcome.app} took it")
            HandoffOutcome.NoHandler -> errorContent(NOTHING_HANDLES_IT)
            HandoffOutcome.Declined -> textContent(DECLINED)
            is HandoffOutcome.Failed -> errorContent(outcome.reason)
        }
    }

    private suspend fun openDiff(arguments: JSONObject, owner: Long): JSONObject {
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
        surfaces.show(diff, owner)

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

        /** The same sentence whether nothing resolved or nothing took it. */
        const val NOTHING_HANDLES_IT = "nothing on this device handles that"

        const val DECLINED = "The operator declined"
    }
}

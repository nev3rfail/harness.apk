package apk.harness.agents

import apk.harness.agents.HeadlessEvent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeEventTest {

    private val id = "2ff233d0-cb38-4eaf-b6c7-549a129713a5"
    private fun parse(line: String, interruptSent: Boolean = false) = ClaudeBackend.parseEvent(line, interruptSent)

    @Test
    fun `init names the conversation`() {
        // Written by the emulator's agent in the panel's directory.
        assertEquals(
            Started("cb53d4da-fbd1-40a9-b5c9-efdd3791e1f9"),
            parse("""{"type":"system","subtype":"init","cwd":"/data/data/dev.harness/files/assistant","session_id":"cb53d4da-fbd1-40a9-b5c9-efdd3791e1f9","tools":["Task","Bash","CronCreate","CronDelete","CronList","DesignSync","Edit","EnterWorktree","ExitWorktree","NotebookEdit","Read","ReportFindings","ScheduleWakeup","SendMessage","Skill","TaskOutput","TaskStop","ToolSearch","WebFetch","WebSearch","Workflow","Write"],"mcp_servers":[{"name":"harness","status":"failed"}],"model":"claude-opus-5[1m]","permissionMode":"default","slash_commands":["firefox-remote-debug","harness-device-build","harness-documents","harness-userland","headless-browser","self-adb","sourcing-claims","deep-research","design-sync","dataviz","update-config","verify","debug","code-review","simplify","batch","fewer-permission-prompts","doctor","loop","claude-api","run","run-skill-generator","agents","auto-mode-setup","autocompact","clear","color","compact","config","context","effort","fast","heapdump","init","mcp","model","__remote-workflow","workflow-launch-exec","reload-skills","rename","security-review","usage","insights","recap","goal","design","design-consent","design-revoke","team-onboarding"],"terminal_slash_commands":["doctor","color"],"apiKeySource":"none","claude_code_version":"2.1.246","output_style":"default","agents":["claude","Explore","general-purpose","Plan","statusline-setup"],"skills":["firefox-remote-debug","harness-device-build","harness-documents","harness-userland","headless-browser","self-adb","sourcing-claims","deep-research","design-sync","dataviz","update-config","verify","debug","code-review","simplify","batch","fewer-permission-prompts","doctor","loop","claude-api","run","run-skill-generator"],"plugins":[],"capabilities":["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1"],"analytics_disabled":false,"product_feedback_disabled":false,"uuid":"9e708845-cf69-4a9c-b6b1-e9bba124883f","memory_paths":{"auto":"/data/data/dev.harness/files/.claude/projects/-data-data-dev-harness-files-assistant/memory/"},"fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required"}"""),
        )
    }

    @Test
    fun `a subagent's events are ignored`() {
        assertEquals(Ignored, parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"inner"}},"session_id":"$id","parent_tool_use_id":"toolu_9"}"""))
        assertEquals(Ignored, parse("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_10","name":"Read","input":{"file_path":"/x"}}]},"parent_tool_use_id":"toolu_9"}"""))
    }

    @Test
    fun `a message start opens a segment`() {
        assertEquals(MessageStart, parse("""{"type":"stream_event","event":{"type":"message_start","message":{}},"session_id":"$id","parent_tool_use_id":null}"""))
    }

    @Test
    fun `a text delta carries its block index and text`() {
        assertEquals(
            TextDelta(1, "one"),
            parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"one"}},"session_id":"$id"}"""),
        )
        assertEquals(TextDelta(0, ""), parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""}}}"""))
    }

    @Test
    fun `thinking is heard from its deltas and from its token counts`() {
        assertEquals(Thinking, parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hm"}}}"""))
        assertEquals(Thinking, parse("""{"type":"system","subtype":"thinking_tokens","estimated_tokens":8,"estimated_tokens_delta":3}"""))
    }

    @Test
    fun `a tool call is its name and a short form of its input`() {
        assertEquals(
            ToolUse("Bash", "ls ~/Downloads"),
            parse("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls ~/Downloads","description":"List"}}]}}"""),
        )
    }

    @Test
    fun `a tool input holding a whole file is one short line`() {
        val body = (1..200).joinToString("\n") { "line $it" }
        val event = parse("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t","name":"Write","input":{"content":${org.json.JSONObject.quote(body)}}}]}}""") as ToolUse
        assertFalse(event.input.contains('\n'))
        assertTrue(event.input.length <= 80)
        assertTrue(event.input.startsWith("line 1 line 2"))
    }

    @Test
    fun `a complete text message is not read for text`() {
        assertEquals(Ignored, parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"one"}]}}"""))
    }

    @Test
    fun `a denial carries its tool and message`() {
        val denied = parse("""{"type":"system","subtype":"permission_denied","tool_name":"Bash","tool_use_id":"toolu_01","message":"touch in '/work/probe.txt' needs approval. The path is inside the working directories for this session ('/work'), and Claude Code asks before a shell command creates, changes or removes files there.","uuid":"00000000-0000-4000-8000-000000000001","session_id":"$id"}""") as Denied
        assertEquals("toolu_01", denied.toolUseId)
        assertEquals("Bash", denied.tool)
        assertTrue(denied.message!!.startsWith("touch in '"))
        assertTrue(denied.message!!.contains("needs approval."))
    }

    @Test
    fun `a retry carries its attempt`() {
        assertEquals(Retrying(2), parse("""{"type":"system","subtype":"api_retry","attempt":2,"max_retries":10,"retry_delay_ms":500,"error_status":529,"error":"overloaded"}"""))
    }

    @Test
    fun `a successful result carries its text and denials`() {
        assertEquals(
            Finished(ok = true, text = "Denied.", denials = listOf(Denial("toolu_01", "Bash")), errors = emptyList(), signedOut = false),
            parse("""{"type":"result","subtype":"success","is_error":false,"num_turns":2,"result":"Denied.","permission_denials":[{"tool_name":"Bash","tool_use_id":"toolu_01","tool_input":{"command":"touch probe.txt"}}],"session_id":"$id"}"""),
        )
    }

    @Test
    fun `an error result carries its errors`() {
        assertEquals(
            Finished(ok = false, text = null, denials = emptyList(), errors = listOf("Reached maximum number of turns (1)"), signedOut = false),
            parse("""{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":2,"errors":["Reached maximum number of turns (1)"],"terminal_reason":"max_turns"}"""),
        )
    }

    @Test
    fun `an interrupted turn is not an error`() {
        val finished = parse("""{"duration_api_ms":0,"stop_reason":null,"session_id":"2ff233d0-cb38-4eaf-b6c7-549a129713a5","total_cost_usd":0,"usage":{"output_tokens_details":{"thinking_tokens":0},"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0,"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0},"inference_geo":"","iterations":[],"speed":"standard"},"modelUsage":{},"permission_denials":[],"terminal_reason":"aborted_streaming","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required","subagent_stats":{"spawned":0,"requested":{"background":0,"foreground":0,"unset":0},"started_in_background":0,"max_depth":0,"spawned_by_subagents":0,"completed":0,"failed":0,"killed":{"parent":0,"user":0,"system":0},"refused":{"depth_limit":0,"concurrency_limit":0,"budget":0},"by_type":{}},"is_error":true,"num_turns":2,"subtype":"error_during_execution","errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"],"type":"result","duration_ms":15371,"uuid":"546ad117-fdf4-4907-92f8-02241fc5379e","queued_turn_count":0,"result_index":0}""") as Finished
        assertTrue(finished.ok)
        assertEquals(emptyList<String>(), finished.errors)
    }

    @Test
    fun `a turn ended with only diagnostics after the panel's interrupt is an interrupted one`() {
        val finished = parse("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"]}""", interruptSent = true) as Finished
        assertTrue(finished.ok)
    }

    @Test
    fun `a turn ended with only diagnostics and no interrupt is an error`() {
        val finished = parse("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"]}""") as Finished
        assertFalse(finished.ok)
    }

    @Test
    fun `a failure that names nothing still says it failed`() {
        val finished = parse("""{"type":"result","subtype":"error_during_execution","is_error":true}""") as Finished
        assertFalse(finished.ok)
        assertEquals(listOf("error_during_execution"), finished.errors)
    }

    @Test
    fun `a result saying no account is signed in is signed out`() {
        val finished = parse("""{"is_error":true,"duration_api_ms":0,"num_turns":1,"stop_reason":"stop_sequence","session_id":"cb53d4da-fbd1-40a9-b5c9-efdd3791e1f9","total_cost_usd":0,"usage":{"output_tokens_details":{"thinking_tokens":0},"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0,"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0},"inference_geo":"","iterations":[],"speed":"standard"},"modelUsage":{},"permission_denials":[],"terminal_reason":"api_error","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required","subagent_stats":{"spawned":0,"requested":{"background":0,"foreground":0,"unset":0},"started_in_background":0,"max_depth":0,"spawned_by_subagents":0,"completed":0,"failed":0,"killed":{"parent":0,"user":0,"system":0},"refused":{"depth_limit":0,"concurrency_limit":0,"budget":0},"by_type":{}},"subtype":"success","api_error_status":null,"result":"Not logged in · Please run /login","type":"result","duration_ms":2651,"uuid":"73df1117-06e7-48ae-96f3-083f113d6e8b","queued_turn_count":0}""") as Finished
        assertTrue(finished.signedOut)
        assertFalse(finished.ok)
        assertEquals(listOf("Not logged in · Please run /login"), finished.errors)
    }

    @Test
    fun `hook events and unknown types are ignored`() {
        assertEquals(Ignored, parse("""{"type":"system","subtype":"hook_started","hook_id":"c1","hook_name":"SessionStart:startup","hook_event":"SessionStart","session_id":"$id"}"""))
        assertEquals(Ignored, parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}"""))
        assertEquals(Ignored, parse("""{"type":"control_response","response":{"subtype":"success","request_id":"int-1","response":{"still_queued":[]}}}"""))
    }

    @Test
    fun `a line that is not json is malformed`() {
        assertEquals(Malformed, parse("{\"type\":"))
        assertEquals(Malformed, parse("hello from launcher.local.sh"))
        assertEquals(Malformed, parse(""))
    }
}

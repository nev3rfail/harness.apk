package apk.harness.assist

import apk.harness.agents.ClaudeBackend
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LaunchTest {

    private val base = mapOf(
        "HOME" to "/data/data/x/files",
        "HARNESS_HOME" to "/data/data/x/files",
        "LD_PRELOAD" to "/lib/libtermuxexec.so",
    )
    private val launcher = File("/data/data/x/files/launcher.sh")
    private val directory = File("/data/data/x/files/assistant")
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private fun launch(resume: Boolean = false, environment: Map<String, String> = base) =
        headlessLaunch(environment, launcher, directory, ClaudeBackend.headlessScript, ClaudeBackend.ideVariables, ClaudeBackend.headless(id, resume))

    @Test
    fun `the launcher runs under the android shell in the assistant directory`() {
        val launch = launch()
        // The shell's argv: argv[0] the shell itself, then the launcher as given.
        assertEquals(listOf("/system/bin/sh", launcher.path), launch.argv)
        assertEquals(directory, launch.directory)
    }

    @Test
    fun `the launcher is told which script to run and with what`() {
        val environment = launch().environment
        assertEquals("headless.sh", environment["HARNESS_HEADLESS_SCRIPT"])
        assertEquals(
            "-p --input-format stream-json --output-format stream-json --verbose " +
                "--include-partial-messages --session-id $id",
            environment["HARNESS_AGENT_ARGS"],
        )
        assertEquals("/lib/libtermuxexec.so", environment["LD_PRELOAD"])
    }

    @Test
    fun `a tab's own variables are not passed on`() {
        val environment = launch(environment = base + mapOf("HARNESS_TAB" to "t", "CLAUDE_CODE_SSE_PORT" to "1")).environment
        assertFalse("HARNESS_TAB" in environment)
        assertFalse("CLAUDE_CODE_SSE_PORT" in environment)
    }

    @Test
    fun `a new conversation starts under its id and a restart resumes it`() {
        assertEquals(listOf("--session-id", id), launch(resume = false).environment.getValue("HARNESS_AGENT_ARGS").split(" ").takeLast(2))
        assertEquals(listOf("--resume", id), launch(resume = true).environment.getValue("HARNESS_AGENT_ARGS").split(" ").takeLast(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an argument holding a space is refused`() {
        headlessLaunch(base, launcher, directory, "headless.sh", emptySet(), listOf("-p", "two words"))
    }

    @Test
    fun `an argument holding a glob character is refused`() {
        for (argument in listOf("a*", "a?", "[a]")) {
            assertThrows(IllegalArgumentException::class.java) {
                headlessLaunch(base, launcher, directory, "headless.sh", emptySet(), listOf("-p", argument))
            }
        }
    }
}

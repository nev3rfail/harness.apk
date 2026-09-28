package apk.harness.assist

import apk.harness.agents.TAB_VARIABLE
import java.io.File

/** A process to start: its argument vector, its whole environment, its working directory. */
data class HeadlessLaunch(
    val argv: List<String>,
    val environment: Map<String, String>,
    val directory: File,
)

/**
 * The launch of a run with no terminal: [launcher] under the Android shell in
 * [directory], with [environment] -- a tab's, from the function `Agent` builds
 * both from -- told to run [script] with [arguments].
 *
 * The launcher splits `HARNESS_AGENT_ARGS` on whitespace, so an argument
 * holding any is refused rather than split, and one holding a glob character
 * is refused rather than left to a shell that might expand it. A tab's own
 * variables are left out: the run belongs to no tab and attaches to no editor.
 */
fun headlessLaunch(
    environment: Map<String, String>,
    launcher: File,
    directory: File,
    script: String,
    arguments: List<String>,
): HeadlessLaunch {
    arguments.firstOrNull { it.isEmpty() || it.any { c -> c.isWhitespace() || c in GLOB } }?.let {
        throw IllegalArgumentException("the launcher splits arguments on whitespace and may expand globs: \"$it\"")
    }
    val variables = environment - setOf(TAB_VARIABLE, IDE_PORT_VARIABLE) + mapOf(
        "HARNESS_HEADLESS_SCRIPT" to script,
        "HARNESS_AGENT_ARGS" to arguments.joinToString(" "),
    )
    return HeadlessLaunch(listOf(ANDROID_SHELL, launcher.path), variables, directory)
}

private const val GLOB = "*?["
private const val ANDROID_SHELL = "/system/bin/sh"
private const val IDE_PORT_VARIABLE = "CLAUDE_CODE_SSE_PORT"

package apk.harness

import android.content.Context
import android.util.Log
import apk.harness.bootstrap.AgentStage
import com.ghostty.android.terminal.TerminalSession
import java.io.File

/**
 * The session the terminal runs.
 *
 * What the agent needs from the platform is assembled by `launcher.sh` on the
 * device rather than here, so the invocation can be changed without a build. This
 * hands that script the paths it needs and starts it. With nothing staged, this
 * is a shell.
 */
class Agent(private val context: Context) {

    private val stage = AgentStage(context)

    /** Where the agent's home is, which is where every project's history is filed. */
    val home: File get() = stage.home

    /** Where the agent's scratch space is, which is also the app's cache. */
    val tmp: File get() = stage.tmp

    /**
     * A session, in [directory], reached over [idePort].
     *
     * [directory] is the working directory the agent runs in and not its `HOME`:
     * one home holds every project's transcripts and one set of credentials,
     * while the working directory is what decides which project a conversation
     * belongs to and therefore which conversations can be reopened in it.
     *
     * [idePort] is the port the app is listening on for this session alone.
     * Discovery otherwise picks a lockfile by matching its workspace against the
     * working directory and taking the newest, which with more than one server
     * is several answers to one question; the port names one of them.
     *
     * [arguments] reach the agent through `launcher.sh`, which forwards them to
     * [script]. That script stands its own default down when it is given
     * arguments, so a session opened on a named conversation stays in it.
     *
     * [script] is the file under `HARNESS_HOME` the launcher runs, which is how
     * a backend brings its own invocation along with its own flags.
     */
    fun session(
        directory: File,
        idePort: Int,
        arguments: List<String>,
        script: String,
    ): TerminalSession {
        // Content rather than machinery, so a failure here costs the agent a
        // skill and not the operator a session.
        runCatching { stage.stageHome() }
            .onFailure { Log.w(TAG, "the home payload did not stage", it) }

        // The tap that opened this is the operator trusting the directory, so
        // the agent is told rather than asking.
        trustProject(File(stage.home, CONFIG_NAME), directory.absolutePath)

        val environment = TerminalSession.defaultEnvironment(
            home = stage.home.absolutePath,
            tmp = stage.tmp.absolutePath,
        ).toMutableMap()
        environment += stage.environment()
        if (idePort > 0) environment["CLAUDE_CODE_SSE_PORT"] = idePort.toString()
        environment["HARNESS_AGENT_SCRIPT"] = script
        // Word-split by the shell that reads it, which is why nothing here may
        // carry a space. Session ids are UUIDs and paths are the app's own.
        if (arguments.isNotEmpty()) environment["HARNESS_AGENT_ARGS"] = arguments.joinToString(" ")

        if (!stage.isStaged()) {
            // Android's own shell, which is toybox.
            environment["SHELL"] = ANDROID_SHELL
            return TerminalSession(
                environment = environment,
                cwd = directory.absolutePath,
            )
        }

        // Run by a shell rather than executed, so nothing here depends on the
        // launcher keeping an execute bit an editor on the device could take.
        return TerminalSession(
            command = ANDROID_SHELL,
            argv = listOf(ANDROID_SHELL, stage.launcher.absolutePath),
            environment = environment,
            cwd = directory.absolutePath,
        )
    }

    private companion object {
        const val TAG = "Agent"
        const val ANDROID_SHELL = "/system/bin/sh"

        /** The agent's own config file, in its home. `AgentStage` seeds it. */
        const val CONFIG_NAME = ".claude.json"
    }
}

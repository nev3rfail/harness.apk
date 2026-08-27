package apk.harness

import android.content.Context
import android.util.Log
import apk.harness.bootstrap.AgentStage
import com.ghostty.android.terminal.TerminalSession

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

    fun session(): TerminalSession {
        // Content rather than machinery, so a failure here costs the agent a
        // skill and not the operator a session.
        runCatching { stage.stageHome() }
            .onFailure { Log.w(TAG, "the home payload did not stage", it) }

        val environment = TerminalSession.defaultEnvironment(
            home = stage.home.absolutePath,
            tmp = stage.tmp.absolutePath,
        ).toMutableMap()
        environment += stage.environment()

        if (!stage.isStaged()) {
            // Android's own shell, which is toybox.
            environment["SHELL"] = ANDROID_SHELL
            return TerminalSession(
                environment = environment,
                cwd = stage.home.absolutePath,
            )
        }

        // Run by a shell rather than executed, so nothing here depends on the
        // launcher keeping an execute bit an editor on the device could take.
        return TerminalSession(
            command = ANDROID_SHELL,
            argv = listOf(ANDROID_SHELL, stage.launcher.absolutePath),
            environment = environment,
            cwd = stage.home.absolutePath,
        )
    }

    private companion object {
        const val TAG = "Agent"
        const val ANDROID_SHELL = "/system/bin/sh"
    }
}

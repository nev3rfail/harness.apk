package apk.harness

import android.content.Context
import android.util.Log
import apk.harness.agents.AgentBackend
import apk.harness.agents.TAB_VARIABLE
import apk.harness.assist.headlessLaunch
import apk.harness.bootstrap.AgentStage
import apk.harness.chats.oneSpelling
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
     *
     * [tag] names the tab in the environment of every process the session
     * starts, whichever backend's script runs, so a record a process writes about
     * itself can be matched to the tab it runs in. A conversation switch inside
     * the CLI keeps its process and so keeps the tag.
     *
     * [trusted] says the operator opened this directory with a tap of their
     * own, which is the operator vouching for it, so the agent is told rather
     * than asking. A tab another app asked for, or one a restart brought back, is
     * not a tap: its directory is trusted only if the agent already records it
     * so, and otherwise the agent asks.
     */
    fun session(
        directory: File,
        idePort: Int,
        arguments: List<String>,
        script: String,
        tag: String,
        trusted: Boolean,
    ): TerminalSession {
        prepare(directory, trusted)
        val environment = baseEnvironment()
        if (idePort > 0) environment["CLAUDE_CODE_SSE_PORT"] = idePort.toString()
        environment[TAB_VARIABLE] = tag
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

    /**
     * What a session in [directory] needs done first, terminal or not: the
     * payload staged, the directory trusted when [trusted], onboarding answered
     * for a home holding credentials, and git's ownership exception for a
     * vouched directory outside the home.
     */
    fun prepare(directory: File, trusted: Boolean) {
        // Content rather than machinery, so a failure here costs the agent a
        // skill and not the operator a session.
        runCatching { stage.stageHome() }
            .onFailure { Log.w(TAG, "the home payload did not stage", it) }

        val config = File(stage.home, CONFIG_NAME)
        if (trusted) trustProject(config, directory.absolutePath)

        // A home that holds credentials has an account, which is what the
        // account question asks for. The config is seeded with the answer when
        // the home is built, and a home built before there was a config to seed
        // -- or one whose config the agent wrote first -- reaches here without
        // it and is asked on every run.
        if (File(stage.home, CREDENTIALS_NAME).isFile) {
            markOnboarded(File(stage.home, CONFIG_NAME))
        }

        // Shared storage synthesises ownership, and git refuses a repository it
        // reads as someone else's. The agent's own home is a real filesystem
        // that needs no exception, so only what lies outside it is named. The
        // two are compared folded, the home being one directory under two names.
        //
        // The exception follows the trust: git's ownership check is what stops a
        // repository planted by another app from running its own hooks, so it is
        // lifted only for a directory the operator vouched for, now or before.
        val vouched = trusted || isTrustedProject(config, directory.absolutePath)
        if (vouched && !folded(directory).startsWith(folded(stage.home))) {
            markSafeDirectory(File(stage.home, GIT_CONFIG_NAME), directory.absolutePath)
        }
    }

    /** The environment every session starts from, a tab's or a headless run's. */
    private fun baseEnvironment(): MutableMap<String, String> =
        TerminalSession.defaultEnvironment(
            home = stage.home.absolutePath,
            tmp = stage.tmp.absolutePath,
        ).toMutableMap().apply { putAll(stage.environment()) }

    /**
     * A run of [backend] with no terminal in [directory], taking its messages on
     * stdin, through the launcher a tab uses.
     *
     * Choosing the app as the assistant is the operator vouching for the
     * assistant's directory, so it is prepared as trusted. The directory is made
     * when it is missing: the seed tree brings it on install, and the operator or
     * the agent may have deleted it since.
     */
    fun headless(directory: File, backend: AgentBackend, arguments: List<String>): Process {
        check(stage.isStaged()) { "the agent is not staged" }
        directory.mkdirs()
        prepare(directory, trusted = true)
        val launch = headlessLaunch(baseEnvironment(), stage.launcher, directory, backend.headlessScript, arguments)
        return ProcessBuilder(launch.argv)
            .directory(launch.directory)
            .apply {
                environment().clear()
                environment().putAll(launch.environment)
            }
            .start()
    }

    /** One directory under one name, so a home reached by either is the home. */
    private fun folded(directory: File) = File(oneSpelling(directory.absolutePath))

    private companion object {
        const val TAG = "Agent"
        const val ANDROID_SHELL = "/system/bin/sh"

        /** The agent's own config file, in its home. `AgentStage` seeds it. */
        const val CONFIG_NAME = ".claude.json"

        /** Git's global config, which is the only place it reads exceptions from. */
        const val GIT_CONFIG_NAME = ".gitconfig"

        /** Where the agent keeps the account it is signed in with. */
        const val CREDENTIALS_NAME = ".claude/.credentials.json"
    }
}

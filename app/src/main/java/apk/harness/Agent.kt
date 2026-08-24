package apk.harness

import android.content.Context
import android.os.Build
import com.ghostty.android.terminal.TerminalSession
import java.io.File

/**
 * What the agent binary needs from the platform in order to be a Linux program
 * on Android.
 *
 * The binary itself is staged into the app's own directory by
 * `scripts/stage-claude.sh`. What is assembled here is the rest: a home it may
 * write, a resolver it can read, and on x86_64 a tracer that rewrites the
 * syscalls the sandbox refuses. With nothing staged, this is a shell.
 */
class Agent(private val context: Context) {

    fun session(): TerminalSession {
        val environment = TerminalSession.defaultEnvironment(
            home = context.filesDir.absolutePath,
            tmp = context.cacheDir.absolutePath,
        ).toMutableMap()

        // Without this the agent finds no shell and disables every tool built on
        // one, which is most of them.
        environment["SHELL"] = SHELL

        val staged = File(context.filesDir, STAGE_DIRECTORY)
        val binary = File(staged, BINARY_NAME)
        if (!binary.canExecute()) {
            return TerminalSession(
                environment = environment,
                cwd = context.filesDir.absolutePath,
            )
        }

        // Android resolves names through netd rather than through a nameserver
        // in /etc/resolv.conf, so a runtime carrying its own resolver has
        // nothing to read. This is the directory the loader is built to read
        // instead, and the one the shim redirects /etc at.
        val etc = File(staged, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(RESOLV_CONF)
        environment["SYSCALL_SHIM_ETC"] = etc.absolutePath

        // The agent is a Bun program, and Bun resolves names two ways: libc for
        // fetch, and its own c-ares for the dns module. c-ares reads the same
        // absent /etc/resolv.conf and then falls back to a nameserver on
        // loopback that nothing answers, so every lookup through it spends its
        // full timeout before failing. A preload names the resolvers instead.
        val setdns = File(staged, SETDNS_NAME)
        setdns.writeText(SETDNS_JS)
        environment["BUN_OPTIONS"] = "--preload ${setdns.absolutePath}"

        // The agent's first run asks which account to sign in with, and asks
        // again on every run until it is told the question has been answered --
        // whatever credentials it already holds. On a phone there is no reason
        // to answer it, so the file it looks in is seeded once, and left alone
        // afterwards because the agent owns it.
        File(context.filesDir, CONFIG_NAME).let { file ->
            if (!file.exists()) file.writeText(ONBOARDED)
        }

        val shim = File(context.applicationInfo.nativeLibraryDir, SHIM_NAME)
        val translating = Build.SUPPORTED_ABIS.firstOrNull() == "x86_64" && shim.canExecute()

        return if (translating) {
            TerminalSession(
                command = shim.absolutePath,
                argv = listOf(shim.absolutePath, binary.absolutePath, IDE_FLAG),
                environment = environment,
                cwd = context.filesDir.absolutePath,
            )
        } else {
            TerminalSession(
                command = binary.absolutePath,
                argv = listOf(binary.absolutePath, IDE_FLAG),
                environment = environment,
                cwd = context.filesDir.absolutePath,
            )
        }
    }

    private companion object {
        const val STAGE_DIRECTORY = "claude"
        const val BINARY_NAME = "claude"

        // The aarch64 Linux ABI has only the *at syscalls, which is what the
        // seccomp policy allows, so the shim only translates on x86_64. It ships
        // as a native library because that directory stays executable whatever
        // the app targets.
        const val SHIM_NAME = "libsyscallshim.so"

        const val IDE_FLAG = "--ide"
        const val SETDNS_NAME = "setdns.js"

        // Android's own shell, which is toybox: enough for the agent to accept
        // that it has one.
        const val SHELL = "/system/bin/sh"

        const val CONFIG_NAME = ".claude.json"
        const val ONBOARDED = """{"hasCompletedOnboarding":true}"""

        // Public resolvers, because the ones the device holds are reachable
        // through netd and not from a socket the agent opens itself. Editing
        // this list sends the agent's lookups somewhere else; nothing else on
        // the device is affected.
        val RESOLVERS = listOf("8.8.8.8", "8.8.4.4")
        val RESOLV_CONF = RESOLVERS.joinToString("") { "nameserver $it\n" }
        val SETDNS_JS = RESOLVERS.joinToString(", ") { "\"$it\"" }
            .let { "try { require(\"dns\").setServers([$it]); } catch (e) {}\n" }
    }
}

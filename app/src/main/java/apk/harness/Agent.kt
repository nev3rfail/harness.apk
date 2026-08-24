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
        // nothing to read. The shim redirects /etc at this directory.
        val etc = File(staged, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").let { file ->
            if (!file.exists()) file.writeText(PUBLIC_RESOLVERS)
        }
        environment["SYSCALL_SHIM_ETC"] = etc.absolutePath

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
        const val PUBLIC_RESOLVERS = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
    }
}

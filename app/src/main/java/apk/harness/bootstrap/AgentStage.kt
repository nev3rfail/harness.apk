package apk.harness.bootstrap

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Where the agent lives on this device, and how it gets there.
 *
 * The binary is proprietary and is not redistributed, so it is downloaded from
 * the host that publishes it and verified against the checksum in that release's
 * manifest. The loader is ours and ships in the APK.
 *
 * The paths are public because two callers need the same ones: this class writes
 * them, and the session that runs the agent names them in its environment.
 * Spelling them in one place is what keeps the two from drifting apart.
 */
class AgentStage(private val context: Context) {

    /**
     * Spelled from the package name rather than taken from `dataDir`, which
     * reports the same directory as `/data/user/0/...`. That spelling is two
     * bytes longer, and the userland's prefix has to fit inside the one Termux
     * compiled into it, so `/data/data` is the only spelling it can carry. Two
     * spellings of one directory is the comparison risk the byte budget exists
     * to avoid.
     */
    val data: File = File("/data/data/${context.packageName}")
    val rootfs: File = File(data, "root")
    val prefix: File = File(rootfs, "usr")

    val home: File = context.filesDir
    val tmp: File = context.cacheDir
    val stage: File = File(home, STAGE_DIRECTORY)
    val binary: File = File(stage, BINARY_NAME)
    val etc: File = File(stage, "etc")
    val launcher: File = File(home, LAUNCHER)

    private val abi: String = Build.SUPPORTED_ABIS.first()

    /** The staged loader, or null on an architecture the app carries none for. */
    val loader: File? = loaderFor(abi)?.let { File(stage, it) }

    /**
     * The tracer, on the architecture that needs one. The aarch64 Linux ABI has
     * only the `*at` syscalls, which is exactly the set Android's seccomp policy
     * allows, so nothing needs rewriting there.
     */
    val shim: File? = File(context.applicationInfo.nativeLibraryDir, SHIM_NAME)
        .takeIf { abi == "x86_64" && it.canExecute() }

    /**
     * True once this device holds the pinned version.
     *
     * The version is read from a file the fetch writes, rather than the binary
     * being hashed: a quarter of a gigabyte read on every launch to learn that
     * nothing changed is a launch nobody waits through. The checksum was
     * verified by the fetch that wrote the stamp.
     */
    fun isStaged(): Boolean =
        binary.isFile &&
            loader?.canExecute() == true &&
            launcher.canExecute() &&
            stamp.takeIf { it.isFile }?.readText()?.trim() == AGENT_VERSION

    /**
     * Downloads and stages the agent, reporting each phase to [onProgress].
     *
     * Every step is repeatable. A verified binary is not fetched twice, the
     * loader and the resolver are overwritten, and the scripts are written only
     * when absent -- so the answer to a failure is another call, which is what
     * the next launch makes.
     */
    fun install(onProgress: (BootstrapProgress) -> Unit) {
        val platform = platformFor(abi) ?: error("no agent build for $abi")
        val loader = loader ?: error("no loader for $abi")

        onProgress(BootstrapProgress.Resolving)
        val release = fetchRelease(AGENT_VERSION, platform)

        stage.mkdirs()
        onProgress(BootstrapProgress.FetchingAgent(0L, release.bytes))
        fetchVerified(
            // A quarter of a gigabyte over a phone connection outlasts the
            // timeout an archive a tenth its size is read under.
            source = { openUrl(agentUrl(release.version, release.platform), readTimeout = 300_000) },
            name = BINARY_NAME,
            checksum = release.sha256,
            into = stage,
        ) { onProgress(BootstrapProgress.FetchingAgent(it, release.bytes)) }

        onProgress(BootstrapProgress.StagingAgent)
        stageLoader(loader)
        configure()

        // Last, because it is what [isStaged] answers from: written before the
        // rest, a run that failed halfway would report a staged agent.
        stamp.writeText("$AGENT_VERSION\n")
    }

    /**
     * Stages what the app tells the agent about itself: the environment
     * document, the skills, and the enablement the panel tools need.
     *
     * Once per install rather than once per launch, so an edit made on the
     * device stands until the next one. The timestamp comes off the installed
     * APK, which changes on every install including a reinstall of the same
     * version.
     */
    fun stageHome() {
        val installed = File(context.applicationInfo.sourceDir).lastModified().toString()
        if (!payloadDue(payload, installed)) return

        home.mkdirs()
        copyTree(HOME_TREE, overwrite = true)
        copyTree(SEED_TREE, overwrite = false)

        // Last, for the reason the version stamp is written last: a run that
        // failed halfway would otherwise report a staged payload.
        stage.mkdirs()
        payload.writeText("$installed\n")
    }

    private fun copyTree(tree: String, overwrite: Boolean) {
        val entries = assetEntries({ context.assets.list(it) }, tree)
        copyAssets(entries, { context.assets.open(it) }, tree, home, overwrite)
    }

    /**
     * Copies the loader out of the native library directory and points its
     * compiled-in paths at this channel.
     *
     * The native library directory is read-only to the app, and the loader has
     * to be written to, so the copy is the thing that runs.
     */
    private fun stageLoader(destination: File) {
        val packaged = File(context.applicationInfo.nativeLibraryDir, LOADER_NAME)
        check(packaged.isFile) { "$LOADER_NAME is not in the native library directory" }
        val image = packaged.readBytes()
        retargetLoader(image, context.packageName)
        destination.writeBytes(image)
        destination.setExecutable(true, true)
    }

    /**
     * The files around the binary: a resolver for the two runtimes that look for
     * one, the answer to the account question, and the scripts that start it.
     */
    private fun configure() {
        etc.mkdirs()
        File(etc, "resolv.conf").writeText(RESOLV_CONF)
        File(etc, SETDNS_NAME).writeText(SETDNS_JS)

        // The agent's first run asks which account to sign in with, and asks
        // again on every run until it is told the question has been answered --
        // whatever credentials it already holds. On a phone there is no reason to
        // answer it, so the file it looks in is seeded once, and left alone
        // afterwards because the agent owns it.
        File(home, CONFIG_NAME).let { if (!it.exists()) it.writeText(ONBOARDED) }

        // Written only when absent, which is what makes an edit on a device
        // survive a relaunch. Deleting one restores it.
        for (name in SCRIPTS) {
            val script = File(home, name)
            if (!script.isFile) {
                context.assets.open(name).use { input ->
                    script.outputStream().use { output -> input.copyTo(output) }
                }
            }
            script.setExecutable(true, true)
        }
    }

    /**
     * What the scripts read. They spell no absolute path of their own, so one
     * copy serves every channel.
     */
    fun environment(): Map<String, String> = buildMap {
        put("HARNESS_HOME", home.absolutePath)
        put("HARNESS_TMP", tmp.absolutePath)
        put("HARNESS_DATA", data.absolutePath)
        put("HARNESS_ROOTFS", rootfs.absolutePath)
        put("HARNESS_PREFIX", prefix.absolutePath)
        put("HARNESS_ETC", etc.absolutePath)
        put("HARNESS_AGENT", binary.absolutePath)
        put("HARNESS_LOADER", loader?.absolutePath ?: "")
        // Empty rather than absent on the architecture that needs no tracer: the
        // script tests the value, and an unset variable and an empty one read the
        // same there.
        put("HARNESS_SHIM", shim?.absolutePath ?: "")
    }

    private val stamp: File get() = File(stage, VERSION_NAME)

    private val payload: File get() = File(stage, PAYLOAD_NAME)

    private companion object {
        const val STAGE_DIRECTORY = "claude"
        const val BINARY_NAME = "claude"
        const val VERSION_NAME = "VERSION"
        const val PAYLOAD_NAME = "PAYLOAD"

        // Two trees because two rules: what the app says about the device is
        // replaced on install, and what the operator or the agent owns is not.
        const val HOME_TREE = "home"
        const val SEED_TREE = "seed"
        const val LAUNCHER = "launcher.sh"
        val SCRIPTS = listOf(LAUNCHER, "agent.sh", "shell.sh")

        // Programs shipped as libraries, because that directory stays executable
        // whatever the app targets.
        const val LOADER_NAME = "libmuslloader.so"
        const val SHIM_NAME = "libsyscallshim.so"

        const val CONFIG_NAME = ".claude.json"
        const val ONBOARDED = """{"hasCompletedOnboarding":true}"""

        const val SETDNS_NAME = "setdns.js"

        // Public resolvers, because the ones the device holds are reachable
        // through netd and not from a socket the agent opens itself. Editing this
        // list sends the agent's lookups somewhere else; nothing else on the
        // device is affected.
        val RESOLVERS = listOf("8.8.8.8", "8.8.4.4")
        val RESOLV_CONF = RESOLVERS.joinToString("") { "nameserver $it\n" }
        val SETDNS_JS = RESOLVERS.joinToString(", ") { "\"$it\"" }
            .let { "try { require(\"dns\").setServers([$it]); } catch (e) {}\n" }
    }
}

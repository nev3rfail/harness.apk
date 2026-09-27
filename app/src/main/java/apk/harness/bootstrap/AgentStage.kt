package apk.harness.bootstrap

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Where the agent lives on this device, and how it gets there.
 *
 * The binary is proprietary and is not redistributed, so it is downloaded from
 * the host that publishes it and verified against the checksum in that release's
 * manifest. The loader is ours and runs from the native library directory as
 * packaged.
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
    val data: File = dataDirectory(context)
    val rootfs: File = File(data, "root")
    val prefix: File = File(rootfs, "usr")

    // Spelled from `data` for the reason `data` is: two spellings of one
    // directory is what the byte budget exists to avoid, and a transcript is
    // filed under the spelling of the working directory the agent was given.
    // `filesDir` and `cacheDir` are read for their other effect -- they create
    // the directories they name -- and their spelling is not used.
    val home: File = File(data, context.filesDir.name)
    val tmp: File = File(data, context.cacheDir.name)
    val stage: File = File(home, STAGE_DIRECTORY)
    val binary: File = File(stage, BINARY_NAME)
    val etc: File = File(stage, "etc")
    val launcher: File = File(home, LAUNCHER)

    private val abi: String = Build.SUPPORTED_ABIS.first()

    private val libraries: File = File(context.applicationInfo.nativeLibraryDir)

    /** The musl loader, which runs the agent with the agent as its argument. */
    private val loader: File = File(libraries, LOADER_NAME)

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
            launcher.canExecute() &&
            stamp.takeIf { it.isFile }?.readText()?.trim() == AGENT_VERSION

    /**
     * Downloads and stages the agent, reporting each phase to [onProgress].
     *
     * Every step is repeatable. A verified binary is not fetched twice and the
     * resolver is overwritten, so the answer to a failure is another call, which
     * is what the next launch makes.
     */
    fun install(onProgress: (BootstrapProgress) -> Unit) {
        val platform = platformFor(abi) ?: error("no agent build for $abi")
        check(loader.isFile) { "$LOADER_NAME is not in the native library directory" }

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
        configure()

        // The launcher comes from the payload, and [isStaged] answers from it,
        // so the payload has to land before the stamp says the agent is ready.
        stageHome()

        // Last, because it is what [isStaged] answers from: written before the
        // rest, a run that failed halfway would report a staged agent.
        stamp.writeText("$AGENT_VERSION\n")
    }

    /**
     * Stages what the app tells the agent about itself: the launcher, the
     * environment document, the skills, and the enablement the panel tools need.
     *
     * The agent's scripts and the binary's execute bit are refreshed on every
     * start. The payload is copied once per install rather than once per
     * launch, so an edit made on the device stands until the next one. The
     * timestamp comes off the installed APK, which changes on every install
     * including a reinstall of the same version.
     *
     * A missing launcher also brings the copy forward, whatever the stamp says,
     * because deleting a staged file is how a device asks for the shipped one
     * back and the launcher is the file a session cannot start without.
     */
    fun stageHome() {
        val installed = File(context.applicationInfo.sourceDir).lastModified().toString()

        // Before the guard, and cheap: a copy carries content and not
        // permissions, [isStaged] reads this bit, and an editor on the device
        // can take it off a file the stamp otherwise considers current. Losing
        // it that way would put the app back to downloading the agent again.
        // Owner only, like the scripts. False on a device that has no launcher
        // yet, which the copy below answers.
        launcher.setExecutable(true, true)

        // Ahead of the guard, because a current version stamp says nothing
        // about the binary's execute bit or the files beside it.
        refreshStage(stage, binary)

        // Ahead of the guard, because the scripts are rewritten on every start.
        stageScripts(SCRIPTS, { context.assets.open(it) }, home) {
            Log.w(TAG, "${it.name} holds the copy that differed from the shipped script")
        }

        if (!payloadDue(payload, installed) && launcher.isFile) return

        home.mkdirs()
        copyTree(HOME_TREE, overwrite = true)
        copyTree(SEED_TREE, overwrite = false)
        launcher.setExecutable(true, true)

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
     * Writes the resolver and the hosts file into [etc], each through a
     * temporary file renamed over it, since a running program may read them.
     * The router binds every file there onto `/etc/<name>` for a program run
     * under proot, so `localhost` resolves without a DNS query. Run at every
     * start.
     */
    fun stageEtc() {
        etc.mkdirs()
        for ((name, text) in listOf("resolv.conf" to RESOLV_CONF, "hosts" to HOSTS)) {
            val staged = File(etc, "$name.tmp")
            staged.writeText(text)
            Os.rename(staged.path, File(etc, name).path)
        }
    }

    /**
     * The files around the binary: a resolver for the two runtimes that look for
     * one, a hosts file, and the answer to the account question.
     */
    private fun configure() {
        stageEtc()
        File(etc, SETDNS_NAME).writeText(SETDNS_JS)

        // The agent's first run asks which account to sign in with, and asks
        // again on every run until it is told the question has been answered --
        // whatever credentials it already holds. On a phone there is no reason to
        // answer it, so the file it looks in is seeded once, and left alone
        // afterwards because the agent owns it.
        File(home, CONFIG_NAME).let { if (!it.exists()) it.writeText(ONBOARDED) }
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
        // For a proot the router does not start, which then still has a
        // directory for its temporary files. The router's proot row keeps it, and
        // its row for native programs sets the same directory.
        put("PROOT_TMP_DIR", File(prefix, "tmp").absolutePath)
        put("HARNESS_AGENT", binary.absolutePath)
        putAll(libraryEnvironment(libraries))
    }

    private val stamp: File get() = File(stage, VERSION_NAME)

    private val payload: File get() = File(stage, PAYLOAD_NAME)

    private companion object {
        const val TAG = "AgentStage"
        const val STAGE_DIRECTORY = "claude"
        const val BINARY_NAME = "claude"
        const val VERSION_NAME = "VERSION"
        const val PAYLOAD_NAME = "PAYLOAD"

        // Two trees because two rules: what the app says about the device is
        // replaced on install, and what the operator or the agent owns is not.
        const val HOME_TREE = "home"
        const val SEED_TREE = "seed"

        // In the app-owned tree, so a build can correct it. What a device wants
        // of its own goes in launcher.local.sh, which the launcher sources and
        // nothing writes.
        const val LAUNCHER = "launcher.sh"

        // App-owned like the launcher and rewritten on every start. A device
        // that wants another invocation or shell sets HARNESS_AGENT_SCRIPT or
        // SHELL in launcher.local.sh.
        val SCRIPTS = listOf("agent.sh", "shell.sh")

        const val CONFIG_NAME = ".claude.json"
        const val ONBOARDED = """{"hasCompletedOnboarding":true}"""

        const val SETDNS_NAME = "setdns.js"

        // Public resolvers, because the ones the device holds are reachable
        // through netd and not from a socket the agent opens itself. Editing this
        // list sends the agent's lookups somewhere else; nothing else on the
        // device is affected.
        val RESOLVERS = listOf("8.8.8.8", "8.8.4.4")
        val RESOLV_CONF = RESOLVERS.joinToString("") { "nameserver $it\n" }
        const val HOSTS = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        val SETDNS_JS = RESOLVERS.joinToString(", ") { "\"$it\"" }
            .let { "try { require(\"dns\").setServers([$it]); } catch (e) {}\n" }
    }
}

// Programs shipped as libraries, because that directory stays executable
// whatever the app targets.
private const val LOADER_NAME = "libmuslloader.so"
private const val TERMUX_EXEC_NAME = "libtermuxexec.so"

// Loader copies under the names a musl binary asks for as its interpreter. The
// loader runs from the native library directory, so nothing runs these.
private val STALE_LOADERS = listOf("ld-musl-aarch64.so.1", "ld-musl-x86_64.so.1")

/**
 * The part of the session environment that names the native library directory
 * [libraries].
 *
 * `LD_PRELOAD` carries the exec router into the launcher's shell, and from there
 * into every bionic program the session starts: each exec of a file in the data
 * directory is rewritten into one the platform allows. The router adds the
 * tracer where one is needed, so the scripts never name it.
 */
fun libraryEnvironment(libraries: File): Map<String, String> {
    val termuxExec = File(libraries, TERMUX_EXEC_NAME).absolutePath
    return mapOf(
        "HARNESS_LOADER" to File(libraries, LOADER_NAME).absolutePath,
        "HARNESS_TERMUX_EXEC" to termuxExec,
        "LD_PRELOAD" to termuxExec,
    )
}

/**
 * Brings the stage directory [stage] up to what a start needs: [binary] made
 * executable, since an exec of it is checked with `X_OK` before it is routed,
 * and the loader copies deleted by name.
 */
fun refreshStage(stage: File, binary: File) {
    binary.setExecutable(true, true)
    for (name in STALE_LOADERS) File(stage, name).delete()
}

/**
 * [path], one of the app's own directories, under both spellings of the data
 * directory.
 *
 * `/data/user/0/<package>` and `/data/data/<package>` are one directory reached
 * two ways: the first is a bind mount of the second rather than a symlink to it,
 * so resolving a path hands back the spelling it arrived in and nothing folds
 * the two together. [AgentStage.data] pins the userland to `/data/data`, and
 * that is the spelling the agent's own environment document and the skills
 * beside it are written in, while Android hands the app the other one. Anything
 * comparing a path the agent supplied against the app's directories therefore
 * has to hold both, or it takes half of the agent's vocabulary and refuses the
 * other half.
 *
 * Both spellings whichever one arrives, so a caller may pass either. A
 * directory under neither spelling is answered with itself alone.
 */
fun bothSpellings(packageName: String, path: String): List<String> {
    val data = "/data/data/$packageName"
    val user = "$PRIMARY_USER/$packageName"
    val other = when {
        path.startsWith("$data/") || path == data -> user + path.removePrefix(data)
        path.startsWith("$user/") || path == user -> data + path.removePrefix(user)
        else -> return listOf(path)
    }
    return listOf(path, other)
}

/** [bothSpellings] for a caller holding a [Context] rather than a package name. */
fun bothSpellings(context: Context, directory: File): List<String> =
    bothSpellings(context.packageName, directory.path)

/** The spelling of the data directory Android hands the app. */
private const val PRIMARY_USER = "/data/user/0"

/** The spelling of the app's data directory that fits the userland's byte budget. */
private fun dataDirectory(context: Context): File = File("/data/data/${context.packageName}")

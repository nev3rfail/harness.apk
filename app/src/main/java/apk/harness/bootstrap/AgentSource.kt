package apk.harness.bootstrap

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The agent release for one architecture: what to download, how long it is, and
 * what it hashes to.
 */
data class AgentRelease(
    val version: String,
    val platform: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * The version the app installs.
 *
 * A release's manifest never changes, so pinning the version is enough to pin
 * the bytes: the checksum and the length come from the manifest at install time
 * rather than from constants here that would have to agree with it.
 */
const val AGENT_VERSION = "2.1.246"

private const val RELEASES = "https://downloads.claude.ai/claude-code-releases"

// The musl builds. A musl binary asks for one thing Android does not have -- a
// loader at /lib -- and the loader that ships in the APK answers it. The glibc
// builds would ask for a whole runtime.
private val PLATFORMS = mapOf(
    "arm64-v8a" to "linux-arm64-musl",
    "x86_64" to "linux-x64-musl",
)

// One shared libc per architecture, built by `scripts/build-loaders.sh` from
// musl's own release and shipped as a native library because that directory stays
// executable whatever the app targets. Both are named `libmuslloader.so` there;
// this is what the copy in the data directory is called, which is the name a musl
// binary would have asked for as its interpreter.
private val LOADERS = mapOf(
    "arm64-v8a" to "ld-musl-aarch64.so.1",
    "x86_64" to "ld-musl-x86_64.so.1",
)

/** The release platform for [abi], or null when there is none. */
fun platformFor(abi: String): String? = PLATFORMS[abi]

/** What the staged loader for [abi] is called, or null when there is none. */
fun loaderFor(abi: String): String? = LOADERS[abi]

/** Where the manifest for [version] is published. */
fun manifestUrl(version: String): String = "$RELEASES/$version/manifest.json"

/** Where the binary for [version] and [platform] is published. */
fun agentUrl(version: String, platform: String): String =
    "$RELEASES/$version/$platform/claude"

/**
 * Reads [platform]'s entry out of a release manifest.
 *
 * Throws when the manifest does not describe the version asked for, or does not
 * carry that platform: a manifest that answered for a different release would
 * hand back a checksum the download can never match, and the failure would
 * arrive 240 megabytes later than it needed to.
 */
fun parseManifest(json: String, version: String, platform: String): AgentRelease {
    val root = JSONObject(json)
    val published = root.getString("version")
    require(published == version) { "manifest is for $published, not $version" }
    val platforms = root.getJSONObject("platforms")
    require(platforms.has(platform)) { "$version has no $platform build" }
    val entry = platforms.getJSONObject(platform)
    return AgentRelease(
        version = version,
        platform = platform,
        sha256 = entry.getString("checksum"),
        bytes = entry.getLong("size"),
    )
}

/** Opens a URL for reading, following the redirect to whatever host serves it. */
fun openUrl(url: String, readTimeout: Int = 60_000): InputStream {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 30_000
    connection.readTimeout = readTimeout
    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "$url answered ${connection.responseCode}"
    }
    return connection.inputStream
}

/** The release [version] publishes for [platform]. */
fun fetchRelease(version: String, platform: String): AgentRelease {
    val json = openUrl(manifestUrl(version), readTimeout = 30_000)
        .use { it.readBytes().decodeToString() }
    return parseManifest(json, version, platform)
}

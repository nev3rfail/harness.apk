package apk.harness.bootstrap

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One architecture's bootstrap archive, pinned by checksum. */
data class BootstrapRelease(val asset: String, val sha256: String, val bytes: Long)

// The release the tree is measured against. The archives are release assets, so
// the tag cannot be moved under us the way an apt repository can.
private const val RELEASE = "bootstrap-2026.08.23-r1+apt.android-7"
private const val ASSETS = "https://github.com/termux/termux-packages/releases/download"

// The checksums come from the same host as the archives, so they catch a
// truncated or corrupted download. That is not code signing.
private val RELEASES = mapOf(
    "arm64-v8a" to BootstrapRelease(
        asset = "bootstrap-aarch64.zip",
        sha256 = "f902017cf09c84189732b6174b56d69b9890468f4fa7394fc1354b573153688e",
        bytes = 32672724L,
    ),
    "x86_64" to BootstrapRelease(
        asset = "bootstrap-x86_64.zip",
        sha256 = "5f7c54e860df1ef5146b8475dc90e69af666da6588ca1fd47be8f7036b07e8cb",
        bytes = 32584674L,
    ),
)

/** The archive pinned for [abi], or null when there is none. */
fun releaseFor(abi: String): BootstrapRelease? = RELEASES[abi]

/** Where [release] is downloaded from. */
// A literal + in a URL path is a space to some servers, so the tag is escaped.
fun downloadUrl(release: BootstrapRelease): String =
    "$ASSETS/${RELEASE.replace("+", "%2B")}/${release.asset}"

/** The SHA-256 of [file] as lowercase hex, read in chunks rather than whole. */
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Opens [release] over HTTP, following the redirect to the signed asset host. */
fun openRelease(release: BootstrapRelease): InputStream {
    val connection = URL(downloadUrl(release)).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "${downloadUrl(release)} answered ${connection.responseCode}"
    }
    return connection.inputStream
}

/**
 * Puts a file named [name] in [into] and returns it, reading from [source] only
 * when what is already there does not hash to [checksum], and reporting bytes
 * copied to [onProgress].
 *
 * The download lands beside the destination and is renamed once verified, so an
 * interrupted fetch never looks like a finished one.
 *
 * The bytes arrive from [source] rather than from a URL of this function's own
 * making, so everything except the connection is driven by a stream a caller can
 * supply. [openRelease] is that connection, and the only part not covered.
 */
fun fetchVerified(
    source: () -> InputStream,
    name: String,
    checksum: String,
    into: File,
    onProgress: (Long) -> Unit,
): File {
    val destination = File(into, name)
    if (destination.isFile && sha256(destination) == checksum) return destination

    into.mkdirs()
    val partial = File(into, "$name.tmp")
    var copied = 0L
    try {
        source().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied)
                }
            }
        }
        val actual = sha256(partial)
        check(actual == checksum) {
            "$name checksum mismatch: expected $checksum, got $actual"
        }
        destination.delete()
        check(partial.renameTo(destination)) { "could not rename ${partial.name}" }
    } finally {
        // Runs after a successful rename too, and cannot touch the archive: the
        // verified bytes are at the destination path and the partial path holds
        // nothing once the rename has taken them. So this only ever removes what
        // a failed or unverifiable fetch left behind.
        partial.delete()
    }
    return destination
}

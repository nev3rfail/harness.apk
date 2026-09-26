package apk.harness.bootstrap

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * One architecture's bootstrap archive, pinned by checksum.
 *
 * [entries] is the archive's zip entry count, which is what the extraction
 * progress is measured against.
 */
data class BootstrapRelease(val asset: String, val sha256: String, val bytes: Long, val entries: Int)

// The Play build's bootstrap, built from the termux.net repository whose Go
// runtime routes its own execs. The archives are release assets, so the tag
// cannot be moved under us the way an apt repository can.
private const val RELEASE = "bootstrap-2026.09.20-r1"
private const val ASSETS = "https://github.com/termux-play-store/termux-packages/releases/download"

// The same archives, carried as assets of the harness release, for when the
// upstream release is pruned or unreachable.
private const val MIRROR = "https://github.com/nev3rfail/harness.apk/releases/download"

// The checksums come from the same host as the archives, so they catch a
// truncated or corrupted download. That is not code signing.
private val RELEASES = mapOf(
    "arm64-v8a" to BootstrapRelease(
        asset = "bootstrap-aarch64.zip",
        sha256 = "8dce2a667b1d1e1de0b87f8228538e9035f286553cb7dbd4a35af191783d0a92",
        bytes = 24564124L,
        entries = 2863,
    ),
    "x86_64" to BootstrapRelease(
        asset = "bootstrap-x86_64.zip",
        sha256 = "115b21acb995c2da36a66c3c76fa07e9b4c69cac851a80b4876f48468eec5b73",
        bytes = 24218072L,
        entries = 2864,
    ),
)

/** The archive pinned for [abi], or null when there is none. */
fun releaseFor(abi: String): BootstrapRelease? = RELEASES[abi]

/** Where [release] is downloaded from first. */
fun downloadUrl(release: BootstrapRelease): String = "$ASSETS/$RELEASE/${release.asset}"

/** Where [release] is downloaded from when [downloadUrl] fails. */
fun mirrorUrl(release: BootstrapRelease): String = "$MIRROR/$RELEASE/${release.asset}"

/**
 * Puts [release] in [into] and returns it, from [downloadUrl] and then from
 * [mirrorUrl] when that fails for any reason -- no answer, a short read, a
 * checksum that does not match. Both hold the same bytes, so one checksum
 * verifies either. [open] is the connection, which a test replaces.
 */
fun fetchBootstrap(
    release: BootstrapRelease,
    into: File,
    open: (String) -> InputStream = { openUrl(it) },
    onProgress: (Long) -> Unit,
): File {
    fun from(url: String) = fetchVerified({ open(url) }, release.asset, release.sha256, into, onProgress)
    return try {
        from(downloadUrl(release))
    } catch (upstream: Exception) {
        try {
            from(mirrorUrl(release))
        } catch (mirror: Exception) {
            mirror.addSuppressed(upstream)
            throw mirror
        }
    }
}

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
 * supply. [openUrl] is that connection, and the only part not covered.
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

package apk.harness.assist

/** One image's limit: 5 MB of base64, and the CLI's own `targetRawSize`. */
const val IMAGE_BYTES_LIMIT = 3_932_160L

/** A non-premultiplied ARGB pixel composited onto white, opaque. JPEG has no alpha. */
fun onWhite(argb: Int): Int {
    val alpha = argb ushr 24
    fun channel(shift: Int): Int {
        val value = (argb shr shift) and 0xFF
        return (value * alpha + 0xFF * (0xFF - alpha)) / 0xFF
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

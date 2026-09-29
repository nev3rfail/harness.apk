package apk.harness.assist

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.util.UUID

/** An image ready to go as a block: its file under the cache directory, its type and size. */
data class EncodedImage(val file: File, val mediaType: String, val bytes: Long)

/**
 * [stream] decoded with ImageDecoder (every format the platform reads, HEIC
 * included) at [targetSize], and encoded into [into]: PNG from a PNG, else JPEG
 * at quality 85. An encoding over [IMAGE_BYTES_LIMIT] is encoded again as JPEG.
 * Every JPEG of an image with alpha is composited onto white first. Null when
 * the stream does not decode, is over [FILE_LIMIT], or its JPEG is still over
 * the limit; the caller copies it as a file instead. The stream is first
 * copied into a file under [into], counted against [FILE_LIMIT] as a file of
 * the inbox is, and decoded from there, so the decoder never holds the
 * sender's descriptor. Cancelling stops the open or the read in flight.
 */
suspend fun encodeImage(resolver: ContentResolver, stream: SharedStream, into: File): EncodedImage? {
    into.mkdirs()
    val source = File(into, "${UUID.randomUUID()}.source")
    return try {
        // Opened as ImageDecoder's own content source opens it, with a signal the cancel reaches.
        val read = copyOpened({ resolver.openTypedAssetFileDescriptor(stream.uri, "image/*", null, it) }, source, 0)
        if (read !is Outcome.Done) return null
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val (width, height) = targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            encode(bitmap, stream, into)
        }.getOrNull()
    } finally {
        source.delete()
    }
}

private fun encode(bitmap: Bitmap, stream: SharedStream, into: File): EncodedImage? =
    try {
        into.mkdirs()
        val png = stream.type == "image/png"
        val first = if (png) write(bitmap, Bitmap.CompressFormat.PNG, into, "png") else jpeg(bitmap, into)
        when {
            first.length() <= IMAGE_BYTES_LIMIT -> EncodedImage(first, if (png) "image/png" else "image/jpeg", first.length())
            !png -> { first.delete(); null }
            else -> {
                first.delete()
                val again = jpeg(bitmap, into)
                if (again.length() <= IMAGE_BYTES_LIMIT) EncodedImage(again, "image/jpeg", again.length())
                else { again.delete(); null }
            }
        }
    } finally {
        bitmap.recycle()
    }

private fun jpeg(bitmap: Bitmap, into: File): File {
    if (!bitmap.hasAlpha()) return write(bitmap, Bitmap.CompressFormat.JPEG, into, "jpg")
    val flat = opaque(bitmap)
    try {
        return write(flat, Bitmap.CompressFormat.JPEG, into, "jpg")
    } finally {
        flat.recycle()
    }
}

private fun opaque(bitmap: Bitmap): Bitmap {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (i in pixels.indices) pixels[i] = onWhite(pixels[i])
    return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
}

/** [bitmap] compressed into a new file under [into]; a failed write leaves no file. */
private fun write(bitmap: Bitmap, format: Bitmap.CompressFormat, into: File, extension: String): File {
    val file = File(into, "${UUID.randomUUID()}.$extension")
    try {
        val written = file.outputStream().use { bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 85, it) }
        check(written) { "the image was not compressed" }
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
    return file
}

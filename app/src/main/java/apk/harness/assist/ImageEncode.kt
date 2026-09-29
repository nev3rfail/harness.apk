package apk.harness.assist

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** An image ready to go as a block: its file under the cache directory, its type and size. */
data class EncodedImage(val file: File, val mediaType: String, val bytes: Long)

/**
 * [stream] decoded with ImageDecoder (every format the platform reads, HEIC
 * included) at [targetSize], and encoded into [into]: PNG from a PNG, else JPEG
 * at quality 85. An encoding over [IMAGE_BYTES_LIMIT] is encoded again as JPEG.
 * Every JPEG of an image with alpha is composited onto white first. Null when
 * the stream does not decode, or its JPEG is still over the limit; the caller
 * copies it as a file instead. Cancelling stops the open or the read in flight.
 */
suspend fun encodeImage(resolver: ContentResolver, stream: HandedStream<Uri>, into: File): EncodedImage? = runCatching {
    // Opened as ImageDecoder's own content source opens it, with a signal the cancel reaches.
    readShared({ resolver.openTypedAssetFileDescriptor(stream.uri, "image/*", null, it) }) { descriptor ->
        ImageDecoder.decodeBitmap(ImageDecoder.createSource { descriptor }) { decoder, info, _ ->
            val (width, height) = targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }?.let { encode(it, stream, into) }
}.getOrElse { failure ->
    if (failure is CancellationException) throw failure
    null
}

private fun encode(bitmap: Bitmap, stream: HandedStream<Uri>, into: File): EncodedImage? =
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

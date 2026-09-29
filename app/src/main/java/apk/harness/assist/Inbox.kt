package apk.harness.assist

import java.io.File

const val NAME_BYTES = 128

/**
 * Removes what earlier shares left behind: the directory under [inbox] of each
 * share named by a file under [unsent], which is one never sent, with that
 * file, and every directory under [cache]. The shares [inUse] keep theirs.
 */
fun clearAbandoned(unsent: File, inbox: File, cache: File, inUse: Set<String>) {
    unsent.listFiles()?.forEach { mark ->
        if (mark.name in inUse) return@forEach
        File(inbox, mark.name).deleteRecursively()
        mark.delete()
    }
    cache.listFiles()?.forEach { if (it.name !in inUse) it.deleteRecursively() }
}
const val FILE_LIMIT = 100L * 1024 * 1024
const val SHARE_LIMIT = 500L * 1024 * 1024

/**
 * A file name from a `DISPLAY_NAME` the sender controls: the part after the
 * last `/` or `\`, control characters removed, a leading `.` replaced by `_`,
 * cut to [NAME_BYTES] bytes of UTF-8, and `shared` when nothing is left.
 */
fun safeName(name: String?): String {
    val last = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val clean = last.filterNot { it.isISOControl() }
    val undotted = if (clean.startsWith('.')) "_" + clean.substring(1) else clean
    return cutUtf8(undotted, NAME_BYTES).ifBlank { "shared" }
}

private fun cutUtf8(text: String, bytes: Int): String {
    val out = StringBuilder()
    var used = 0
    var at = 0
    while (at < text.length) {
        val point = text.codePointAt(at)
        val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
        if (used + size > bytes) break
        out.appendCodePoint(point)
        used += size
        at += Character.charCount(point)
    }
    return out.toString()
}

/** [name], or with `-2`, `-3` before its extension when [taken] holds it. */
fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot)
    val extension = name.substring(dot)
    return generateSequence(2) { it + 1 }.map { "$stem-$it$extension" }.first { it !in taken }
}

/** Which streams, by index, are copied and which refused before the copy. */
data class CopyPlan(val copy: List<Int>, val refused: List<Int>)

/**
 * [sizes] from `SIZE`, null or negative where unknown. A known size over [FILE_LIMIT] is
 * refused; the known sizes run up the share, and the one crossing
 * [SHARE_LIMIT] and every one after it are refused.
 */
fun planCopies(sizes: List<Long?>): CopyPlan {
    val copy = ArrayList<Int>()
    val refused = ArrayList<Int>()
    var share = 0L
    for ((index, reported) in sizes.withIndex()) {
        val size = reported?.takeIf { it >= 0 }
        when {
            share > SHARE_LIMIT -> refused += index
            size != null && size > FILE_LIMIT -> refused += index
            size != null && share + size > SHARE_LIMIT -> { share = SHARE_LIMIT + 1; refused += index }
            else -> { share += size ?: 0; copy += index }
        }
    }
    return CopyPlan(copy, refused)
}

enum class Limit { File, Share }

/** The limit the bytes read so far cross, or null. */
fun limitCrossed(fileBytes: Long, shareBytes: Long): Limit? = when {
    fileBytes > FILE_LIMIT -> Limit.File
    shareBytes > SHARE_LIMIT -> Limit.Share
    else -> null
}

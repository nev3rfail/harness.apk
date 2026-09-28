package apk.harness.assist

/**
 * Base64 bytes a conversation's images may carry in all. The API refuses a
 * request over 32 MB and every follow-up resends earlier images, so this leaves
 * room for the text and the rest of the request.
 */
const val IMAGE_BUDGET: Long = 20L * 1024 * 1024

/** Images a conversation may carry in all. */
const val IMAGE_COUNT = 100

/** Images one message carries as blocks; the rest go as files. */
const val MESSAGE_IMAGES = 20

fun base64Size(bytes: Long): Long = (bytes + 2) / 3 * 4

/** Which of the images being added go as blocks and which as files, by index. */
data class ImageSplit(val blocks: List<Int>, val files: List<Int>)

/**
 * [adding], in order, against the base64 sizes [sent] earlier in the
 * conversation. The first image that does not fit -- past the budget, the
 * count, or the twentieth of this message -- and every one after it go as files.
 */
fun imageBudget(sent: List<Long>, adding: List<Long>): ImageSplit {
    var total = sent.sum()
    var count = sent.size
    val blocks = ArrayList<Int>()
    for ((index, size) in adding.withIndex()) {
        if (blocks.size >= MESSAGE_IMAGES || count >= IMAGE_COUNT || total + size > IMAGE_BUDGET) {
            return ImageSplit(blocks, (index until adding.size).toList())
        }
        blocks += index
        total += size
        count++
    }
    return ImageSplit(blocks, emptyList())
}

/** Whether a screenshot of base64 [size] still fits the conversation. */
fun screenshotFits(sent: List<Long>, size: Long): Boolean =
    imageBudget(sent, listOf(size)).blocks.isNotEmpty()

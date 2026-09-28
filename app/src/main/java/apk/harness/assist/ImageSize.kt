package apk.harness.assist

import kotlin.math.roundToInt

/** Where the model's own scaling starts: anything larger costs upload for detail it does not see. */
const val IMAGE_EDGE = 1568

/** [width] x [height] scaled so the long edge is at most [IMAGE_EDGE]. */
fun targetSize(width: Int, height: Int): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (long <= IMAGE_EDGE) return width to height
    val scale = IMAGE_EDGE.toDouble() / long
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

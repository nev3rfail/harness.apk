package apk.harness.assist

/** A speech recognizer on the device, as `queryIntentServices` resolves it. */
data class RecognizerCandidate(val packageName: String, val className: String, val system: Boolean)

/** Every channel's forwarder, which would forward back. */
const val FORWARDER_CLASS = "apk.harness.assist.ForwardingRecognitionService"

/** The first system recognizer by package, else the first by package, never a forwarder. */
fun forwardTarget(candidates: List<RecognizerCandidate>): RecognizerCandidate? {
    val real = candidates.filter { it.className != FORWARDER_CLASS }.sortedBy { it.packageName }
    return real.firstOrNull { it.system } ?: real.firstOrNull()
}

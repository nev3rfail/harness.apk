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

/**
 * Whether the app asks for the microphone on behalf of other apps' voice input:
 * on Android 11 the assistant's forwarder is refused without the app's own grant.
 */
fun asksMicForOtherApps(api: Int, roleHeld: Boolean, granted: Boolean): Boolean = api == 30 && roleHeld && !granted

/** Which recognizer the panel's listen creates. */
enum class RecognizerKind { OnDevice, Default, Forward }

/**
 * From Android 12, the on-device recognizer when [onDevice] is wanted and
 * [onDeviceAvailable], else the default one; on Android 11 the recognizer the
 * forwarder would pick, since the default may be this app's own forwarder.
 * [onDeviceAvailable] is asked only from Android 12.
 */
fun recognizerKind(api: Int, onDevice: Boolean, onDeviceAvailable: () -> Boolean): RecognizerKind = when {
    api < 31 -> RecognizerKind.Forward
    onDevice && onDeviceAvailable() -> RecognizerKind.OnDevice
    else -> RecognizerKind.Default
}

/** Whether a listen's error starts it again on the default recognizer: once, for an on-device one lacking the language. */
fun retriesOnline(onDevice: Boolean, retried: Boolean, noLanguage: Boolean): Boolean = onDevice && !retried && noLanguage

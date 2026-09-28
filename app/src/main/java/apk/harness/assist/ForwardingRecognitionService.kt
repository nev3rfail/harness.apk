package apk.harness.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognition service the assistant role requires beside the voice
 * interaction service. Enabled on Android 11 only, where picking the assistant
 * also makes it the device's speech recognizer.
 */
class ForwardingRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}

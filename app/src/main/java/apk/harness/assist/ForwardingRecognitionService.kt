package apk.harness.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The recognition service the assistant role requires beside the voice
 * interaction service. Enabled on Android 11 only, where picking the assistant
 * also makes it the device's speech recognizer: it passes each listen to
 * [forwardTarget] and every answer back, keeping nothing.
 */
class ForwardingRecognitionService : RecognitionService() {
    private var forwarded: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val target = forwardTarget(recognizerCandidates(this))
            ?: return run { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
        drop()
        Log.i(TAG, "forwarding to ${target.packageName}")
        forwarded = SpeechRecognizer.createSpeechRecognizer(this, target.component()).apply {
            setRecognitionListener(Relay(listener))
            startListening(recognizerIntent)
        }
    }

    override fun onStopListening(listener: Callback) { forwarded?.stopListening() }

    override fun onCancel(listener: Callback) { forwarded?.cancel(); drop() }

    override fun onDestroy() { drop(); super.onDestroy() }

    private fun drop() { forwarded?.destroy(); forwarded = null }

    /** Every listener call to the matching callback; nothing is kept. */
    private inner class Relay(private val callback: Callback) : RecognitionListener {
        private fun pass(block: () -> Unit) { runCatching(block) }
        override fun onReadyForSpeech(params: Bundle?) = pass { callback.readyForSpeech(params ?: Bundle()) }
        override fun onBeginningOfSpeech() = pass { callback.beginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = pass { callback.rmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = pass { buffer?.let(callback::bufferReceived) }
        override fun onEndOfSpeech() = pass { callback.endOfSpeech() }
        override fun onPartialResults(partialResults: Bundle) = pass { callback.partialResults(partialResults) }
        override fun onResults(results: Bundle) { pass { callback.results(results) }; drop() }
        override fun onError(error: Int) { Log.i(TAG, "forwarded recognizer error $error"); pass { callback.error(error) }; drop() }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

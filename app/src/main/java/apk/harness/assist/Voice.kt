package apk.harness.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The panel's speech-to-text, through the phone's own recognizer: on the device
 * where it can be, else the default one, and on Android 11 the recognizer the
 * forwarder would pick, since the default may be this app's own forwarder.
 * [onProblem] gets the line to show under the field, or null to clear it.
 */
class Voice(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onProblem: (String?) -> Unit,
    private val onListening: (Boolean) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    /** An on-device listen that lacked the language has been replaced once. */
    private var retried = false

    fun start() {
        retried = false
        listen(onDevice = true)
    }

    fun stop() {
        recognizer?.stopListening()
        onListening(false)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen(onDevice: Boolean) {
        destroy()
        val (created, usedOnDevice) = create(onDevice) ?: return run { onListening(false); onProblem(NO_RECOGNIZER) }
        Log.i(TAG, "listening, on device: $usedOnDevice")
        recognizer = created
        created.setRecognitionListener(listener(usedOnDevice))
        created.startListening(intent(preferOffline = !usedOnDevice))
        onProblem(null)
        onListening(true)
    }

    private fun create(onDevice: Boolean): Pair<SpeechRecognizer, Boolean>? = when {
        Build.VERSION.SDK_INT >= 31 && onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) to true
        Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createSpeechRecognizer(context) to false
        // On Android 11 the device default may be this app's own forwarder.
        else -> forwardTarget(recognizerCandidates(context))?.let { SpeechRecognizer.createSpeechRecognizer(context, it.component()) to false }
    }

    private fun intent(preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .apply { if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }

    private fun listener(onDevice: Boolean) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            Log.i(TAG, "recognizer error $error")
            val noLanguage = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            if (onDevice && !retried && noLanguage) {
                retried = true
                listen(onDevice = false)
                return
            }
            onListening(false)
            if (error != SpeechRecognizer.ERROR_NO_MATCH) onProblem("The speech recognizer could not hear that (error $error).")
        }
        override fun onResults(results: Bundle) {
            onListening(false)
            first(results)?.let(onFinal)
        }
        override fun onPartialResults(partialResults: Bundle) { first(partialResults)?.let(onPartial) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun first(bundle: Bundle) =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val NO_RECOGNIZER = "No speech recognizer on this phone."
    }
}

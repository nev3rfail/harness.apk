package apk.harness.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** The panel's speech-to-text, through the phone's own recognizer. */
class Voice(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var loud = 0
    /** Whether the on-device recognizer has been replaced once for this listen (Task 16). */
    private var retried = false

    fun start() {
        destroy()
        loud = 0
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        Log.i(TAG, "listening, on device: $onDevice")
        recognizer = created
        created.setRecognitionListener(listener())
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .apply { if (!onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) },
        )
        onListening(true)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun listener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "ready for speech") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "speech began") }
        override fun onRmsChanged(rmsdB: Float) { if (rmsdB > 0) loud++ }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Log.i(TAG, "speech ended after $loud frames above silence") }
        override fun onError(error: Int) { Log.i(TAG, "recognizer error $error"); onListening(false) }
        override fun onResults(results: Bundle) { first(results)?.let(onFinal); onListening(false) }
        override fun onPartialResults(partialResults: Bundle) { first(partialResults)?.let(onPartial) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun first(bundle: Bundle) =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}

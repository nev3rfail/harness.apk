package apk.harness.assist

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Asks for the microphone on the session's behalf, and says what it was told. */
class MicActivity : ComponentActivity() {
    private var answered = false
    /** Whether the app asked for the microphone before this request. */
    private var askedBefore = false

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answered = true
        val blocked = micBlocked(granted, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO), askedBefore)
        Assistant.mic.value = MicAnswer.Answered(granted, blocked)
        // The session hid for the dialog; granted or not, it comes back with its panel.
        Assistant.service?.showSession(Assistant.requestArgs(), 0)
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            askedBefore = Assistant.askingMic(this)
            Assistant.mic.value = MicAnswer.Started
            ask.launch(Manifest.permission.RECORD_AUDIO)
        } else askedBefore = savedInstanceState.getBoolean(ASKED_BEFORE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKED_BEFORE, askedBefore)
    }

    override fun onStop() {
        super.onStop()
        if (!answered && !isChangingConfigurations) {
            Assistant.mic.value = MicAnswer.Stopped
            // With the task: a permission dialog left in it would take the next
            // request's start, and its answer would reach no one.
            finishAndRemoveTask()
        }
    }
}

private const val ASKED_BEFORE = "askedBefore"

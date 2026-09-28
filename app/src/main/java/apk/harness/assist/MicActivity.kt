package apk.harness.assist

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Asks for the microphone on the session's behalf, and says what it was told. */
class MicActivity : ComponentActivity() {
    private var answered = false

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answered = true
        val blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        Assistant.mic.value = MicAnswer.Answered(granted, blocked)
        // The session hid for the dialog; granted or not, it comes back with its panel.
        if (MIC_DIALOG_UNDER_SESSION) Assistant.service?.showSession(Assistant.requestArgs(), 0)
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            Assistant.mic.value = MicAnswer.Started
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
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

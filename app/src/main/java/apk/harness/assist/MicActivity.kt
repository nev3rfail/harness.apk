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
        Assistant.mic.value = MicAnswer.Answered(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        super.onStop()
        if (!answered && !isChangingConfigurations) {
            Assistant.mic.value = MicAnswer.Stopped
            finish()
        }
    }
}

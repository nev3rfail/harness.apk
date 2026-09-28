package apk.harness.assist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apk.harness.ui.HarnessTheme

/** The panel in an activity, for ASSIST and, in plan 097, text and shares. */
class AskActivity : ComponentActivity(), PanelHost {
    override val inSession = false
    private var state by mutableStateOf<PanelState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HarnessTheme { state?.let { AskPanel(it, this) } } }
    }

    override fun onStart() {
        super.onStart()
        if (state == null) state = PanelState().also { it.begin(this) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) dismiss()
    }

    override fun dismiss() {
        state?.dismiss()
        state = null
        finish()
    }

    override fun askContext(text: Boolean, shot: Boolean) {}
    override fun requestMic() {}
}

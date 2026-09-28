package apk.harness.assist

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apk.harness.bootstrap.Bootstrap
import apk.harness.ui.HarnessTheme

/** The panel in an activity, for ASSIST. */
class AskActivity : ComponentActivity(), PanelHost {
    override val inSession = false
    private var state by mutableStateOf<PanelState?>(null)
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HarnessTheme { state?.let { AskPanel(it, this) } } }
    }

    override fun onStart() {
        super.onStart()
        // At start, not creation: an activity created behind the lock screen is
        // not left locked, and one shown while locked starts nothing.
        val current = state
        if (current != null && current.gate == PanelState.Gate.Open) return
        openPanel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new intent during an escalation is ignored until it ends.
        if (state?.escalating == true) return
        setIntent(intent)
        state?.dismiss()
        openPanel()
    }

    private fun openPanel() {
        state = when (classifyShow(keyguard.isKeyguardLocked, shown = false, ownRequest = false, staged = Bootstrap(this).isInstalled())) {
            Show.Locked -> PanelState.locked()
            Show.NotStaged -> PanelState.notStaged(this)
            else -> PanelState().also { it.begin(this); if (intent?.action == Intent.ACTION_ASSIST) it.listenIfGranted() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        state?.let { if (it.escalating) it.run?.hidden() else it.dismiss() }
        state = null
        finish()
    }

    override fun dismiss() {
        state?.dismiss()
        state = null
        finish()
    }

    override fun start(intent: Intent): Boolean =
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    override fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun askContext(text: Boolean, shot: Boolean) {}
    override fun requestMic() {}
}

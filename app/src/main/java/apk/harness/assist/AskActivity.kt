package apk.harness.assist

import android.Manifest
import android.app.ComponentCaller
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import apk.harness.bootstrap.Bootstrap
import apk.harness.ui.HarnessTheme

/** The panel in an activity, for ASSIST. */
class AskActivity : ComponentActivity(), PanelHost {
    override val inSession = false
    private var state by mutableStateOf<PanelState?>(null)
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    /** Whether the app asked for the microphone before the request in flight. */
    private var micAskedBefore = false
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) state?.micGranted()
        else state?.micBlocked = micBlocked(false, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO), micAskedBefore)
    }

    /** The screen going off ends the panel, as the lock screen does where one is set. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = end()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HarnessTheme { state?.let { AskPanel(it, this) } } }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        // The panel of the instance a recreation replaced, with its run.
        if (state == null) state = Assistant.recreated.also { Assistant.recreated = null }
        // At start, not creation: an activity created behind the lock screen is
        // not left locked, and one shown while locked starts nothing.
        show(summoned = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new intent during an escalation is ignored until it ends.
        if (state?.escalating == true) return
        setIntent(intent)
        // A second summon keeps the conversation and listens, as in the session;
        // a share replaces the panel with one holding it.
        show(summoned = true)
    }

    /** [summoned]: a new intent reached the activity on screen, so an open panel listens. */
    private fun show(summoned: Boolean) {
        val current = state
        val show = classifyShow(
            keyguard.isKeyguardLocked,
            shown = panelShown(current != null, current?.gate),
            ownRequest = false,
            staged = Bootstrap(this).isInstalled(),
        )
        if (show == Show.Listen && !(summoned && intent?.action in SHARE_ACTIONS)) {
            if (summoned && intent?.action == Intent.ACTION_ASSIST) current?.listenIfGranted()
            return
        }
        current?.dismiss()
        state = when (show) {
            Show.Locked -> PanelState.locked()
            Show.NotStaged -> PanelState.notStaged(this)
            else -> PanelState().also { panel ->
                panel.begin(this)
                when (intent?.action) {
                    Intent.ACTION_ASSIST -> panel.listenIfGranted()
                    in SHARE_ACTIONS -> panel.fromShare(this, askRequest(handedOf(intent, ::ownAuthority, senderAccess(summoned))))
                }
            }
        }
    }

    /**
     * Why a stream is refused on the sender's own access, or null. The grant
     * check the platform makes for `FLAG_GRANT_READ_URI_PERMISSION` does not
     * ask whether the sender may read a URI this app can already open, so the
     * app asks: from Android 15, whether the app that sent the intent in hand,
     * [summoned] through onNewIntent or at the start, may read the URI itself.
     * Android 11 to 14 name no sender the app can trust, so there a stream
     * whose provider this app could open without a grant is refused: its read
     * would be this app's, whatever the sender may read. On Android 11 that
     * includes MediaStore, where the platform grants any sender's URI.
     */
    private fun senderAccess(summoned: Boolean): (Uri) -> String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) callerAccess(if (summoned) currentCaller else initialCaller)
        else { uri -> if (opensWithoutGrant(uri.authority)) OPENS_WITHOUT_GRANT else null }

    private fun opensWithoutGrant(authority: String?): Boolean {
        val info = authority?.let { packageManager.resolveContentProvider(it.substringAfterLast('@'), 0) } ?: return false
        return opensWithoutGrant(info.exported, info.readPermission) { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun callerAccess(caller: ComponentCaller): (Uri) -> String? = { uri ->
        val access = runCatching { caller.checkContentUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.getOrNull()
        if (access == PackageManager.PERMISSION_GRANTED) null else SENDER_CANNOT_READ
    }

    /** Whether [authority] is one of this app's providers, exported or not. */
    private fun ownAuthority(authority: String): Boolean =
        packageManager.resolveContentProvider(authority, 0)?.packageName == packageName

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        // A recreation the configChanges do not absorb hands the panel and its
        // run to the new instance; any other end stops it.
        if (isChangingConfigurations) Assistant.recreated = state
        else state?.let { if (it.escalating) it.run?.hidden() else it.dismiss() }
        state = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) end()
    }

    /** Ends the panel as a hide does: an escalation reaches its stop, anything else is dismissed. */
    private fun end() {
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
    // The dialog pauses the activity and does not stop it, so onStop's dismiss does not fire.
    override fun requestMic() {
        micAskedBefore = Assistant.askingMic(this)
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

package apk.harness.assist

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apk.harness.BuildConfig
import apk.harness.agents.BACKENDS
import apk.harness.ui.HarnessTheme

/** The Ask Agent section: the role, the screen-context setting, consent and the privacy policy. */
class AskSettingsActivity : ComponentActivity() {
    private var held by mutableStateOf(false)
    private var settings by mutableStateOf(AssistantSettings())
    /** "Always" chosen without consent: the disclosure is drawn in place of the section. */
    private var disclosing by mutableStateOf(false)
    /** Android 11: the forwarder needs the app's own microphone for other apps' voice input. */
    private var otherAppsMic by mutableStateOf(false)
    private var micDisclosing by mutableStateOf(false)
    /** The microphone denied for good: only the app's settings can grant it now. */
    private var micBlocked by mutableStateOf(false)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        otherAppsMic = asksMicForOtherApps(this)
        micBlocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text(TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                        if (micDisclosing) {
                            BackHandler { micDisclosing = false }
                            MicDisclosure(
                                provider = BACKENDS.first().provider,
                                otherApps = true,
                                onContinue = {
                                    micDisclosing = false
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onNotNow = { micDisclosing = false },
                            )
                        } else if (disclosing) {
                            BackHandler { disclosing = false }
                            ScreenDisclosure(
                                provider = BACKENDS.first().provider,
                                always = true,
                                onAllow = {
                                    disclosing = false
                                    save(AssistantSettings(consented = true, screenContext = ScreenContext.Always))
                                },
                                onNotNow = { disclosing = false },
                                openPolicy = ::open,
                            )
                        } else Section()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val roles = getSystemService(RoleManager::class.java)
        held = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        settings = Assistant.settings(this)
        otherAppsMic = asksMicForOtherApps(this)
    }

    @Composable
    private fun Section() {
        Action(if (held) HELD else NOT_HELD) {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }
        if (otherAppsMic && micBlocked) Action(MIC_BLOCKED) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        } else if (otherAppsMic) Action(OTHER_APPS_MIC) { micDisclosing = true }
        HorizontalDivider()
        Text(SCREEN_CONTEXT, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Choice(ASK_EACH_TIME, settings.screenContext == ScreenContext.Ask) {
            save(settings.copy(screenContext = ScreenContext.Ask))
        }
        Choice(ALWAYS, settings.screenContext == ScreenContext.Always) {
            if (settings.consented) save(settings.copy(screenContext = ScreenContext.Always)) else disclosing = true
        }
        if (settings.consented) Action(WITHDRAW) { save(settings.withdrawn()) }
        if (BuildConfig.PRIVACY_POLICY_URL.isNotEmpty()) {
            HorizontalDivider()
            Action(PRIVACY_POLICY) { open(BuildConfig.PRIVACY_POLICY_URL) }
        }
    }

    @Composable
    private fun Action(text: String, onClick: () -> Unit) {
        Text(text, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp))
    }

    @Composable
    private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Text(text)
        }
    }

    private fun save(next: AssistantSettings) {
        Assistant.save(this, next)
        settings = next
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

private const val TITLE = "Ask Agent"
private const val HELD = "Ask Agent is the assistant"
private const val NOT_HELD = "Make Ask Agent the assistant"
private const val SCREEN_CONTEXT = "Screen context"
private const val ASK_EACH_TIME = "Ask each time"
private const val ALWAYS = "Always"
private const val WITHDRAW = "Withdraw screen consent"
private const val PRIVACY_POLICY = "Privacy policy"

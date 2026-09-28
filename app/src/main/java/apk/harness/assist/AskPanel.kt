package apk.harness.assist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** What the panel asks of whatever hosts it: the session window or AskActivity. */
interface PanelHost {
    /** True in the session window, which can read the screen and cannot ask for permissions. */
    val inSession: Boolean
    fun dismiss()
    fun askContext(text: Boolean, shot: Boolean)
    fun requestMic()
}

@Composable
fun AskPanel(state: PanelState, host: PanelHost) {
    // Only an activity has a back dispatcher; the session hears back in onBackPressed.
    if (!host.inSession) BackHandler { host.dismiss() }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val sheet = maxHeight * SHEET_SHARE
        Box(
            Modifier.fillMaxSize().background(SCRIM)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = host::dismiss),
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = sheet),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = host::dismiss) { Icon(Icons.Default.Close, contentDescription = CLOSE) }
                }
                state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (host.inSession) Row {
                    TextButton(onClick = { host.askContext(text = true, shot = false) }) { Text(SCREEN_TEXT) }
                    TextButton(onClick = { host.askContext(text = true, shot = true) }) { Text(SCREENSHOT) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.field, onValueChange = { state.field = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = host::requestMic) { Icon(Icons.Default.Mic, contentDescription = MIC) }
                }
            }
        }
    }
}

private const val SHEET_SHARE = 0.75f
private val SCRIM = Color.Black.copy(alpha = 0.4f)
private const val TITLE = "Ask Agent"
private const val CLOSE = "Close"
private const val MIC = "Speak"
private const val SCREEN_TEXT = "Screen text"
private const val SCREENSHOT = "Screenshot"

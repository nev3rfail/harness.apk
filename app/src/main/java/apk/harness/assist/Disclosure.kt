package apk.harness.assist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apk.harness.BuildConfig

/** What a disclosure on screen is for, and what "Allow" or "Continue" then does. */
sealed interface Disclosing {
    /** A chip waiting on screen consent. */
    data class Screen(val text: Boolean, val shot: Boolean) : Disclosing
    data object Mic : Disclosing
}

/**
 * The screen-content disclosure. [always] words it for the setting that reads
 * the screen on every summon. [openPolicy] opens the privacy policy.
 */
@Composable
fun ScreenDisclosure(provider: String, always: Boolean, onAllow: () -> Unit, onNotNow: () -> Unit, openPolicy: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(SCREEN_TITLE, style = MaterialTheme.typography.titleMedium)
        Text(
            "$SCREEN_BODY$provider$SCREEN_BODY_END ${if (always) SCREEN_ALWAYS else SCREEN_ASKED}",
            modifier = Modifier.padding(vertical = 8.dp),
        )
        val policy = BuildConfig.PRIVACY_POLICY_URL
        if (policy.isNotEmpty() && openPolicy != null) TextButton(onClick = { openPolicy(policy) }) { Text(PRIVACY_POLICY) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotNow) { Text(NOT_NOW) }
            TextButton(onClick = onAllow) { Text(ALLOW) }
        }
    }
}

private const val SCREEN_TITLE = "Screen content"
private const val SCREEN_BODY = "When you turn on \"Screen text\" or \"Screenshot\", Ask Agent reads what is on your " +
    "screen at that moment: the text shown by the app you are in, except password fields, and a picture of the " +
    "screen. It is sent with your next message to "
private const val SCREEN_BODY_END = ", which runs the agent, to answer that message, and it is kept in the " +
    "conversation's history on this phone."
private const val SCREEN_ASKED = "Nothing is read unless you turn one on."
private const val SCREEN_ALWAYS = "Ask Agent reads it every time you open it."
private const val PRIVACY_POLICY = "Privacy policy"
private const val NOT_NOW = "Not now"
private const val ALLOW = "Allow"

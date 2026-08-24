package apk.harness.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The keys a soft keyboard does not have.
 *
 * Control and Alt latch rather than being held, since there is nothing to hold
 * them with; the terminal clears them once a key has consumed them.
 */
@Composable
fun InputToolbar(
    onKey: (String) -> Unit,
    onShowKeyboard: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Key("ESC", Modifier.weight(1f)) { onKey(ESCAPE) }
            Key("TAB", Modifier.weight(1f)) { onKey("\t") }
            Key("CTRL", Modifier.weight(1f), active = ctrlActive, onClick = onToggleCtrl)
            Key("ALT", Modifier.weight(1f), active = altActive, onClick = onToggleAlt)
            Key("↑", Modifier.weight(1f)) { onKey(CSI + "A") }
            Key("↓", Modifier.weight(1f)) { onKey(CSI + "B") }
            Key("⌨", Modifier.weight(1f), onClick = onShowKeyboard)
        }
    }
}

@Composable
private fun Key(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val text: @Composable () -> Unit = {
        Text(label, textAlign = TextAlign.Center, maxLines = 1)
    }
    if (active) {
        Button(onClick = onClick, modifier = modifier, contentPadding = KeyPadding) { text() }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = KeyPadding) { text() }
    }
}

private val KeyPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 2.dp,
    vertical = 4.dp,
)

// The escape byte is spelled by code point so no literal control character ever
// has to survive a copy through a file.
private val ESCAPE = 27.toChar().toString()
private val CSI = ESCAPE + "["

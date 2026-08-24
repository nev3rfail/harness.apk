package apk.harness.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A terminal is dark, and the panels sit over it, so the whole app is.
private val scheme = darkColorScheme(
    primary = Color(0xFFE0A88F),
    onPrimary = Color(0xFF1A1614),
    surface = Color(0xFF16130F),
    onSurface = Color(0xFFEDE6DE),
    surfaceVariant = Color(0xFF262119),
    onSurfaceVariant = Color(0xFFCFC5B8),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDE6DE),
    error = Color(0xFFE08080),
)

@Composable
fun HarnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

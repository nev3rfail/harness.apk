package apk.harness.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import apk.harness.bootstrap.BootstrapProgress

/**
 * The first run, which is a download and a rewrite of ninety-odd megabytes.
 *
 * This replaces the terminal rather than covering it, so it is a screen and not
 * a dialog the way the panels are: a panel needs its own window because the
 * terminal surface is composited above the activity's, and there is no terminal
 * here yet.
 *
 * Every phase is slow enough to read as a hang, so every phase reports a count.
 * A [progress] of null is the install not having reported anything yet.
 */
@Composable
fun BootstrapScreen(progress: BootstrapProgress?, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress is BootstrapProgress.Failed) {
                Failure(progress.reason)
                // A dropped download is the failure this will see most, and the
                // remedy is to ask again. Every step is idempotent, so a retry
                // costs only what the last attempt did not finish.
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Try again")
                }
            } else {
                Working(progress)
            }
        }
    }
}

@Composable
private fun Working(progress: BootstrapProgress?) {
    val (label, fraction) = describe(progress)
    Text("Preparing the userland", style = MaterialTheme.typography.titleMedium)
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    // Determinate only where a fraction is actually being reported. A bar parked
    // at half for the length of a pass reads as a hang; a moving indeterminate
    // one reads as work.
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.padding(top = 24.dp))
    } else {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/**
 * The reason, in full and in monospace: it carries paths and checksums, and it
 * is the only thing the operator has to go on.
 */
@Composable
private fun Failure(reason: String) {
    Text(
        "Could not prepare the userland",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
}

private fun describe(progress: BootstrapProgress?): Pair<String, Float?> = when (progress) {
    null -> "starting" to null
    is BootstrapProgress.Downloading ->
        "${progress.bytes / 1_000_000} of ${progress.total / 1_000_000} MB" to
            fraction(progress.bytes, progress.total)
    is BootstrapProgress.Extracting ->
        "${progress.entries} of ${progress.total} files" to
            fraction(progress.entries.toLong(), progress.total.toLong())
    // Linking and relocating report their bounds and then their completion, with
    // nothing in between to draw, so both are counted and neither is a fraction.
    is BootstrapProgress.Linking -> "${progress.made} of ${progress.total} links" to null
    is BootstrapProgress.Relocating ->
        "rewriting paths, pass ${progress.pair} of ${progress.of}" to null
    BootstrapProgress.Configuring -> "configuring the package manager" to null
    BootstrapProgress.Done -> "ready" to 1f
    // Drawn by Failure, which is what this is never asked about.
    is BootstrapProgress.Failed -> progress.reason to null
}

private fun fraction(done: Long, total: Long): Float? =
    if (total <= 0L) null else (done.toFloat() / total).coerceIn(0f, 1f)

package apk.harness.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import apk.harness.ide.DiffDecision
import apk.harness.ide.Surface
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * What the agent put on screen, over the terminal.
 *
 * The point of the harness: a file, a diff, a rendered document or a map, drawn
 * by the app instead of drawn with ANSI in a scrollback buffer.
 */
@Composable
fun SurfacePanel(
    surface: Surface,
    onDecide: (Surface.Diff, DiffDecision) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialSurface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Header(
                title = surface.title(),
                subtitle = surface.subtitle(),
                onDismiss = onDismiss,
            )
            HorizontalDivider()

            // Clipped: the map view draws beyond the bounds it is given.
            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                when (surface) {
                    is Surface.Diff -> DiffBody(surface)
                    is Surface.FileView -> Prose(surface.text)
                    is Surface.Markdown -> Prose(surface.text)
                    is Surface.Place -> Map(surface)
                }
            }

            if (surface is Surface.Diff) {
                HorizontalDivider()
                DiffActions(
                    onAccept = { onDecide(surface, DiffDecision.Accepted) },
                    onReject = { onDecide(surface, DiffDecision.Rejected) },
                )
            }
        }
    }
}

private fun Surface.title(): String = when (this) {
    is Surface.Diff -> "Review edit"
    is Surface.FileView -> path.substringAfterLast('/')
    is Surface.Markdown -> title
    is Surface.Place -> label
}

private fun Surface.subtitle(): String? = when (this) {
    is Surface.Diff -> path
    is Surface.FileView -> path
    is Surface.Markdown -> null
    is Surface.Place -> "%.5f, %.5f".format(latitude, longitude)
}

@Composable
internal fun Header(title: String, subtitle: String?, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun DiffActions(onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
        Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Apply") }
    }
}

/**
 * A line-level diff.
 *
 * The longest common subsequence of the two line lists is what stayed; anything
 * off it is a removal or an addition. It is O(n*m) in lines, which is the lazy
 * choice and fine for a file a person is about to read.
 */
@Composable
private fun DiffBody(diff: Surface.Diff) {
    val lines = diffLines(diff.oldText.lines(), diff.newText.lines())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            val background = when (line.kind) {
                LineKind.Added -> Color(0x3327A05B)
                LineKind.Removed -> Color(0x33C4453F)
                LineKind.Kept -> Color.Transparent
            }
            val marker = when (line.kind) {
                LineKind.Added -> "+"
                LineKind.Removed -> "-"
                LineKind.Kept -> " "
            }
            Text(
                text = "$marker ${line.text}",
                modifier = Modifier.background(background).padding(horizontal = 12.dp, vertical = 1.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

private enum class LineKind { Kept, Added, Removed }

private data class DiffLine(val kind: LineKind, val text: String)

private fun diffLines(old: List<String>, new: List<String>): List<DiffLine> {
    // ponytail: O(n*m) table; switch to a Myers diff if whole-repo diffs ever
    // arrive here rather than single files.
    val lengths = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            lengths[i][j] = if (old[i] == new[j]) lengths[i + 1][j + 1] + 1
            else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < old.size && j < new.size) {
        when {
            old[i] == new[j] -> { result += DiffLine(LineKind.Kept, old[i]); i++; j++ }
            lengths[i + 1][j] >= lengths[i][j + 1] -> { result += DiffLine(LineKind.Removed, old[i]); i++ }
            else -> { result += DiffLine(LineKind.Added, new[j]); j++ }
        }
    }
    while (i < old.size) result += DiffLine(LineKind.Removed, old[i++])
    while (j < new.size) result += DiffLine(LineKind.Added, new[j++])
    return result
}

@Composable
private fun Prose(markdown: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        MarkdownDocument(content = markdown)
    }
}

@Composable
private fun Map(place: Surface.Place) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            configureOsmdroid(context)
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                val point = GeoPoint(place.latitude, place.longitude)
                controller.setZoom(place.zoom)
                controller.setCenter(point)
                overlays.add(Marker(this).apply {
                    position = point
                    title = place.label
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
        },
    )
}

/**
 * osmdroid keeps a tile cache on disk and identifies itself to the tile server,
 * both of which it refuses to guess at. The cache goes in the app's own space.
 */
private fun configureOsmdroid(context: Context) {
    val configuration = Configuration.getInstance()
    if (configuration.userAgentValue == context.packageName) return
    configuration.userAgentValue = context.packageName
    configuration.osmdroidBasePath = context.cacheDir.resolve("osmdroid")
    configuration.osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
}

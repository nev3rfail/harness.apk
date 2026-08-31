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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import apk.harness.ide.Selection
import apk.harness.ide.Surface
import apk.harness.ide.label
import apk.harness.ide.pointAt
import apk.harness.intents.Action
import apk.harness.intents.ExtraValue
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
 *
 * [onPark] puts a document away behind the band at the bottom of the screen.
 * Only a document offers it: a diff and a handoff are questions an agent is
 * blocked on and a place is a look at one thing, and none of those is something
 * to come back to later.
 * Null for a document there is nowhere to park, which is one no chat owns.
 *
 * [scroll] and [onScroll] are how a parked document comes back where it was
 * left. Parking destroys this composition, so the offset is reported to the
 * caller, which outlives it, and read back from there when it is built again.
 *
 * [selection] arrives the same way and for the same reason: the point of holding
 * one is to put the document away and then talk about it, which happens after
 * this composition is gone. [onSelect] is called with what the person has
 * pointed at, or null when they have dropped it.
 *
 * [openExternal] is the way out of the app, which a document needs because a
 * cell's source mark is a reference to follow rather than a string to read.
 *
 * [onAnswer] is the same thing for a handoff that [onDecide] is for a diff: both
 * carry a person's answer back to a tool call that is blocked on it.
 */
@Composable
fun SurfacePanel(
    surface: Surface,
    onDecide: (Surface.Diff, DiffDecision) -> Unit,
    onAnswer: (Surface.Handoff, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onPark: (() -> Unit)?,
    scroll: Int,
    onScroll: (Int) -> Unit,
    selection: Selection?,
    onSelect: (Selection?) -> Unit,
    modifier: Modifier = Modifier,
    openExternal: ((String) -> Unit)? = null,
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
                onPark = if (surface is Surface.Document) onPark else null,
                // The file's numbering, not the document's: this reads back
                // what the agent was told, so it may not disagree with it.
                selected = (surface as? Surface.Document)
                    ?.let { document -> selection?.label(document.lineOffset) },
                onClear = { onSelect(null) },
            )
            HorizontalDivider()

            // Clipped: the map view draws beyond the bounds it is given.
            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                when (surface) {
                    is Surface.Diff -> DiffBody(surface)
                    is Surface.Document ->
                        Prose(surface, selection, onSelect, scroll, onScroll, openExternal)
                    is Surface.Handoff -> HandoffBody(surface)
                    is Surface.Place -> Map(surface)
                }
            }

            when (surface) {
                is Surface.Diff -> {
                    HorizontalDivider()
                    Decision(
                        no = "Reject",
                        yes = "Apply",
                        onNo = { onDecide(surface, DiffDecision.Rejected) },
                        onYes = { onDecide(surface, DiffDecision.Accepted) },
                    )
                }

                is Surface.Handoff -> {
                    HorizontalDivider()
                    Decision(
                        no = "Cancel",
                        yes = "Hand it over",
                        onNo = { onAnswer(surface, false) },
                        onYes = { onAnswer(surface, true) },
                    )
                }

                else -> Unit
            }
        }
    }
}

private fun Surface.title(): String = when (this) {
    is Surface.Diff -> "Review edit"
    is Surface.Document -> path.substringAfterLast('/')
    is Surface.Handoff -> handoff.said()
    is Surface.Place -> label
}

private fun Surface.subtitle(): String? = when (this) {
    is Surface.Diff -> path
    is Surface.Document -> path
    // The app that would receive it, resolved before the question was asked.
    // Directly under the title because it is half of what is being decided:
    // the other half is what it carries, which is the body.
    is Surface.Handoff -> app
    is Surface.Place -> "%.5f, %.5f".format(latitude, longitude)
}

/**
 * What the fire is, said the way a person would say it.
 *
 * Not the `action` word and not Android's constant: the operator is answering a
 * question about their own phone, and `share_many` is neither of those things
 * said aloud.
 */
private fun apk.harness.intents.Handoff.said(): String = when (action) {
    Action.View -> if (content.isEmpty()) "Open a link" else "Open a file elsewhere"
    Action.Share -> if (content.isEmpty()) "Share some text" else "Share a file"
    Action.ShareMany -> "Share ${content.size} files"
    Action.Compose -> "Write a mail"
    Action.Dial -> "Dial a number"
    Action.Settings -> "Open a settings screen"
    Action.Launch -> "Open another app"
}

/**
 * The panel's title, what is selected in it, and the two ways out of it.
 *
 * [onPark] is drawn as a chevron pointing the way the document goes, which is
 * the way the band it lands on is pulled back. It defaults to absent, so the
 * row is Close alone for the drawers and for a surface that does not park.
 *
 * [selected] is the range already written for a person, `L113-119`. It is the
 * only place they can read back what the agent has been told, and [onClear] is
 * beside it because a selection with nowhere on screen to drop it is one the
 * operator has to guess their way out of.
 *
 * [onAdd] adds something to what the header names -- a project to the list of
 * them, files to the directory. It is drawn at the head of the row, as far from
 * Close as the row is wide, and defaults to absent so that a surface with
 * nothing to add to draws the row without it.
 */
@Composable
internal fun Header(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onPark: (() -> Unit)? = null,
    selected: String? = null,
    onClear: () -> Unit = {},
    onAdd: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // An icon button carries its own inset, so a header that draws one
            // starts where a header that draws none has its text.
            .padding(
                start = if (onAdd == null) 16.dp else 4.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // At the head of the row, well clear of Close: the two do opposite
        // things, and a thumb aimed at one of them should not be able to find
        // the other.
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Default.Add,
                    // The colour every other control that adds something takes.
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = "Add",
                )
            }
        }
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
        if (selected != null) {
            Text(
                selected,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (onPark != null) {
            IconButton(onClick = onPark) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Put the document away",
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

/**
 * The two ways to answer a question, in the words that question uses.
 *
 * One row for both kinds, because a diff and a handoff ask the same thing of the
 * operator -- yes or no, with no way out of the panel that is neither -- and only
 * the words differ. [no] is drawn as the quieter of the two: the answer that
 * changes nothing should not be the one a thumb finds first.
 */
@Composable
private fun Decision(no: String, yes: String, onNo: () -> Unit, onYes: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onNo, modifier = Modifier.weight(1f)) { Text(no) }
        Button(onClick = onYes, modifier = Modifier.weight(1f)) { Text(yes) }
    }
}

/**
 * What is about to leave the app, in the order it matters.
 *
 * A file is drawn as its basename with the rest of the path beneath it: the
 * person is deciding whether *this file* goes out of the sandbox, and a long
 * path truncated to one line hides exactly the end that says which file it is.
 * The monospace is the diff body's, for the same reason -- these are strings to
 * be read character by character, not prose.
 */
@Composable
private fun HandoffBody(asked: Surface.Handoff) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        asked.handoff.content.forEach { path -> Carried(path) }
        asked.handoff.uri?.let { Detail("Address", it) }
        asked.handoff.mimeType?.let { Detail("Kind", it) }
        asked.handoff.target?.let { Detail("Package", it) }
        asked.handoff.extras.forEach { (name, value) -> Detail(name, value.written()) }
    }
}

/** One file, named by the part of the path a person can recognise. */
@Composable
private fun Carried(path: String) {
    Column {
        Text(
            path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (directory.isNotEmpty()) {
            Text(
                directory,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A labelled value: what a receiving app would read, under the name it reads it by. */
@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** An extra as the person reads it, rather than as the receiving app takes it. */
private fun ExtraValue.written(): String = when (this) {
    is ExtraValue.Text -> value
    is ExtraValue.Number -> if (value == value.toLong().toDouble()) "${value.toLong()}" else "$value"
    is ExtraValue.Flag -> if (value) "yes" else "no"
    is ExtraValue.Series -> values.joinToString(", ")
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
private fun Prose(
    document: Surface.Document,
    selection: Selection?,
    onSelect: (Selection?) -> Unit,
    scroll: Int,
    onScroll: (Int) -> Unit,
    openExternal: ((String) -> Unit)?,
) {
    val state = rememberScrollState(initial = scroll)
    // Reported once, as this goes away, because that is the only moment anything
    // reads it. Following the scroll frame by frame would tell the caller the
    // same number several hundred times to answer a question asked once.
    val report by rememberUpdatedState(onScroll)
    DisposableEffect(state) { onDispose { report(state.value) } }

    // The anchor is meaningful only while one run of pointing lasts, so it is
    // remembered here and goes when this composition does. A restored document
    // arrives with a selection and no anchor, and pointAt reads the selection's
    // own first line in its place.
    var anchor by remember(document.path) { mutableStateOf<apk.harness.ide.Unit?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(state)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        MarkdownDocument(
            content = document.markdown,
            selected = selection?.let { it.first..it.last },
            onPoint = { unit, anchoring ->
                val next = pointAt(document.path, anchor, selection, unit, anchoring)
                // A long press is where the anchor is dropped, and a tap that
                // dropped the selection ended the run of pointing it belonged to.
                anchor = when {
                    anchoring -> unit
                    next == null -> null
                    else -> anchor
                }
                onSelect(next)
            },
            openExternal = openExternal,
        )
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
internal fun configureOsmdroid(context: Context) {
    val configuration = Configuration.getInstance()
    if (configuration.userAgentValue == context.packageName) return
    configuration.userAgentValue = context.packageName
    configuration.osmdroidBasePath = context.cacheDir.resolve("osmdroid")
    configuration.osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
}

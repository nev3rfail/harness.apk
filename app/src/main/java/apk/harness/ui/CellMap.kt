package apk.harness.ui

import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import apk.harness.cells.CardSpan
import apk.harness.cells.Cell
import apk.harness.cells.CellKind
import apk.harness.cells.CellPoint
import apk.harness.cells.CellRow
import apk.harness.cells.Provenance
import apk.harness.cells.SCHEMAS
import apk.harness.cells.boundsOf
import apk.harness.cells.geoUri
import apk.harness.cells.markFor
import apk.harness.cells.pointOf
import apk.harness.cells.snappedIndex
import apk.harness.cells.textOf
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The places a map cell names: the pins above, the cards below.
 *
 * The map is the picture and the carousel is the reading. Swiping a card is what
 * moves the selection, which is the one gesture that has to work here -- a map
 * inside a scrolling document cannot also own the drag.
 *
 * [rows] is passed in rather than read from [cell] for the reason every cell
 * renderer takes it: a view's rows belong to the cell it refers to.
 * [openExternal] follows a source and hands a place to a map application.
 */
@Composable
internal fun CellMapBody(
    cell: Cell,
    rows: List<CellRow>,
    modifier: Modifier = Modifier,
    openExternal: ((String) -> Unit)? = null,
) {
    // A row the checker would have refused still reaches here, so a row with no
    // usable coordinates is left off both the map and the carousel rather than
    // drawn somewhere it is not.
    val places = remember(rows) { rows.mapNotNull { row -> pointOf(row)?.let { row to it } } }
    // Keyed on the cell, because this composition outlives the document it was
    // drawn for: a fresh map arriving into the same slot would otherwise open on
    // whatever card the last one was left on.
    val carousel = rememberSaveable(cell, rows, saver = LazyListState.Saver) { LazyListState() }
    // Derived from the list rather than stored beside it, so the pin and the
    // card in front of the person cannot come to disagree.
    val selected by remember {
        derivedStateOf {
            snappedIndex(
                carousel.layoutInfo.visibleItemsInfo.map { CardSpan(it.index, it.offset) },
            ) ?: 0
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = cell.text("title").orEmpty(),
            style = MaterialTheme.typography.titleSmall,
        )
        Pins(places, selected, cell.number("zoom"))
        LazyRow(
            state = carousel,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            flingBehavior = rememberSnapFlingBehavior(carousel),
        ) {
            items(places.size) { index ->
                val (row, point) = places[index]
                PlaceCard(cell, row, point, openExternal)
            }
        }
    }
}

/**
 * The map, fixed on the pins it was given.
 *
 * It neither pans nor zooms, so it takes no touch at all: a drag that began on
 * it reaches the document scrolling underneath instead of being eaten. The
 * horizontal half of that is claimed here, because sideways on this cell means
 * the carousel and nothing else.
 *
 * The camera is set in the update block rather than at construction, so a cell
 * whose rows changed moves the view it already has.
 */
@Composable
private fun Pins(places: List<Pair<CellRow, CellPoint>>, selected: Int, zoom: Double?) {
    val tint = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> change.consume() }
            },
        factory = { context ->
            configureOsmdroid(context)
            object : MapView(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean = false
            }.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(false)
            }
        },
        update = { map ->
            map.overlays.clear()
            places.forEachIndexed { index, (row, point) ->
                map.overlays.add(
                    Marker(map).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        title = textOf(row.fields["name"])
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        // Mutated, or the tint would follow the shared drawable
                        // onto every other pin on the map.
                        if (index == selected) icon = icon?.mutate()?.apply { setTint(tint) }
                    },
                )
            }
            // The camera is measured against the view's own size, which is not
            // known until it has been laid out.
            map.post { frame(map, places.map { it.second }, zoom) }
        },
    )
}

/** Puts every pin on screen, or centres the one there is. */
private fun frame(map: MapView, points: List<CellPoint>, zoom: Double?) {
    val box = boundsOf(points)
    if (box != null) {
        map.zoomToBoundingBox(
            BoundingBox(box.north, box.east, box.south, box.west),
            false,
            PIN_PADDING,
        )
    } else {
        // One place, or several rows naming one place: there is no region to
        // fit, so the cell's own zoom decides how much of the city is in frame.
        points.firstOrNull()?.let {
            map.controller.setZoom(zoom ?: DEFAULT_ZOOM)
            map.controller.setCenter(GeoPoint(it.latitude, it.longitude))
        }
    }
    map.invalidate()
}

/**
 * One place, as the row wrote it.
 *
 * The name, the notes, and then every factual field the row carries with what
 * vouches for it beside it. That last part is the whole point of the card: the
 * reference app fills this space with ratings and photos, and that enrichment is
 * what makes an invented place look real.
 */
@Composable
private fun PlaceCard(
    cell: Cell,
    row: CellRow,
    point: CellPoint,
    openExternal: ((String) -> Unit)?,
) {
    Card(modifier = Modifier.padding(end = 12.dp).width(CARD_WIDTH)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = textOf(row.fields["name"]),
                style = MaterialTheme.typography.titleSmall,
            )
            val notes = textOf(row.fields["notes"])
            if (notes.isNotBlank()) {
                Text(
                    text = notes,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SCHEMAS.getValue(CellKind.Map).factual.forEach { field ->
                val value = row.fields[field] ?: return@forEach
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = field.replace('_', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = textOf(value),
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Vouching(markFor(CellKind.Map, field, row), openExternal)
                }
            }
            // The one handoff a document offers, and it offers one place: an
            // intent that carries a pin set does not exist.
            if (openExternal != null) {
                TextButton(
                    onClick = { openExternal(geoUri(point, cell.text("title").orEmpty())) },
                ) {
                    Text("Open in maps")
                }
            }
        }
    }
}

/**
 * What vouches for one value on a card, in words rather than a glyph.
 *
 * A card has the room a table cell does not, and the two marks say very
 * different things: an agent's inference and the operator's own say-so carry
 * different weight and a reader has to tell them apart without being told how.
 * A reference is followed rather than read, so it is the one that is tapped.
 */
@Composable
private fun Vouching(provenance: Provenance?, openExternal: ((String) -> Unit)?) {
    val label = when (provenance) {
        null -> return
        is Provenance.Source -> "source"
        Provenance.Reasoned -> "reasoned by the agent"
        Provenance.Operator -> "you said this"
    }
    val reference = (provenance as? Provenance.Source)?.reference
    if (reference != null && openExternal != null) {
        TextButton(onClick = { openExternal(reference) }) {
            Text(text = "$label ↗", style = MaterialTheme.typography.labelSmall)
        }
    } else {
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * About a quarter of the panel, which is the proportion `spec/038` sets: enough
 * to read a pin cluster, and the rest of the screen left for the prose and the
 * cells around it.
 */
private val MAP_HEIGHT = 275.dp

/** Wide enough for a name and a line of notes, narrow enough that the next card shows. */
private val CARD_WIDTH = 260.dp

/** Room around the outermost pins, so a pin is never drawn on the frame's edge. */
private const val PIN_PADDING = 48

/** What a single pin is drawn at when the cell names no zoom: a street, not a country. */
private const val DEFAULT_ZOOM = 14.0

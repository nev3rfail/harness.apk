package apk.harness.ui

import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
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
import apk.harness.cells.cardMark
import apk.harness.cells.geoUri
import apk.harness.cells.markFor
import apk.harness.cells.pointOf
import apk.harness.cells.snappedIndex
import apk.harness.cells.textOf
import kotlinx.coroutines.launch
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

    // Which card is open, held for the carousel rather than by the card, so
    // opening a second closes the first: two cards drawn over one another is two
    // places claiming to be the one being read.
    var opened by remember(cell, rows) { mutableStateOf<Int?>(null) }

    // An open panel travels with the card it belongs to, because a popup follows
    // the thing it is anchored on. So a swipe carries it out of the way rather
    // than being spent closing it, and where it comes to rest is the card the
    // carousel settled on rather than the one it left.
    LaunchedEffect(carousel.isScrollInProgress) {
        if (!carousel.isScrollInProgress && opened != null) opened = selected
    }
    val scope = rememberCoroutineScope()

    // How much of the map the cards stand on, which is what the pins have to be
    // framed clear of.
    val covered = with(LocalDensity.current) {
        (CARD_HEIGHT + STRIP_PADDING * 2).roundToPx()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = cell.text("title").orEmpty(),
            style = MaterialTheme.typography.titleSmall,
        )
        // The carousel stands on the map rather than under it, so the cell costs
        // the document the map's height and nothing more.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The map's height and not a pixel more, whatever the carousel
                // on top of it is doing. Sized by its tallest child instead,
                // this grows when a card opens and every block below the cell
                // moves down -- which is the one thing an overlay exists to
                // avoid. [PANEL_MAX] is what keeps a card inside it.
                .height(MAP_HEIGHT)
                // What closes an open panel besides its own chevron. A card and
                // a mark consume their own taps, and the map consumes only
                // drags, so what reaches here is a tap on the map: the nearest
                // thing to tapping away that a cell can offer, since a panel
                // the list can swipe cannot also be told about touches beyond
                // the cell.
                .pointerInput(Unit) { detectTapGestures { opened = null } },
        ) {
            Pins(places, selected, cell.number("zoom"), covered)
            LazyRow(
                modifier = Modifier.align(Alignment.BottomStart),
                state = carousel,
                // The row is as tall as its tallest card and sits on the
                // bottom, so an open card grows up over the map and the cell's
                // own height never changes. The collapsed cards beside it stay
                // on the same line as before.
                verticalAlignment = Alignment.Bottom,
                // No inset on the end: a list clips its items at the padded
                // edge, so an inset there cuts the peeking card short of the
                // screen and leaves a strip of background beside it. The card
                // that ends the list keeps a margin from its own trailing gap.
                contentPadding = PaddingValues(
                    start = 8.dp,
                    top = STRIP_PADDING,
                    bottom = STRIP_PADDING,
                ),
                flingBehavior = rememberSnapFlingBehavior(carousel),
            ) {
                items(places.size) { index ->
                    val (row, point) = places[index]
                    PlaceCard(
                        cell = cell,
                        row = row,
                        point = point,
                        opened = opened == index,
                        onToggle = {
                            if (opened == index) {
                                opened = null
                            } else {
                                opened = index
                                // Opening a card that is not the selection makes
                                // it the selection, or the pin lit on the map
                                // names one place while the panel reads another.
                                if (index != selected) {
                                    scope.launch { carousel.animateScrollToItem(index) }
                                }
                            }
                        },
                        openExternal = openExternal,
                    )
                }
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
 *
 * [covered] is how much of the bottom the carousel stands on, which the framing
 * keeps its pins out of.
 */
@Composable
private fun Pins(
    places: List<Pair<CellRow, CellPoint>>,
    selected: Int,
    zoom: Double?,
    covered: Int,
) {
    val tint = MaterialTheme.colorScheme.primary.toArgb()
    // The size the cell was laid out at, read inside the update block so a
    // resize runs it again. A frame is a region fitted to the view's own bounds,
    // so the one a narrow window produced holds only at that width: turning the
    // phone widens the cell and leaves the pins bunched, and turning it back
    // narrows the cell and puts them outside it.
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            // A map view draws whole tiles, so its grid reaches past the view
            // whenever the camera does not land on a tile boundary, and the
            // host a Compose `AndroidView` puts it in does not clip its
            // children. Unclipped, the overhang is drawn over the paragraphs
            // above and below the cell.
            .clipToBounds()
            .onSizeChanged { bounds = it }
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
                        // Every marker on a map is handed the one icon its
                        // MapView caches, and `mutate` answers with that same
                        // object, so tinting what a marker already holds tints
                        // every pin at once. A drawable taken off the constant
                        // state is this marker's alone, and only that copy is
                        // ever tinted: the rest stay osmdroid's default, which
                        // is what makes the selected one tell itself apart.
                        if (index == selected) {
                            icon?.constantState?.newDrawable(map.resources)?.mutate()?.let { own ->
                                own.setTint(tint)
                                icon = own
                            }
                        }
                    },
                )
            }
            // The camera is measured against the view's own size, which is not
            // known until it has been laid out.
            map.post { frame(map, places.map { it.second }, zoom, bounds, covered) }
        },
    )
}

/**
 * Puts every pin in the strip the cards leave clear, or centres the one there is.
 *
 * [size] is the cell's laid-out size. A view with none has no region to fit a
 * frame into, and the next size it is given brings the framing with it.
 *
 * [covered] is the height the carousel stands on. A map view fits a box to the
 * whole of itself and knows nothing of what is drawn over it, so the fit is
 * given half that height as extra border and the result is then scrolled by the
 * same amount: the first shrinks the framed region to the clear strip's height,
 * the second moves it up into the strip.
 */
private fun frame(
    map: MapView,
    points: List<CellPoint>,
    zoom: Double?,
    size: IntSize,
    covered: Int,
) {
    if (size == IntSize.Zero) return
    val box = boundsOf(points)
    if (box != null) {
        map.zoomToBoundingBox(
            BoundingBox(box.north, box.east, box.south, box.west),
            false,
            PIN_PADDING + covered / 2,
        )
    } else {
        // One place, or several rows naming one place: there is no region to
        // fit, so the cell's own zoom decides how much of the city is in frame.
        points.firstOrNull()?.let {
            map.controller.setZoom(zoom ?: DEFAULT_ZOOM)
            map.controller.setCenter(GeoPoint(it.latitude, it.longitude))
        }
    }
    // In map pixels rather than through a projection, which is rebuilt on a draw
    // and so answers for the camera as it was rather than as it has just been set.
    if (covered > 0) map.scrollBy(0, covered / 2)
    map.invalidate()
}

/**
 * One place, at one height, with the rest of it behind a chevron.
 *
 * Every card is [CARD_HEIGHT] tall whatever it holds, so the carousel's height
 * does not change as the selection moves and the document below the cell stays
 * where it is. What that height holds is the name, two lines of notes and the
 * footer; the factual fields are what [opened] draws.
 *
 * An open card is a taller card rather than a window over one. A window is
 * positioned once against what it was anchored on and does not follow a lazy
 * item the list then scrolls, and it takes every touch that lands on it, so the
 * list never sees the drag: the panel stays where it was opened and a swipe on
 * it does nothing. As an item it travels with the carousel and the carousel owns
 * its gestures, so a place can be read and then left behind in one gesture.
 *
 * Growing upward is what keeps the prose still: the row it grows inside sits on
 * the bottom of the map, so the height an open card takes is the map's and never
 * the document's. [PANEL_MAX] is the ceiling for the same reason, and a place
 * with more to say than that scrolls inside its own card.
 */
@Composable
private fun PlaceCard(
    cell: Cell,
    row: CellRow,
    point: CellPoint,
    opened: Boolean,
    onToggle: () -> Unit,
    openExternal: ((String) -> Unit)?,
) {
    Card(
        modifier = Modifier
            .padding(end = CARD_GAP)
            .width(CARD_WIDTH)
            .then(
                if (opened) Modifier.heightIn(max = PANEL_MAX)
                else Modifier.height(CARD_HEIGHT)
            )
            // The whole card opens and closes, the chevron being the sign of it
            // rather than the only way to work it. A mark, the handoff and the
            // chevron each take their own tap in the pass before this one, so
            // following a reference cannot also toggle the card.
            .clickable(onClick = onToggle),
        // Lifted while open, because it stands over the map and the pins rather
        // than beside its neighbours.
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (opened) 8.dp else 1.dp,
        ),
    ) {
        Column(
            modifier =
                if (opened) Modifier.nestedScroll(HoldVertical)
                    .verticalScroll(rememberScrollState())
                else Modifier,
        ) {
            CardBody(cell, row, point, opened, onToggle, openExternal)
        }
    }
}

/**
 * What a card says about its place.
 *
 * The name, the notes, and -- when [opened] -- every factual field the row
 * carries as two lines: the field and its value on the first, whatever vouches
 * for it on the second. A mark under the value rather than beside it, because
 * three things sharing a row leave the mark whatever width the value did not
 * take, and a mark measured that narrow is drawn one letter per line.
 *
 * Provenance is the whole point of the card: the reference app fills this space
 * with ratings and photos, and that enrichment is what makes an invented place
 * look real.
 */
@Composable
private fun CardBody(
    cell: Cell,
    row: CellRow,
    point: CellPoint,
    opened: Boolean,
    onToggle: () -> Unit,
    openExternal: ((String) -> Unit)?,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = textOf(row.fields["name"]),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleSmall,
            )
            Vouching(markFor(CellKind.Map, "name", row), openExternal)
        }
        val notes = textOf(row.fields["notes"])
        if (notes.isNotBlank()) {
            Text(
                text = notes,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                // The ellipsis is here rather than where the card runs out,
                // because a line limit is the only thing that draws one.
                maxLines = if (opened) Int.MAX_VALUE else COLLAPSED_NOTES,
                overflow = TextOverflow.Ellipsis,
            )
            // Kept on the collapsed card rather than going behind the chevron
            // with the fields: notes are prose an agent reasoned, and prose
            // whose mark is one tap away reads as prose somebody checked.
            Vouching(markFor(CellKind.Map, "notes", row), openExternal)
        }
        if (opened) {
            SCHEMAS.getValue(CellKind.Map).factual.forEach { field ->
                val value = row.fields[field] ?: return@forEach
                Column(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    }
                    // The blanket is drawn once in the footer, so a field it
                    // covers carries nothing of its own here.
                    Vouching(cardMark(CellKind.Map, field, row), openExternal)
                }
            }
        }
        // The collapsed card has a height to fill, so its footer is pushed to
        // the bottom of it. The open one is inside a scroll, where there is no
        // remaining space to claim a share of.
        if (!opened) Spacer(modifier = Modifier.weight(1f))
        Footer(cell, row, point, opened, onToggle, openExternal)
    }
}

/**
 * What answers for the place, and what can be done with it.
 *
 * The blanket source sits here rather than against each field it covers: a bare
 * `source` covers every factual field the row did not name, so drawing it per
 * field repeats one reference as many times as the row has fields.
 *
 * The handoff carries one place, which is the whole of what a document can offer
 * an external map: an intent that carries a pin set does not exist.
 */
@Composable
private fun Footer(
    cell: Cell,
    row: CellRow,
    point: CellPoint,
    opened: Boolean,
    onToggle: () -> Unit,
    openExternal: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leftmost, and away from the handoff: a chevron drawn against a button
        // reads as that button's own dropdown rather than as the card's control.
        // Drawn on every card rather than only where something is hidden --
        // knowing that a clipped region overflowed means reporting a measurement
        // back out of the layout, and seeing a place whole is worth offering on
        // a card that happens to fit.
        Icon(
            imageVector =
                if (opened) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
            contentDescription = if (opened) "Close the place" else "Open the place in full",
            modifier = Modifier
                .heightIn(min = TOUCH)
                .clickable(onClick = onToggle)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(end = 6.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Vouching(row.blanket, openExternal)
        Spacer(modifier = Modifier.weight(1f))
        if (openExternal != null) {
            Action("Open in maps") {
                openExternal(geoUri(point, cell.text("title").orEmpty()))
            }
        }
    }
}

/**
 * What vouches for one value on a card, in words rather than a glyph.
 *
 * A card has the room a table cell does not, and the three marks say very
 * different things: an agent's inference and the operator's own say-so carry
 * different weight and a reader has to tell them apart without being told how.
 * A reference is followed rather than read, so it is the one that is tapped.
 *
 * A line of text rather than a button: a `TextButton` stands taller than [TOUCH]
 * to say one word and indents its label away from the text it qualifies, which
 * on a card carrying a mark per field is most of the card's height.
 */
@Composable
private fun Vouching(provenance: Provenance?, openExternal: ((String) -> Unit)?) {
    val label = when (provenance) {
        null -> return
        is Provenance.Source -> "source ↗"
        Provenance.Reasoned -> "reasoned by the agent"
        Provenance.Operator -> "you said this"
    }
    val reference = (provenance as? Provenance.Source)?.reference?.takeIf { openExternal != null }
    Text(
        text = label,
        // A followed mark gets a touch target; the other two are read, so they
        // are the height of their own line and nothing more.
        modifier =
            if (reference == null) Modifier
            else Modifier
                .heightIn(min = TOUCH)
                .clickable { openExternal?.invoke(reference) }
                .wrapContentHeight(Alignment.CenterVertically),
        style = MaterialTheme.typography.labelSmall,
        color =
            if (reference == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
    )
}

/**
 * Keeps a vertical drag inside the open card it started in.
 *
 * An inner scroll hands what it cannot use to the scroll outside it, which is
 * the right answer almost everywhere and the wrong one here: a card's ceiling is
 * the map's height, so its range is often a few pixels, and passing on the rest
 * scrolls the document out from under the place being read. A drag that began on
 * an open card is the card's for as long as it lasts, whether the card has
 * anywhere to go or not.
 */
private val HoldVertical = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}

/** One thing a card can do, as a line of text with a touch target under it. */
@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .heightIn(min = TOUCH)
            .clickable(onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * About a quarter of the panel, which is the proportion `spec/038` sets: enough
 * to read a pin cluster, and the rest of the screen left for the prose and the
 * cells around it.
 */
private val MAP_HEIGHT = 275.dp

/** Wide enough for a name and a line of notes, narrow enough that the next card shows. */
private val CARD_WIDTH = 260.dp

/**
 * One height for every card, so the carousel's is fixed.
 *
 * What it holds, against Material's default type: `12dp` of card padding, a
 * `20dp` name, two `16dp` lines of notes, a `16dp` mark under them and a `28dp`
 * footer -- a card with slack rather than one that clips its own footer when a
 * name runs long. A field is two more lines and fits in neither, which is what
 * the chevron is for.
 */
private val CARD_HEIGHT = 130.dp

/** Enough to show that the next card is there, and no more. */
private val CARD_GAP = 8.dp

/** What the carousel keeps between itself and the map it stands on. */
private val STRIP_PADDING = 4.dp

/**
 * How tall an open card may grow: the map's height less what the carousel keeps
 * around itself, so the row holding it is never taller than the map behind it.
 *
 * A place with more to say than this scrolls inside its own card. Letting it grow
 * instead makes the cell taller, and every block below the cell moves down --
 * which is what drawing over the map is for.
 */
private val PANEL_MAX = MAP_HEIGHT - STRIP_PADDING * 2

/**
 * The worst case rather than the usual one: a notes mark that is a followed
 * reference stands taller than one that is a word, and the limit has to hold for
 * the taller.
 */
private const val COLLAPSED_NOTES = 2

/** A target for a finger on something the size of a word. */
private val TOUCH = 28.dp

/** Room around the outermost pins, so a pin is never drawn on the frame's edge. */
private const val PIN_PADDING = 48

/** What a single pin is drawn at when the cell names no zoom: a street, not a country. */
private const val DEFAULT_ZOOM = 14.0

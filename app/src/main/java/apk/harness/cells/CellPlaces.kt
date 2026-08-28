package apk.harness.cells

import java.net.URLEncoder
import kotlin.math.abs

/** A pin: one row's coordinates, in the order `at` spells them. */
data class CellPoint(val latitude: Double, val longitude: Double)

/**
 * A box that holds every pin, as the four edges a map view is told to fit.
 *
 * Edges rather than a centre and a span, because that is what fitting asks: the
 * camera has to contain all four and the zoom falls out of the view's own size.
 */
data class CellBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/**
 * Where one row is, or null when it does not say.
 *
 * The checker refuses a row whose `at` is missing, is not a pair of numbers, or
 * is off the globe, but rendering is best effort and a cell reaching the
 * renderer is not a promise it was checked. A row with nothing usable here is
 * left off the map rather than drawn at the equator or thrown over.
 */
fun pointOf(row: CellRow): CellPoint? {
    val pair = (row.fields["at"] as? CellValue.Series)?.values ?: return null
    if (pair.size != 2) return null
    val latitude = (pair[0] as? CellValue.Number)?.value ?: return null
    val longitude = (pair[1] as? CellValue.Number)?.value ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return CellPoint(latitude, longitude)
}

/**
 * The box that fits [points], or null when there is nothing to fit them to.
 *
 * Null covers the two ways a box has no area: no pins at all, and every pin on
 * one spot -- one place, or several rows naming the same address. A map view
 * asked to fit a point rather than a region answers with its deepest zoom, which
 * is a street corner filling the screen, so the caller centres on the pin and
 * picks a zoom instead. A box flat in one direction only is still a box: a row
 * of pins along a street fits by its length.
 */
fun boundsOf(points: List<CellPoint>): CellBounds? {
    if (points.distinct().size < 2) return null
    return CellBounds(
        south = points.minOf { it.latitude },
        west = points.minOf { it.longitude },
        north = points.maxOf { it.latitude },
        east = points.maxOf { it.longitude },
    )
}

/**
 * The card the carousel is showing: the one nearest the edge it snaps to.
 *
 * [cards] is what the list can see, each with its offset from the snap edge, so
 * the answer follows the finger through a drag rather than waiting for it to
 * settle. Derived on every read rather than stored beside the list, because a
 * selection kept in two places is a selection that can disagree with itself.
 */
fun snappedIndex(cards: List<CardSpan>): Int? = cards.minByOrNull { abs(it.offset) }?.index

/** One visible card: which row it draws, and how far it sits from the snap edge. */
data class CardSpan(val index: Int, val offset: Int)

/**
 * One place handed to whatever map app the phone has.
 *
 * `geo:` carries a single pin and no more, so this is the whole of the handoff a
 * document can offer. The coordinates are repeated in the query because the bare
 * `geo:lat,lon` form centres a map without dropping anything on it, and a pin
 * with no label is a place the person has to recognise from its surroundings.
 *
 * [label] is encoded: an ampersand in a name would otherwise end the query and
 * hand off a truncated place.
 */
fun geoUri(point: CellPoint, label: String): String {
    val at = "${point.latitude},${point.longitude}"
    if (label.isBlank()) return "geo:$at?q=$at"
    // URLEncoder spells a space the way a form does; a URI wants the percent form.
    val name = URLEncoder.encode(label, "UTF-8").replace("+", "%20")
    return "geo:$at?q=$at($name)"
}

package apk.harness.ui

import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Wide enough for a thumb to find. The terminal gives up this much width, so
// it is the smallest strip a drag can start in reliably. Tunable.
private val StripWidth = 24.dp

// Travel inward that counts as a pull rather than a stray touch. Tunable.
private val PullThreshold = 20.dp

// The bottom band's height. More than the side strips have width, because the
// band carries a filename and is the only thing that says a document is parked
// rather than gone. Tunable.
private val BandHeight = 34.dp

// The chevron drawn at the band's start. Sized rather than stretched: the band
// has room for a vector at its own proportions. Tunable.
private val BandChevron = 20.dp

// The band's inward corners. Tunable.
private val BandCornerRadius = 6.dp

// The handle painted inside the band. Narrower than the band, so the thumb has
// more to aim at than the eye needs. Tunable.
private val HandleWidth = 9.6.dp

// How much of the terminal's height the handle spans, centred in it. Tunable.
private const val HandleHeightFraction = 0.5f

// Enough fill to find the handle without losing the terminal underneath it.
// Tunable.
private const val HandleAlpha = 0.4f

// How much of the handle's height the chevron is stretched to. Wide enough to
// read only because the handle is narrow. Tunable.
private const val ChevronHeightFraction = 0.125f

/**
 * The strip along one edge of the terminal that a panel is pulled in from.
 *
 * It overlays the terminal: declared after it in their parent, the strip
 * claims a drag that starts inside it before the surface underneath ever sees
 * it. Its fill is translucent, so the terminal shows through, and the straight
 * edge is the one against the screen's.
 *
 * [side] decides which edge it sits against, which way a pull has to travel,
 * and which way the chevron points. There is one of these per side, and they
 * share the view's gesture exclusion.
 *
 * [DrawerSide.Bottom] is the band a parked document comes back from. It spans
 * the width and names what it holds instead of painting a handle: a strip
 * 9.6dp wide has nowhere to say which document is parked, and a band that does
 * not say it is indistinguishable from one over any other document.
 *
 * The band answers a tap as well as a pull, and the side strips answer only a
 * pull. A side strip is a handle at the screen's edge where a stray touch would
 * open a drawer nobody asked for; the band is the full width, 34dp tall, and
 * carries a filename, so it reads as a button and a tap on it that did nothing
 * would land in the terminal underneath.
 */
@Composable
fun DrawerEdgeStrip(
    side: DrawerSide,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the band names, drawn only on the bottom. A side strip carries no text. */
    label: String? = null,
) {
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { PullThreshold.toPx() }
    // Read only inside the drag callbacks, so accumulating it recomposes
    // nothing.
    val travel = remember { mutableFloatStateOf(0f) }
    // This strip's own rectangle as it was last registered, which is the only
    // one it may take back out of the view's list.
    val registered = remember { mutableStateOf<Rect?>(null) }
    val band = side == DrawerSide.Bottom

    Box(
        modifier = modifier
            .then(
                if (band) Modifier
                    .fillMaxWidth()
                    .height(BandHeight)
                    .clickable(onClick = onOpen)
                else Modifier.width(StripWidth).fillMaxHeight()
            )
            .onGloballyPositioned { coordinates ->
                // Asks the system to leave this rectangle to the app rather
                // than taking an inward swipe as the back gesture. From API 29;
                // below it the device navigates with three buttons, which
                // leaves the edges to the app already.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bounds = coordinates.boundsInRoot()
                    val rect = Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    // The write replaces the whole list, and there is a strip
                    // on three edges, so this one takes out only what it put in
                    // -- the rectangle it registered last -- and leaves the
                    // others alone. Taking out everything that *overlaps* its
                    // own will not do: the band spans the full width and meets
                    // both side strips at the bottom corners, so it would carry
                    // their rectangles out with its own and leave both edges to
                    // the system's back gesture until their next layout.
                    //
                    // Layout runs on every frame of the keyboard's animation
                    // with the same rectangle throughout, so a rectangle that
                    // has not moved and is already in the list is not written
                    // again. The membership test is against the list the view
                    // holds, so a rectangle dropped from it by a write this
                    // strip did not make comes back.
                    val current = view.systemGestureExclusionRects
                    val mine = registered.value
                    if (rect != mine || rect !in current) {
                        view.systemGestureExclusionRects =
                            current.filterNot { it == mine || it == rect } + rect
                        registered.value = rect
                    }
                }
            }
            .draggable(
                // The band is pulled up out of the bottom edge; a side strip is
                // pulled in across the screen.
                orientation = if (band) Orientation.Vertical else Orientation.Horizontal,
                state = rememberDraggableState { delta -> travel.floatValue += delta },
                onDragStarted = { travel.floatValue = 0f },
                onDragStopped = {
                    // Inward is away from the edge the panel comes in from:
                    // negative on the right, positive on the left, and negative
                    // again at the bottom, where inward is up.
                    val pulled = when (side) {
                        DrawerSide.Right, DrawerSide.Bottom -> travel.floatValue <= -threshold
                        DrawerSide.Left -> travel.floatValue >= threshold
                    }
                    if (pulled) onOpen()
                    travel.floatValue = 0f
                },
            ),
        contentAlignment =
            if (side == DrawerSide.Right) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (band) Band(label) else Handle(side)
    }
}

/**
 * The band's face: a chevron pointing the way it is pulled, and the document it
 * brings back.
 *
 * Filled across its whole width rather than behind a handle, because the label
 * has to be readable over whatever the terminal draws underneath it.
 */
@Composable
private fun Band(label: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // Square against the screen's edge and rounded on the inward one,
            // the way a handle is.
            .clip(
                RoundedCornerShape(
                    topStart = BandCornerRadius,
                    topEnd = BandCornerRadius,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )
            )
            .background(MaterialTheme.colorScheme.primary.copy(alpha = HandleAlpha))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "Bring the document back",
            colorFilter = ColorFilter.tint(Color.Black),
            modifier = Modifier.size(BandChevron),
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
                maxLines = 1,
                // The filename is at the start, so what a long label loses at
                // the end is the range rather than the name.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * A side strip's face: a translucent handle narrower than the strip, with the
 * chevron stretched to fill it.
 */
@Composable
private fun Handle(side: DrawerSide) {
    val rounded = HandleWidth / 2
    Box(
        modifier = Modifier
            .width(HandleWidth)
            .fillMaxHeight(HandleHeightFraction)
            // Round the inward corners and leave the ones against the
            // screen's edge square.
            .clip(
                if (side == DrawerSide.Right) {
                    RoundedCornerShape(
                        topStart = rounded,
                        bottomStart = rounded,
                        topEnd = 0.dp,
                        bottomEnd = 0.dp,
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 0.dp,
                        bottomStart = 0.dp,
                        topEnd = rounded,
                        bottomEnd = rounded,
                    )
                }
            )
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = HandleAlpha),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector =
                if (side == DrawerSide.Right) Icons.Default.ChevronLeft
                else Icons.Default.ChevronRight,
            contentDescription =
                if (side == DrawerSide.Right) "Open the file drawer"
                else "Open the chat drawer",
            // The vector is stretched rather than fitted: the handle gives it
            // height to be read by and no width to be read by.
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(Color.Black),
            modifier = Modifier
                .width(HandleWidth)
                .fillMaxHeight(ChevronHeightFraction),
        )
    }
}

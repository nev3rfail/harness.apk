package apk.harness.ui

import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp

// Wide enough for a thumb to find. The terminal gives up this much width, so
// it is the smallest strip a drag can start in reliably. Tunable.
private val StripWidth = 24.dp

// Travel inward that counts as a pull rather than a stray touch. Tunable.
private val PullThreshold = 20.dp

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
 * The band along one edge of the terminal that opens the drawer on that side.
 *
 * It overlays the terminal: declared after it in their parent, the strip
 * claims a drag that starts within its width before the surface underneath
 * ever sees it. Only the handle inside the band is painted, and its fill is
 * translucent, so the terminal shows through. The straight edge is the one
 * against the screen's.
 *
 * [side] decides which edge it sits against, which way a pull has to travel,
 * and which way the chevron points. There is one of these per side, and they
 * share the view's gesture exclusion.
 */
@Composable
fun DrawerEdgeStrip(side: DrawerSide, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { PullThreshold.toPx() }
    // Read only inside the drag callbacks, so accumulating it recomposes
    // nothing.
    val travel = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .width(StripWidth)
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                // Asks the system to leave this rectangle to the app rather
                // than taking an inward swipe as the back gesture. From API 29;
                // below it the device navigates with three buttons, which
                // leaves both edges to the app already.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bounds = coordinates.boundsInRoot()
                    val rect = Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    // The write replaces the whole list, and there is a strip on
                    // each edge, so this side takes out only what it put in --
                    // anything overlapping its own rectangle -- and leaves the
                    // other side's alone. Two strips at opposite edges never
                    // overlap, which is what makes that test enough.
                    //
                    // The comparison is against the list the view holds rather
                    // than against composition state, so there is no second copy
                    // to drift from what is registered. Layout runs on every
                    // frame of the keyboard's animation with the same rectangle
                    // throughout, so an unchanged list is not rewritten.
                    val registered = view.systemGestureExclusionRects
                    val others = registered.filterNot { Rect.intersects(it, rect) }
                    if (registered.size != others.size + 1 || rect !in registered) {
                        view.systemGestureExclusionRects = others + rect
                    }
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> travel.floatValue += delta },
                onDragStarted = { travel.floatValue = 0f },
                onDragStopped = {
                    // Inward is away from the edge the drawer comes in from:
                    // negative on the right, positive on the left.
                    val pulled = when (side) {
                        DrawerSide.Right -> travel.floatValue <= -threshold
                        DrawerSide.Left -> travel.floatValue >= threshold
                    }
                    if (pulled) onOpen()
                    travel.floatValue = 0f
                },
            ),
        contentAlignment =
            if (side == DrawerSide.Right) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
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
                // The vector is stretched rather than fitted: the handle gives
                // it height to be read by and no width to be read by.
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(Color.Black),
                modifier = Modifier
                    .width(HandleWidth)
                    .fillMaxHeight(ChevronHeightFraction),
            )
        }
    }
}

package apk.harness.ui

import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

// Wide enough for a thumb to find. The terminal gives up this much width, so
// it is the smallest strip a drag can start in reliably. Tunable.
private val StripWidth = 24.dp

// The handle: visible enough to be looked for, quiet enough not to be content.
private val HandleWidth = 3.dp
private val HandleMargin = 8.dp
private const val HandleAlpha = 0.30f

// Leftward travel that counts as a pull rather than a stray touch. Tunable.
private val PullThreshold = 20.dp

/**
 * The strip along the right edge that opens the file drawer.
 *
 * It occupies a column of its own beside the terminal rather than a band over
 * it: the terminal's surface is composited above this window, so a handle
 * inside its bounds would be behind it, and a strip overlapping it would take
 * every touch that began in its width from the terminal's own gestures.
 *
 * An edge gesture nobody has been told about cannot be found by trying it, so
 * the strip carries a visible handle whether or not the drawer is open.
 */
@Composable
fun DrawerEdgeStrip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
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
                    view.systemGestureExclusionRects = listOf(
                        Rect(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        ),
                    )
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> travel.floatValue += delta },
                onDragStarted = { travel.floatValue = 0f },
                onDragStopped = {
                    // Leftward is negative: the drawer comes in from the right.
                    if (travel.floatValue <= -threshold) onOpen()
                    travel.floatValue = 0f
                },
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .width(HandleWidth)
                .fillMaxHeight()
                .padding(vertical = HandleMargin)
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = HandleAlpha),
                    shape = RoundedCornerShape(HandleWidth / 2),
                ),
        )
    }
}

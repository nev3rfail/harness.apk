package apk.harness.ui

import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

// Wide enough for a thumb to find. The terminal gives up this much width, so
// it is the smallest strip a drag can start in reliably. Tunable.
private val StripWidth = 24.dp

// Leftward travel that counts as a pull rather than a stray touch. Tunable.
private val PullThreshold = 20.dp

/**
 * The band along the terminal's right edge that opens the file drawer.
 *
 * It overlays the terminal rather than drawing anything: the terminal's
 * surface is composited above this window, so nothing drawn here would be
 * visible over it. Declared after the terminal in its parent, the strip
 * claims a drag that starts within its width before the surface underneath
 * ever sees it.
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
                    val rect = Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    // Layout runs on every frame of the keyboard's
                    // animation and the rectangle is the same one throughout,
                    // so it goes to the system only once it has moved. The
                    // comparison is against what the view holds rather than a
                    // copy kept here, so a registration the framework lets go
                    // of is posted again.
                    if (rect != view.systemGestureExclusionRects.firstOrNull()) {
                        view.systemGestureExclusionRects = listOf(rect)
                    }
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
    )
}

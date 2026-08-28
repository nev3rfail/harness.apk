package apk.harness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Most of the width, so the terminal is not the thing being read, and enough
// remainder to be an obvious way out.
private const val DrawerWidthFraction = 0.86f

// Long enough that the slide reads as a movement with a direction, short
// enough that opening the drawer is not something to wait through.
private const val SlideMillis = 300

private const val ScrimAlpha = 0.35f

/**
 * Which edge a drawer is anchored to, and therefore which way it arrives.
 *
 * [Bottom] is an edge a [DrawerEdgeStrip] sits on and no drawer is anchored to:
 * the band a parked document comes back from is a strip, and what it opens is
 * the document panel's own dialog. [SideDrawer] reads any side other than
 * [Right] as [Left], so [Bottom] passed to it would arrive from the left rather
 * than crash a composition.
 */
enum class DrawerSide { Left, Right, Bottom }

/**
 * A panel presented as a drawer against one edge.
 *
 * Structurally a [Dialog], as every panel here is: the terminal's surface is
 * composited above its own window, so anything drawn in that window sits under
 * the terminal. A dialog gets a window of its own.
 *
 * The dialog outlives the request to close it by the length of the animation,
 * so the drawer is seen leaving. [onClosed] fires when the panel and the scrim
 * have both finished and the caller can drop this from the composition; the
 * [content] receives the same close, so the scrim, the back action and the
 * content's own way out all play it.
 *
 * [side] decides the edge it sits against and the direction it slides from.
 * Nothing else differs between the two: the file tree on the right and the
 * chat list on the left are one drawer shown from two sides.
 */
@Composable
fun SideDrawer(
    side: DrawerSide,
    onClosed: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var closing by remember { mutableStateOf(false) }

    // Starts shut and is told to open on the first composition, which is what
    // makes the arrival a slide rather than an appearance.
    val slide = remember { MutableTransitionState(false) }
    slide.targetState = !closing

    // One transition drives the panel and the scrim, so the two begin on the
    // same frame and land on the same one. The scrim fades from an explicit
    // zero: an overlay that arrives at full strength reads as the drawer
    // appearing, whatever the panel beside it is doing.
    val transition = rememberTransition(slide)
    val scrimAlpha by transition.animateFloat(
        transitionSpec = { tween(SlideMillis) },
    ) { open -> if (open) ScrimAlpha else 0f }

    // Idle is the transition's, so it covers the scrim's fade as well as the
    // panel's slide: the dialog stays until both have finished.
    LaunchedEffect(closing, slide.currentState, slide.isIdle) {
        if (closing && slide.isIdle && !slide.currentState) onClosed()
    }

    Dialog(
        onDismissRequest = { closing = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // The drawer is meant to run the display's full height with its
            // scrim over the system bars, so a tap beside the status bar is a
            // way out like any other. Keeping the first row clear of the bar is
            // then the content's own job and FileTree does it; insetting here
            // as well would leave the drawer a status bar short at the top.
            // Whether the dialog's window is itself inset is the platform's
            // answer on the running device rather than this flag's.
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // A sibling behind the drawer rather than a parent around it: a
            // clickable ancestor answers for a tap its descendant did not
            // consume, and the gaps between tree rows are exactly that tap.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The alpha is read in the draw lambda rather than in a
                    // background colour, so a frame of the fade redraws the
                    // scrim rather than recomposing the drawer.
                    .drawBehind { drawRect(Color.Black, alpha = scrimAlpha) }
                    .clickable(
                        // No ripple: the scrim is a way out, not a control.
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { closing = true },
            )
            // Off-screen is past the far edge, so the offset a slide starts and
            // ends at is the panel's width on the right and its negation on the
            // left. Right against everything else rather than a case per side:
            // a drawer is anchored to a side, and the third side of
            // [DrawerSide] is not one a drawer is anchored to.
            val offScreen: (Int) -> Int =
                if (side == DrawerSide.Right) { width -> width } else { width -> -width }

            transition.AnimatedVisibility(
                visible = { open -> open },
                modifier = Modifier.align(
                    if (side == DrawerSide.Right) Alignment.CenterEnd else Alignment.CenterStart
                ),
                enter = slideInHorizontally(animationSpec = tween(SlideMillis), initialOffsetX = offScreen),
                exit = slideOutHorizontally(animationSpec = tween(SlideMillis), targetOffsetX = offScreen),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(DrawerWidthFraction)
                        .fillMaxHeight()
                        // The panel stays composed and hit-testable for the
                        // whole of its exit. A row tapped in that window still
                        // opens a file, and opening one abandons the surface
                        // before it -- which answers a diff the agent is
                        // waiting on as rejected. From the moment the drawer is
                        // asked to leave, its taps stop here, on the initial
                        // pass, before any row sees them.
                        .pointerInput(closing) {
                            if (!closing) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.forEach { change -> change.consume() }
                                }
                            }
                        },
                ) {
                    content { closing = true }
                }
            }
        }
    }
}

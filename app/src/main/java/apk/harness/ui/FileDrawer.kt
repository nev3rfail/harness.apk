package apk.harness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Most of the width, so the terminal is not the thing being read, and enough
// remainder to be an obvious way out.
private const val DrawerWidthFraction = 0.86f

// Long enough that the slide is sampled at several positions even when the
// terminal is holding the frame rate down, short enough not to be waited on.
private const val SlideMillis = 400

private const val ScrimAlpha = 0.35f

/**
 * A panel presented as a drawer against the right edge.
 *
 * Structurally a [Dialog], as every panel here is: the terminal's surface is
 * composited above its own window, so anything drawn in that window sits under
 * the terminal. A dialog gets a window of its own.
 *
 * The dialog outlives the request to close it by the length of the animation,
 * so the drawer is seen leaving. [onClosed] fires when the slide has finished
 * and the caller can drop this from the composition; the [content] receives the
 * same close, so the scrim, the back action and the content's own way out all
 * play it.
 */
@Composable
fun FileDrawer(
    onClosed: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var closing by remember { mutableStateOf(false) }

    // Starts shut and is told to open on the first composition, which is what
    // makes the arrival a slide rather than an appearance.
    val slide = remember { MutableTransitionState(false) }
    slide.targetState = !closing

    // The scrim fades on the panel's own tween, from an explicit zero: an
    // overlay that arrives at full strength reads as the drawer appearing,
    // whatever the panel beside it is doing.
    val scrim = remember { Animatable(0f) }
    LaunchedEffect(slide.targetState) {
        scrim.animateTo(if (slide.targetState) ScrimAlpha else 0f, tween(SlideMillis))
    }

    LaunchedEffect(closing, slide.currentState, slide.isIdle) {
        if (closing && slide.isIdle && !slide.currentState) onClosed()
    }

    Dialog(
        onDismissRequest = { closing = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // The window reaches the system bars, so the drawer is full height
            // and the scrim covers them: a tap beside the status bar is a way
            // out like any other. Clearing the bar is then the content's own
            // job, and FileTree does it -- insetting the window as well would
            // leave the drawer a status bar short at the top.
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
                    .drawBehind { drawRect(Color.Black, alpha = scrim.value) }
                    .clickable(
                        // No ripple: the scrim is a way out, not a control.
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { closing = true },
            )
            AnimatedVisibility(
                visibleState = slide,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(animationSpec = tween(SlideMillis)) { width -> width },
                exit = slideOutHorizontally(animationSpec = tween(SlideMillis)) { width -> width },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(DrawerWidthFraction)
                        .fillMaxHeight(),
                ) {
                    content { closing = true }
                }
            }
        }
    }
}

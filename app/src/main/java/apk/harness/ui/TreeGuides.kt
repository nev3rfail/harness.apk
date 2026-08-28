package apk.harness.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// One level of indentation. The smallest step that still keeps two adjacent
// guide lines apart, so a row's own guide reads as belonging to its icon
// rather than to the level beside it.
val IndentStep = 12.dp

// The horizontal run from a row's guide to its icon, and the hair of space
// after it, so the guide and the icon read as one unit.
private val GuideStub = 5.dp
private val GuideGap = 2.dp

// The space between a row's icon and the text after it. Both trees space their
// rows by it, so an icon sits the same distance from its name in either.
val IconGap = 6.dp

// What a row's icon occupies, given to the rows that carry one and reserved by
// the rows that do not. Text then starts at the same offset for a given depth
// whatever the row is, so a chat sits to the right of the project above it
// rather than under its icon.
val IconSlot = 16.dp

// The elbow's run for a row that draws no icon: across the icon's slot as well,
// so the line meets the text, which is the only thing such a row draws.
val ElbowToText = GuideStub + GuideGap + IconSlot + IconGap

// Structure rather than content: the outline colour, well under full strength.
const val GuideAlpha = 0.35f

/**
 * The left padding a row of this depth needs to clear its own guide: half a
 * step per level to reach the line, then the stub that runs out to the icon and
 * the space after it.
 *
 * Every tree that draws guides pads from here, so two drawers side by side
 * indent by the same step and neither can drift from the other.
 */
fun guideIndent(depth: Int): Dp = IndentStep * (depth + 0.5f) + GuideStub + GuideGap

/**
 * The guide lines behind one row of a tree, in [colour].
 *
 * Applied before the row's own padding, so the lines are painted across the
 * indentation that padding creates rather than inside the content it holds.
 *
 * [ancestorsContinue] is one entry per level above the row, outermost first,
 * saying whether that ancestor has a sibling below it; [isLastSibling] says the
 * same of the row itself, and [hasChildren] says the row is drawn directly
 * above the rows inside it.
 *
 * Four kinds of line come out of that: a full-height line for each ancestor
 * still to be continued, the row's own line down to its middle where the elbow
 * runs out to the right, that line carried on to the bottom when a sibling
 * follows, and the head of the child column, drawn from the row's middle to its
 * bottom at the child level's offset. The last is what joins an open row to the
 * first row inside it.
 *
 * [elbow] is how far that run goes. A row that draws an icon asks for the stub
 * that reaches it; a row that draws none asks for [ElbowToText], so the line
 * ends where the row's text begins rather than in the space an icon would fill.
 */
fun Modifier.treeGuides(
    depth: Int,
    ancestorsContinue: List<Boolean>,
    isLastSibling: Boolean,
    hasChildren: Boolean,
    colour: Color,
    elbow: Dp = GuideStub,
): Modifier = drawBehind {
    val step = IndentStep.toPx()
    val middle = size.height / 2f
    // A level above this row draws a full-height line when its directory has a
    // sibling still to come, and nothing when it does not -- that blank space
    // is what ends a subtree.
    ancestorsContinue.forEachIndexed { level, continues ->
        if (continues) {
            val x = (level + 0.5f) * step
            drawLine(
                color = colour,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = Stroke.HairlineWidth,
            )
        }
    }
    // The row's own level: down to the middle always, on to the bottom only
    // when a sibling follows. Stopping at the middle is what draws the elbow
    // under the last child.
    val own = (depth + 0.5f) * step
    drawLine(
        color = colour,
        start = Offset(own, 0f),
        end = Offset(own, if (isLastSibling) middle else size.height),
        strokeWidth = Stroke.HairlineWidth,
    )
    drawLine(
        color = colour,
        start = Offset(own, middle),
        end = Offset(own + elbow.toPx(), middle),
        strokeWidth = Stroke.HairlineWidth,
    )
    // The child column, begun on the row it descends from: from this row's
    // middle, level with the elbow, to the top edge of the first child.
    if (hasChildren) {
        val child = (depth + 1.5f) * step
        drawLine(
            color = colour,
            start = Offset(child, middle),
            end = Offset(child, size.height),
            strokeWidth = Stroke.HairlineWidth,
        )
    }
}

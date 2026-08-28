package apk.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import apk.harness.cells.promoteCells
import apk.harness.cells.rowsById
import apk.harness.cells.rowsFor
import apk.harness.ide.Unit
import apk.harness.ide.bodyLinesOf
import apk.harness.ide.fenceUnit
import apk.harness.ide.unitOf
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Markdown the agent asked to have rendered: headings, emphasis, lists, quotes,
 * links and code from the renderer, and tables drawn here because it has none.
 *
 * [selected] is in the document's own source lines, and every block whose lines
 * it covers draws as selected. A fence is the exception: it is handed the body
 * lines instead and draws them itself, because a source file is one fence and
 * marking the whole of it says nothing.
 *
 * [onPoint] is told which unit was touched and whether the touch was a long
 * press, and nothing else. What that does to the selection depends on where the
 * anchor is, and the anchor belongs to whoever outlives this composition. A
 * long press is always reported; a tap only while [selected] holds something,
 * because that is the only state a tap has a meaning in, and a document whose
 * links stopped opening would be a poor price for one that has none.
 *
 * [openExternal] is what a cell's source mark hands its reference to. It comes
 * from here rather than from the cell because a document is the smallest thing
 * that can be shown without a way out of the app.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    selected: IntRange? = null,
    onPoint: ((Unit, anchoring: Boolean) -> kotlin.Unit)? = null,
    openExternal: ((String) -> kotlin.Unit)? = null,
) {
    val blocks = remember(content) { promoteCells(markdownBlocks(content)).blocks }
    // A view names a cell elsewhere in the document, so the whole block list is
    // what resolves it. Built once here and handed to each widget, rather than
    // each widget searching the document it sits in.
    val rows = remember(blocks) {
        rowsById(blocks.mapNotNull { (it.block as? MarkdownBlock.Widget)?.cell })
    }
    // A gesture outlives the composition that started it, so the handler is
    // taken as it is when the touch lands rather than as it was then. Without
    // this a tap would extend from the selection two taps ago.
    val point by rememberUpdatedState(onPoint)
    // Whether a tap means anything, read the same way and for a second reason:
    // a pointer input node whose keys are unchanged is reused as it stands,
    // handler and all, so a modifier that swapped one gesture block for another
    // on the same key would leave the block installed first running for good.
    // One block that reads this is the only shape that answers a selection
    // arriving and going away.
    val holding by rememberUpdatedState(selected != null)
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = SELECTION_ALPHA)
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        h2 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        h3 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodyMedium,
        paragraph = MaterialTheme.typography.bodyMedium,
        ordered = MaterialTheme.typography.bodyMedium,
        bullet = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium,
        quote = MaterialTheme.typography.bodyMedium,
        code = MaterialTheme.typography.bodySmall,
    )

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, spanned ->
            val unit = unitOf(index, spanned)
            val covered = selected != null &&
                spanned.lines.first >= selected.first &&
                spanned.lines.last <= selected.last
            // Every block that answers whole is pointed at and drawn the same
            // way, so the modifier is built once here and handed to whichever
            // renderer draws the block. A fence is the one that does not take it.
            val whole = Modifier
                .fillMaxWidth()
                .then(if (covered) Modifier.background(highlight) else Modifier)
                .then(
                    if (onPoint == null) Modifier
                    else Modifier.pointAhead(unit, { holding }) { held ->
                        point?.invoke(unit, held)
                    }
                )

            when (val block = spanned.block) {
                is MarkdownBlock.Prose -> Markdown(
                    content = block.text,
                    typography = typography,
                    modifier = whole,
                )
                // Markdown's own table carries no provenance, so it is the same
                // grid a cell draws with nothing to put beside its values.
                is MarkdownBlock.Table -> Grid(
                    header = block.header,
                    rows = block.rows,
                    marks = emptyList(),
                    modifier = whole,
                )
                // A fence answers per line rather than whole, so it takes the
                // touches itself and is told the selection in its own numbering.
                is MarkdownBlock.Code -> CodeFence(
                    language = block.language,
                    code = block.text,
                    selected = selected?.let { bodyLinesOf(spanned, it) },
                    onPointLine = onPoint?.let { report ->
                        { line, anchoring -> report(fenceUnit(index, spanned, line), anchoring) }
                    },
                )
                // A widget is a block, so its renderer takes `whole` and answers
                // a long press and a tap the way a paragraph does.
                is MarkdownBlock.Widget -> CellBody(
                    cell = block.cell,
                    rows = rowsFor(block.cell, rows),
                    modifier = whole,
                    openExternal = openExternal,
                )
            }
        }
    }
}

/**
 * Reports a press on this block ahead of anything drawn inside it.
 *
 * A block's handler sits outside the text it renders, so on the main pass it
 * hears about a tap only after a link inside has taken it. This one listens on
 * the initial pass, which reaches the outside first, and consumes the touch it
 * acts on so the link never sees it.
 *
 * A long press always anchors. A tap is acted on only while [taps] answers
 * true, which is while a selection is held: `spec/040` gives a tap no meaning
 * with nothing held, so the release is left as it arrived and whatever is under
 * it -- a link -- has it. The release of a long press is consumed either way,
 * because holding a link is not clicking it.
 *
 * Nothing is consumed until the touch has settled into a tap or a hold: a drag
 * starting here is the panel scrolling, and taking it would pin the document
 * under a finger. Watching a pass does not take a change from it, so on the
 * gestures this declines the pointer stream is the one the renderer would have
 * seen had nothing been attached here at all.
 *
 * [taps] is asked rather than told, because this block outlives the composition
 * that installed it and a new one is not put in its place.
 *
 * [press] is told whether the touch was held long enough to be a long press,
 * which is the same pair of answers a tap detector gives.
 */
private fun Modifier.pointAhead(
    key: Any,
    taps: () -> Boolean,
    press: (held: Boolean) -> kotlin.Unit,
) = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var change = down
            while (change.pressed) {
                change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull false
                }
            }
            if (!taps()) return@withTimeoutOrNull false
            change.consume()
            true
        }
        when {
            // Still down when the press timed out. What is left of the
            // gesture is consumed as it arrives, or the release lands on the
            // link as a click of its own.
            tapped == null -> {
                press(true)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }

            tapped -> press(false)
        }
    }
}

/**
 * How much of the theme's primary a selected block is drawn in.
 *
 * Enough to see which lines are held and not enough to stop them being read.
 * Tunable.
 */
internal const val SELECTION_ALPHA = 0.18f

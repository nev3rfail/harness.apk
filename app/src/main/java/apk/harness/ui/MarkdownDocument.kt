package apk.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import apk.harness.cells.promoteCells
import apk.harness.cells.rowsById
import apk.harness.cells.rowsFor
import apk.harness.ide.Unit
import apk.harness.ide.bodyLinesOf
import apk.harness.ide.fenceUnit
import apk.harness.ide.itemUnit
import apk.harness.ide.unitOf
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

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
 * A list is the second block to answer below its own level. Each of its items is
 * drawn in a box of its own and reports where it was laid out, so a touch
 * resolves to the deepest item that contains it and each item the selection
 * covers whole draws its own background.
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
    // A newline inside a paragraph separates two words rather than two lines.
    val lineBreaks = lineBreakAnnotator()
    // Where each item and each block was drawn. Plain maps rather than state:
    // only a touch reads them, and a bound that moved changes nothing that is
    // drawn until the next touch asks. The map instance outlives every
    // recomposition, so a pointer handler installed once still reads the latest
    // bounds through it.
    val itemBounds = remember(content) { mutableMapOf<Int, Rect>() }
    val blockTops = remember(content) { mutableMapOf<Int, Float>() }
    // One index per block, so an item is found by the line it begins on rather
    // than by walking the tree on every touch.
    val byLine = remember(blocks) { blocks.map { itemsByLine(it.items) } }
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        h2 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        h3 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodyMedium,
        paragraph = MaterialTheme.typography.bodyMedium
            .copy(textIndent = TextIndent(firstLine = PARAGRAPH_INDENT)),
        ordered = MaterialTheme.typography.bodyMedium,
        bullet = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium,
        quote = MaterialTheme.typography.bodyMedium,
        code = MaterialTheme.typography.bodySmall,
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
        blocks.forEachIndexed { index, spanned ->
            val unit = unitOf(index, spanned)
            val covered = spanned.lines.inside(selected)
            // Every block that answers whole is pointed at and drawn the same
            // way, so the modifier is built once here and handed to whichever
            // renderer draws the block. A fence is the one that does not take it.
            val whole = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { blockTops[index] = it.boundsInRoot().top }
                .then(if (covered) Modifier.background(highlight) else Modifier)
                .then(
                    if (onPoint == null) Modifier
                    else Modifier.pointAhead(unit, { holding }) { held, at ->
                        // The touch arrives in the block's own coordinates and
                        // the bounds are recorded in the root's, so the two meet
                        // with one addition rather than a guess about which
                        // space a nested item reports in.
                        val y = (blockTops[index] ?: 0f) + at.y
                        val item = itemAt(spanned.items, itemBounds, y)
                        point?.invoke(if (item == null) unit else itemUnit(index, item), held)
                    }
                )

            when (val block = spanned.block) {
                is MarkdownBlock.Prose -> Markdown(
                    content = block.text,
                    typography = typography,
                    annotator = lineBreaks,
                    // The components close over the selection, and the block is
                    // redrawn when the selection changes anyway, so they are
                    // built here rather than remembered against it.
                    components = listComponents(
                        firstLine = spanned.lines.first,
                        itemFor = { line -> byLine[index][line] },
                        selected = selected,
                        blockCovered = covered,
                        highlight = highlight,
                        record = { line, bounds -> itemBounds[line] = bounds },
                    ),
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
 * Whether [selected] covers the whole of this range.
 *
 * The same test at both levels: a block draws its own background when the
 * selection covers all of it, and an item draws its own when the selection
 * covers all of the item. A range covered only in part is not drawn, because
 * marking it would claim more than is held.
 */
private fun IntRange.inside(selected: IntRange?): Boolean =
    selected != null && first >= selected.first && last <= selected.last

/**
 * The renderer's two list components, replaced so each item is a box the app can
 * find and paint.
 *
 * The block is still one `Markdown` call over one parse of one list, so the
 * numbering is the renderer's and cannot drift. What is taken over is the walk:
 * the library's own list draws a nested list beside the item that holds it and
 * through its own components rather than these, so an item inside one would have
 * neither bounds nor a background. Iterating here puts a nested list inside its
 * parent's box, which is what makes a parent's bounds contain its children's and
 * a touch resolve to the deepest of them.
 *
 * The marker comes from the handler the renderer takes it from, over the index
 * of the item among its siblings, and the item's own content is drawn by the
 * renderer's element dispatch. Both of the things that are easy to get wrong
 * stay behind the library's own calls.
 *
 * [firstLine] is the document line the block begins on, so an item's offset into
 * the block's text becomes the line its [Item] is keyed by. [itemFor] answers
 * with the item that begins on a line, [selected] is the selection in document
 * lines, and [record] is told where each item was laid out.
 */
@Composable
private fun listComponents(
    firstLine: Int,
    itemFor: (Int) -> Item?,
    selected: IntRange?,
    blockCovered: Boolean,
    highlight: Color,
    record: (Int, Rect) -> kotlin.Unit,
): MarkdownComponents = markdownComponents(
    orderedList = { model ->
        ListItems(model, true, firstLine, itemFor, selected, blockCovered, highlight, record)
    },
    unorderedList = { model ->
        ListItems(model, false, firstLine, itemFor, selected, blockCovered, highlight, record)
    },
)

/**
 * One level of a list: a marker beside each item's content, with the item in a
 * box of its own.
 *
 * The box is what carries an item's bounds and its background. A list nested
 * under an item is drawn by the element dispatch that draws the rest of the
 * item's content, so it comes back through here and lands inside its parent's
 * box: a parent contains its children on screen the way its lines contain
 * theirs in the source.
 *
 * An item draws its own background only while the block does not: a block the
 * selection covers whole is already drawn, and painting each item again inside
 * it would say the same thing twice at twice the strength.
 */
@Composable
private fun ColumnScope.ListItems(
    model: MarkdownComponentModel,
    ordered: Boolean,
    firstLine: Int,
    itemFor: (Int) -> Item?,
    selected: IntRange?,
    blockCovered: Boolean,
    highlight: Color,
    record: (Int, Rect) -> kotlin.Unit,
) {
    val handler = if (ordered) LocalOrderedListHandler.current else LocalBulletListHandler.current
    val marker = if (ordered) MarkdownTokenTypes.LIST_NUMBER else MarkdownTokenTypes.LIST_BULLET
    val style = if (ordered) model.typography.ordered else model.typography.bullet
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    Column {
        var index = 0
        model.node.children.forEach { child ->
            if (child.type != MarkdownElementTypes.LIST_ITEM) return@forEach
            val at = index
            index++
            val line = firstLine + lineOfOffset(model.content, child.startOffset)
            val item = itemFor(line)
            val covered = !blockCovered && item != null && item.lines.inside(selected)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (covered) Modifier.background(highlight) else Modifier)
                    .onGloballyPositioned { record(line, it.boundsInRoot()) },
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = handler.transform(
                            marker,
                            child.findChildOfType(marker)?.getTextInNode(model.content) ?: "",
                            at,
                        ),
                        style = style,
                    )
                    Column(modifier = Modifier.padding(bottom = padding.listItemBottom)) {
                        // The item's content, and any list nested under it,
                        // drawn by the renderer's own dispatch. A nested list
                        // reaches these components again, so it is drawn here
                        // and inside this item's box.
                        //
                        // What an item says is a paragraph like any other, and
                        // the paragraph style sets its first line in. Here that
                        // is the wrong first line: the item is already set in by
                        // its marker, and setting it in again opens a gap
                        // between the two.
                        CompositionLocalProvider(
                            LocalMarkdownTypography provides flush(model.typography),
                        ) {
                            MarkdownItemBody(child, components, model.content)
                        }
                    }
                }
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
 *
 * [press] is also told where the touch landed, in this block's own coordinates,
 * because a block that answers below its own level has to know which part of
 * itself was pointed at.
 */
private fun Modifier.pointAhead(
    key: Any,
    taps: () -> Boolean,
    press: (held: Boolean, at: Offset) -> kotlin.Unit,
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
                press(true, down.position)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }

            tapped -> press(false, down.position)
        }
    }
}

/**
 * [typography] with the paragraph style's first line drawn where the rest are.
 *
 * The one style that differs, the rest handed on as they are, so a style added
 * to the renderer arrives here without this having to be told about it.
 */
private fun flush(typography: MarkdownTypography): MarkdownTypography =
    Flush(typography, typography.paragraph.copy(textIndent = TextIndent.None))

private class Flush(
    typography: MarkdownTypography,
    override val paragraph: TextStyle,
) : MarkdownTypography by typography

/**
 * How far the first line of a paragraph is set in from the lines under it.
 *
 * The paragraph style alone, which is what a block of prose is drawn in. A
 * quoted line is drawn in a style of its own, and an item's paragraph is drawn
 * flush, so neither is set in away from the marker that opens it.
 *
 * Tunable.
 */
private val PARAGRAPH_INDENT = 8.sp

/**
 * The gap between one block and the next.
 *
 * Every block is drawn on its own, so this is the whole of what sets a paragraph
 * apart from the one after it. Tunable.
 */
private val BLOCK_GAP = 4.dp

/**
 * How much of the theme's primary a selected block is drawn in.
 *
 * Enough to see which lines are held and not enough to stop them being read.
 * Tunable.
 */
internal const val SELECTION_ALPHA = 0.18f

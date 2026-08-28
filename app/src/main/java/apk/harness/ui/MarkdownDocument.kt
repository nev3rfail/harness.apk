package apk.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import apk.harness.cells.promoteCells
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
 * anchor is, and the anchor belongs to whoever outlives this composition.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    selected: IntRange? = null,
    onPoint: ((Unit, anchoring: Boolean) -> kotlin.Unit)? = null,
) {
    val blocks = remember(content) { promoteCells(markdownBlocks(content)).blocks }
    // A gesture outlives the composition that started it, so the handler is
    // taken as it is when the touch lands rather than as it was then. Without
    // this a tap would extend from the selection two taps ago.
    val point by rememberUpdatedState(onPoint)
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
                    else Modifier.pointerInput(unit) {
                        detectTapGestures(
                            onLongPress = { point?.invoke(unit, true) },
                            onTap = { point?.invoke(unit, false) },
                        )
                    }
                )

            when (val block = spanned.block) {
                is MarkdownBlock.Prose -> Markdown(
                    content = block.text,
                    typography = typography,
                    modifier = whole,
                )
                is MarkdownBlock.Table -> Table(block, whole)
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
                is MarkdownBlock.Widget -> {}
            }
        }
    }
}

@Composable
private fun Table(table: MarkdownBlock.Table, modifier: Modifier = Modifier) {
    // Each column is as wide as its widest cell, so columns line up and the whole
    // table scrolls sideways rather than wrapping into nonsense.
    val rows = listOf(table.header) + table.rows
    val columns = rows.maxOfOrNull { it.size } ?: return
    val widths = (0 until columns).map { column ->
        val longest = rows.maxOf { it.getOrNull(column)?.length ?: 0 }
        (longest.coerceIn(MIN_CHARS, MAX_CHARS) * CHAR_WIDTH + CELL_PADDING).dp
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        rows.forEachIndexed { index, cells ->
            val header = index == 0
            Row(
                modifier = Modifier.background(
                    if (header) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                ),
            ) {
                widths.forEachIndexed { column, width ->
                    Text(
                        text = cells.getOrNull(column).orEmpty(),
                        modifier = Modifier
                            .width(width)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            if (header || index < rows.lastIndex) HorizontalDivider()
        }
    }
}

// A table arrives measured in characters, which is what turns into a width here.
// The padding is part of the width, or the widest cell wraps.
private const val CHAR_WIDTH = 8
private const val CELL_PADDING = 20
private const val MIN_CHARS = 5
private const val MAX_CHARS = 26

/**
 * How much of the theme's primary a selected block is drawn in.
 *
 * Enough to see which lines are held and not enough to stop them being read.
 * Tunable.
 */
internal const val SELECTION_ALPHA = 0.18f

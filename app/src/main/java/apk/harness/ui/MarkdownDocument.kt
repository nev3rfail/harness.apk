package apk.harness.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import apk.harness.cells.promoteCells
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Markdown the agent asked to have rendered: headings, emphasis, lists, quotes,
 * links and code from the renderer, and tables drawn here because it has none.
 */
@Composable
fun MarkdownDocument(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { promoteCells(markdownBlocks(content)).blocks }
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
        blocks.forEach { spanned ->
            when (val block = spanned.block) {
                is MarkdownBlock.Prose -> Markdown(
                    content = block.text,
                    typography = typography,
                )
                is MarkdownBlock.Table -> Table(block)
                is MarkdownBlock.Code -> CodeFence(block.language, block.text)
                is MarkdownBlock.Widget -> Unit // task 6 draws this
            }
        }
    }
}

@Composable
private fun Table(table: MarkdownBlock.Table) {
    // Each column is as wide as its widest cell, so columns line up and the whole
    // table scrolls sideways rather than wrapping into nonsense.
    val rows = listOf(table.header) + table.rows
    val columns = rows.maxOfOrNull { it.size } ?: return
    val widths = (0 until columns).map { column ->
        val longest = rows.maxOf { it.getOrNull(column)?.length ?: 0 }
        (longest.coerceIn(MIN_CHARS, MAX_CHARS) * CHAR_WIDTH + CELL_PADDING).dp
    }

    Column(
        modifier = Modifier
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

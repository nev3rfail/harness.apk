package apk.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import apk.harness.cells.Cell
import apk.harness.cells.CellKind
import apk.harness.cells.CellRow
import apk.harness.cells.Provenance
import apk.harness.cells.gridOf
import apk.harness.cells.tabSeparated

/**
 * Draws a cell as whatever its kind is.
 *
 * [rows] is passed in rather than read from [cell], because a view's rows belong
 * to the cell it refers to and only the document holding both can resolve that.
 *
 * [modifier] is the one every block in a document is drawn with, so a cell is
 * selected and pointed at the way a paragraph is. [openExternal] is what a
 * source mark hands its reference to; without one a source is named but not
 * followed.
 */
@Composable
fun CellBody(
    cell: Cell,
    rows: List<CellRow>,
    modifier: Modifier = Modifier,
    openExternal: ((String) -> Unit)? = null,
) {
    when (cell.kind) {
        CellKind.Table -> CellTable(cell, rows, modifier, openExternal)
        CellKind.Map -> CellMapBody(cell, rows, modifier, openExternal)
    }
}

@Composable
private fun CellTable(
    cell: Cell,
    rows: List<CellRow>,
    modifier: Modifier,
    openExternal: ((String) -> Unit)?,
) {
    val grid = remember(cell, rows) { gridOf(cell, rows) }
    val clipboard = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = cell.text("title").orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            // Tab-separated, which is what a spreadsheet takes: a table read on a
            // phone is usually on its way somewhere it can be worked with.
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(tabSeparated(grid)))
            }) {
                Text("Copy")
            }
        }
        Grid(grid.header, grid.rows, grid.marks, openExternal = openExternal)
    }
}

/**
 * A table of text, with what vouches for each value beside it.
 *
 * One drawing for both tables a document can hold: the pipe table markdown
 * spells, which has no provenance and passes [marks] empty, and the one a cell
 * builds, which has one mark per value. [marks] is indexed by [rows], so a
 * shorter list simply leaves the rest of the grid unmarked.
 *
 * Each column is as wide as its widest cell, so columns line up and the whole
 * table scrolls sideways rather than wrapping into nonsense.
 */
@Composable
internal fun Grid(
    header: List<String>,
    rows: List<List<String>>,
    marks: List<List<Provenance?>>,
    modifier: Modifier = Modifier,
    openExternal: ((String) -> Unit)? = null,
) {
    val lines = listOf(header) + rows
    val columns = lines.maxOfOrNull { it.size } ?: return
    val widths = (0 until columns).map { column ->
        val longest = lines.indices.maxOf { line ->
            val text = lines[line].getOrNull(column)?.length ?: 0
            // A mark is drawn inside its own cell, so it is part of what the
            // column has to be wide enough for. The header carries none.
            val mark = if (line == 0) null else markLabel(marks.mark(line - 1, column))
            text + (mark?.let { it.length + 1 } ?: 0)
        }
        (longest.coerceIn(MIN_CHARS, MAX_CHARS) * CHAR_WIDTH + CELL_PADDING).dp
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        lines.forEachIndexed { line, cells ->
            val heading = line == 0
            Row(
                modifier = Modifier.background(
                    if (heading) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                ),
            ) {
                widths.forEachIndexed { column, width ->
                    Row(
                        modifier = Modifier
                            .width(width)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = cells.getOrNull(column).orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (!heading) Mark(marks.mark(line - 1, column), openExternal)
                    }
                }
            }
            if (heading || line < lines.lastIndex) HorizontalDivider()
        }
    }
}

private fun List<List<Provenance?>>.mark(row: Int, column: Int): Provenance? =
    getOrNull(row)?.getOrNull(column)

/**
 * The mark drawn beside a value, or null when nothing vouches for it.
 *
 * A source is a glyph, because a reference is a URL nobody reads off a table
 * cell and the thing to do with it is follow it. The other two are words: an
 * agent's inference and the operator's own say-so are claims of very different
 * weight, and a reader has to be able to tell them apart at a glance.
 */
private fun markLabel(provenance: Provenance?): String? = when (provenance) {
    null -> null
    is Provenance.Source -> "↗"
    Provenance.Reasoned -> "reasoned"
    Provenance.Operator -> "you said"
}

/**
 * What vouches for one value, drawn beside it.
 *
 * Provenance that renders invisibly is worth nothing, so it is drawn rather than
 * implied. A value with nothing behind it draws nothing: those cells are the
 * extras a cell carries for a referrer, and they make no claim to answer for.
 */
@Composable
private fun Mark(provenance: Provenance?, openExternal: ((String) -> Unit)?) {
    val label = markLabel(provenance) ?: return
    val follow = (provenance as? Provenance.Source)?.reference?.takeIf { openExternal != null }
    Text(
        text = label,
        modifier = Modifier
            .padding(start = 4.dp)
            .then(
                if (follow == null) Modifier
                else Modifier.clickable { openExternal?.invoke(follow) }
            ),
        style = MaterialTheme.typography.labelSmall,
        color =
            if (follow == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
    )
}

// A table arrives measured in characters, which is what turns into a width here.
// The padding is part of the width, or the widest cell wraps.
private const val CHAR_WIDTH = 8
private const val CELL_PADDING = 20
private const val MIN_CHARS = 5
private const val MAX_CHARS = 26

package apk.harness.cells

/**
 * A cell's rows laid out as text, and what vouches for each value.
 *
 * [rows] and [marks] are the same shape: one entry per column of one row, in the
 * order [header] names. They are separate lists rather than a pair per value
 * because a renderer draws the two differently -- the value is the cell, the
 * mark is what sits beside it -- and because a table block that has no
 * provenance at all supplies an empty [marks] and nothing else.
 */
data class CellGrid(
    val header: List<String>,
    val rows: List<List<String>>,
    val marks: List<List<Provenance?>>,
)

/**
 * A value as the text a person reads.
 *
 * A number carries no count of its own, so it arrives as a double and a whole
 * one drops the `.0` an integer never had. A flag reads as the answer to the
 * question its field asks rather than as `true`. An absent field is an empty
 * cell: a row that does not carry a column says nothing about it, which is
 * different from saying it is blank only in a way no table can draw.
 */
fun textOf(value: CellValue?): String = when (value) {
    null -> ""
    is CellValue.Text -> value.value
    is CellValue.Number -> value.value.toString().removeSuffix(".0")
    is CellValue.Flag -> if (value.value) "yes" else "no"
    is CellValue.Series -> value.values.joinToString(", ") { textOf(it) }
}

/**
 * [rows] as the grid [cell] asks for.
 *
 * `columns` decides both the order the fields are drawn in and which of them are
 * drawn at all, so a cell referring to a dataset shows the part of it that
 * answers the question it was written for. `sort` orders the rows by one
 * column's text, with rows missing that field last: a row with nothing to sort
 * on cannot be placed among the ones that have something, and putting it first
 * would hide the rows that do.
 *
 * The rows are passed in rather than read from [cell], because a view's rows
 * belong to the cell it refers to and only a whole document knows both.
 */
fun gridOf(cell: Cell, rows: List<CellRow>): CellGrid {
    val columns = cell.names("columns").orEmpty()
    val sorted = cell.text("sort")?.let { key ->
        rows.sortedWith(compareBy(nullsLast()) { row -> row.fields[key]?.let(::textOf) })
    } ?: rows
    return CellGrid(
        header = columns,
        rows = sorted.map { row -> columns.map { textOf(row.fields[it]) } },
        marks = sorted.map { row -> columns.map { markFor(cell.kind, it, row) } },
    )
}

/**
 * A grid as tab-separated text, header first.
 *
 * What a spreadsheet takes from a clipboard, which is the one place a table read
 * on a phone is likely to go next. The marks are left out: they qualify a value
 * on screen and there is no column for them here.
 */
fun tabSeparated(grid: CellGrid): String =
    (listOf(grid.header) + grid.rows).joinToString("\n") { it.joinToString("\t") }

/**
 * What vouches for one field of one row, or nothing.
 *
 * A blanket `source` covers the kind's factual fields and stops there. Drawing
 * it beside an extra would put a claim on a value the schema says is nobody's,
 * and `notes` is presumed reasoned already, so it shows a mark only when the row
 * gave it one of its own.
 */
fun markFor(kind: CellKind, field: String, row: CellRow): Provenance? =
    if (field in SCHEMAS.getValue(kind).factual) row.provenanceOf(field)
    else row.provenance[field]

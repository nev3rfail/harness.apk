package apk.harness.cells

/** The lowest zoom a map cell may ask for. */
const val MIN_ZOOM = 2.0

/**
 * The highest zoom a map cell may ask for.
 *
 * Together with [MIN_ZOOM] this is the range the map view draws. A cell asking
 * outside it gets some other zoom than the one it named, silently, which is the
 * kind of quiet wrongness the checker exists to turn into a message.
 */
const val MAX_ZOOM = 21.0

/** A coordinate is refused whole: half a pair places nothing. */
private const val NOT_A_PAIR = "at is two numbers"

/** The shape of a column list, which is the only shape a table can draw. */
private const val COLUMN_SHAPE = "columns is a list of names"

/**
 * Every rule in `spec/038`, over a document's cells at once.
 *
 * Document-level because `from` resolves against every id in the file. A cell is
 * given the ids that carry rows, not the cells themselves, so a referrer cannot
 * reach into a referent for anything but its columns.
 *
 * Every rule appends and none returns early: a person fixing a cell reads the
 * whole list once rather than recompiling to find the next fault.
 *
 * Returns the problems with [cell]. Empty means it may be drawn.
 */
fun checkCell(cell: Cell, rowsById: Map<String, List<CellRow>>): List<CellProblem> {
    val problems = mutableListOf<CellProblem>()
    val schema = SCHEMAS.getValue(cell.kind)

    for (name in schema.required) {
        if (name !in cell.attributes) problems += CellProblem("missing: $name")
    }

    // Present but malformed fails here, because `Cell.names` is all-or-nothing:
    // `columns = [1, 2]` answers null, rule 6 then iterates nothing, and the
    // table promotes and draws no columns -- the same silent pass a non-string
    // `source` was.
    if ("columns" in schema.required && "columns" in cell.attributes &&
        cell.names("columns") == null
    ) {
        problems += CellProblem(COLUMN_SHAPE)
    }

    // A table with both reads one set of rows and draws another, and there is no
    // saying which the author meant. A table with neither draws nothing at all.
    if (cell.kind == CellKind.Table) {
        if (cell.from != null && cell.rows.isNotEmpty()) {
            problems += CellProblem("a table reads rows with from or carries them, not both")
        } else if (cell.from == null && cell.rows.isEmpty()) {
            problems += CellProblem("a table needs from or rows of its own")
        }
    }

    val zoom = cell.attributes["zoom"]
    if (zoom != null) {
        // A zoom that is not a number is as undrawable as one out of range, so
        // one message covers both and says what arrived.
        val level = (zoom as? CellValue.Number)?.value
        if (level == null || level !in MIN_ZOOM..MAX_ZOOM) {
            problems += CellProblem("zoom out of range: ${level ?: "not a number"}")
        }
    }

    if (cell.kind == CellKind.Map) {
        problems += cell.rows.withIndex().flatMap { (index, row) -> coordinateProblems(index, row) }
    }

    // The wording is the text `spec/038` quotes, so an operator reading the spec
    // and an operator reading the fence see the same sentence.
    val resolves = cell.from == null || cell.from in rowsById
    if (!resolves) problems += CellProblem("unknown id: ${cell.from}")

    // A cell whose `from` named nothing has no rows to check its columns
    // against, and calling every column unknown on top of that says the same
    // failure once per column. One failure, reported once.
    if (resolves) {
        val stored = rowsFor(cell, rowsById).flatMapTo(mutableSetOf()) { it.fields.keys }
        val named = cell.names("columns").orEmpty() + listOfNotNull(cell.text("sort"))
        for (name in named) {
            if (name !in stored) problems += CellProblem("unknown column: $name")
        }
    }

    // A factual field the row does not carry is nobody's claim, so only the ones
    // present need vouching for. Rows count from one, because a person counts
    // them off the fence body.
    for ((index, row) in cell.rows.withIndex()) {
        for (field in schema.factual) {
            if (field in row.fields && row.provenanceOf(field) == null) {
                problems += CellProblem("row ${index + 1}: $field carries no provenance")
            }
        }
    }

    return problems
}

/** The ids in a document that carry rows, which is what `from` may name. */
fun rowsById(cells: List<Cell>): Map<String, List<CellRow>> = buildMap {
    for (cell in cells) {
        val id = cell.id ?: continue
        // Rows are the whole of what an id offers, so a cell with none claims
        // nothing -- which is what makes a view unreferenceable without a rule
        // of its own. The first claim on an id keeps it.
        if (cell.rows.isEmpty() || id in this) continue
        put(id, cell.rows)
    }
}

/** The rows a cell draws: its own, or the ones it refers to. */
fun rowsFor(cell: Cell, rowsById: Map<String, List<CellRow>>): List<CellRow> =
    cell.rows.ifEmpty { cell.from?.let(rowsById::get).orEmpty() }

/**
 * Where row [index] of a map cell says it is.
 *
 * A pin needs a latitude and a longitude and nothing else will do, so anything
 * that is not two numbers is one refusal rather than a description of how it
 * differs. A pair that is a pair is then checked against the globe: osmdroid
 * accepts 91 degrees north and draws it somewhere that does not exist.
 *
 * Every message names its row: a twenty-row map with two bad coordinates
 * otherwise reports the same sentence twice and names neither.
 */
private fun coordinateProblems(index: Int, row: CellRow): List<CellProblem> {
    val where = "row $index: "
    val pair = (row.fields["at"] as? CellValue.Series)?.values
        ?.map { (it as? CellValue.Number)?.value ?: return listOf(CellProblem(where + NOT_A_PAIR)) }
    if (pair == null || pair.size != 2) return listOf(CellProblem(where + NOT_A_PAIR))

    val problems = mutableListOf<CellProblem>()
    if (pair[0] !in -90.0..90.0) {
        problems += CellProblem("${where}latitude out of range: ${pair[0]}")
    }
    if (pair[1] !in -180.0..180.0) {
        problems += CellProblem("${where}longitude out of range: ${pair[1]}")
    }
    return problems
}

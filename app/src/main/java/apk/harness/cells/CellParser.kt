package apk.harness.cells

import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.TableType
import com.akuleshov7.ktoml.tree.nodes.TomlArrayOfTablesElement
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValueArray
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValuePrimitive
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import com.akuleshov7.ktoml.tree.nodes.TomlTable
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlValue

/** The one section a cell body may open, and the key that vouches for a field. */
private const val ROWS = "rows"
private const val SOURCE = "source"

private const val NESTED = "a cell holds attributes and rows, nothing deeper"
private const val DOTTED = "write a row's source inside the row: source = { at = \"...\" }"
private const val SOURCE_SHAPE =
    "a source is a URL or a result id, or \"reasoned\", or \"operator\""

/** The position a parser failure carries, which it carries only as text. */
private val LINE_PREFIX = Regex("""^Line (\d+):""")

/**
 * One fence body to one cell.
 *
 * The AST rather than a decoder: a row carries whatever fields its author put
 * there, including ones this kind never draws, and a `data class` cannot hold
 * what it does not declare.
 *
 * A failure carries the position the parser reported, because that text is the
 * only diagnostic the operator gets.
 */
fun parseCell(kind: CellKind, body: String): Result<Cell> = try {
    Result.success(cellOf(kind, TomlParser(TomlInputConfig()).parseString(body).children))
} catch (refusal: CellProblemException) {
    Result.failure(refusal)
} catch (thrown: Exception) {
    // Every exception, not only the parser's own hierarchy: a malformed body
    // reaches library code that throws for its own reasons, and a cell that
    // cannot be read is a code block either way.
    Result.failure(CellProblemException(problemOf(thrown)))
}

/**
 * The file's own children, read once.
 *
 * A key/value pair at this level is an attribute, the `rows` array of tables is
 * the rows, and anything else is refused: a section under another name means
 * nothing here, and a table means somebody nested where nesting has no reading.
 */
private fun cellOf(kind: CellKind, nodes: List<TomlNode>): Cell {
    val attributes = mutableMapOf<String, CellValue>()
    var rows = emptyList<CellRow>()

    for (node in nodes) {
        val pair = keyValueOf(node)
        when {
            pair != null -> attributes += pair
            node is TomlTable && node.type == TableType.ARRAY ->
                if (node.name == ROWS) rows = rowsOf(node)
                else refuse("unknown section: ${node.name}", node.lineNo)
            // A file-level table named `source` is the dotted spelling, which the
            // parser hoists out of the row that wrote it and merges across every
            // row in the body. `isSynthetic` separates that from a hand-written
            // `[source]` table, and both land here because a cell has a reading
            // for neither -- so the name alone decides the message, and the
            // message names the spelling that works.
            node is TomlTable -> refuse(if (node.name == SOURCE) DOTTED else NESTED, node.lineNo)
            // Whatever else the parser leaves behind -- the stub node it puts in
            // an empty file -- says nothing about the cell.
        }
    }

    // `id` and `from` are what one cell says about another, so they are lifted
    // out of the attributes and nothing reads them twice.
    val id = (attributes.remove("id") as? CellValue.Text)?.value
    val from = (attributes.remove("from") as? CellValue.Text)?.value
    return Cell(kind, id, from, attributes.toMap(), rows)
}

/**
 * The rows under the `rows` array of tables.
 *
 * An element's own name is the parser's literal `technical_node`, so an element
 * is recognised by being one rather than by what it is called. Any other child
 * of the section is a table somebody opened underneath it, which nests deeper
 * than a cell reads.
 */
private fun rowsOf(section: TomlTable): List<CellRow> = section.children.map { node ->
    if (node !is TomlArrayOfTablesElement) refuse(NESTED, node.lineNo)
    rowOf(node)
}

/**
 * One row: its fields, and what vouches for them.
 *
 * `source` arrives as one of two node types -- a bare `source = "reasoned"` as a
 * key/value pair covering the whole row, or `source = { at = "..." }` as a table
 * naming fields one at a time. The parser leaves no inline-table node, so that
 * table is identical in shape to a `[rows.source]` header and one reader draws
 * both spellings.
 */
private fun rowOf(element: TomlArrayOfTablesElement): CellRow {
    val fields = mutableMapOf<String, CellValue>()
    val provenance = mutableMapOf<String, Provenance>()
    var blanket: Provenance? = null

    for (node in element.children) {
        val pair = keyValueOf(node)
        when {
            pair != null && pair.first == SOURCE ->
                blanket = provenanceOf(pair.second, node.lineNo)
            pair != null -> fields += pair
            node is TomlTable && node.name == SOURCE ->
                for (child in node.children) {
                    val (field, mark) = keyValueOf(child) ?: continue
                    provenance[field] = provenanceOf(mark, child.lineNo)
                }
            node is TomlTable -> refuse(NESTED, node.lineNo)
        }
    }

    return CellRow(fields.toMap(), provenance.toMap(), blanket)
}

/**
 * A key/value node as its name and its value, or null when it is neither.
 *
 * The interface both key/value classes implement is `internal` to the parser, so
 * the two concrete types are the only handle on `key` and `value` from here.
 */
private fun keyValueOf(node: TomlNode): Pair<String, CellValue>? = when (node) {
    is TomlKeyValuePrimitive -> node.key.last() to valueOf(node.value)
    is TomlKeyValueArray -> node.key.last() to valueOf(node.value)
    else -> null
}

/**
 * A parsed value in the shapes a cell reads.
 *
 * Switched on the content rather than on the node class, which covers every
 * width of integer the parser distinguishes and this does not. An array's
 * content is a list of values rather than of the numbers it printed, so each
 * element is unwrapped in turn.
 */
private fun valueOf(value: TomlValue): CellValue = when (val content = value.content) {
    is Number -> CellValue.Number(content.toDouble())
    is Boolean -> CellValue.Flag(content)
    is List<*> -> CellValue.Series(content.map { valueOf(it as TomlValue) })
    else -> CellValue.Text(content.toString())
}

/**
 * What a `source` entry says: one of the two marks, or a reference to follow.
 *
 * Anything that is not a string is refused rather than read as an empty
 * reference: an empty reference draws as no link while the check still sees
 * provenance present, so the field would claim a source it does not have.
 */
private fun provenanceOf(value: CellValue, lineNo: Int): Provenance =
    when (val mark = (value as? CellValue.Text)?.value) {
        // A mark is a literal, so the comparison is exact.
        "reasoned" -> Provenance.Reasoned
        "operator" -> Provenance.Operator
        null -> refuse(SOURCE_SHAPE, lineNo)
        else -> Provenance.Source(mark)
    }

/**
 * Refuses a body, at the line the offending node sits on.
 *
 * The parser counts lines from one and [CellProblem] counts from zero, matching
 * the fence body it was cut from.
 */
private fun refuse(reason: String, lineNo: Int): Nothing =
    throw CellProblemException(CellProblem(reason, lineNo - 1))

/**
 * A thrown parser failure as a problem.
 *
 * The parser reports its position inside the message text and nowhere else, so
 * the line is read back out of the words rather than off a field.
 */
private fun problemOf(thrown: Throwable): CellProblem {
    val reason = thrown.message ?: thrown.toString()
    val lineNo = LINE_PREFIX.find(reason)?.groupValues?.get(1)?.toIntOrNull()
    return CellProblem(reason, lineNo?.minus(1))
}

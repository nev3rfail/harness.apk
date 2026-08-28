package apk.harness.cells

/**
 * The kinds of cell a document may carry, and the fence label each answers to.
 *
 * The label is the enum's own data rather than a table beside it, so a new kind
 * cannot be added without saying what fence introduces it.
 */
enum class CellKind(val label: String) { Map("harness-map"), Table("harness-table") }

/**
 * A value out of a cell body, in the shapes TOML can spell.
 *
 * Flat on purpose: a cell holds attributes and rows and nothing deeper, so there
 * is no variant for a nested table. A [Series] holds values rather than strings
 * because a coordinate pair is two numbers and a column list is two strings, and
 * both arrive the same way.
 */
sealed interface CellValue {
    /** A string, already unescaped by the parser. */
    data class Text(val value: String) : CellValue

    /** An integer or a float, both as a double -- nothing here counts. */
    data class Number(val value: Double) : CellValue

    /** A boolean. */
    data class Flag(val value: Boolean) : CellValue

    /** An array, its elements read the same way as any other value. */
    data class Series(val values: List<CellValue>) : CellValue
}

/**
 * Where a field came from: a URL or search-result id, or the agent's own
 * inference, or the person in the conversation.
 */
sealed interface Provenance {
    /** A reference somebody can follow to check the claim. */
    data class Source(val reference: String) : Provenance

    /** The agent worked it out. */
    data object Reasoned : Provenance

    /** The person in the conversation said it. */
    data object Operator : Provenance
}

/**
 * One record in a cell.
 *
 * [fields] is every key the row carried, whether or not this cell's kind draws
 * it: a map cell renders `name` and `at` and stores `who` and `years` too,
 * because a table referring to it needs them.
 *
 * [provenance] is per field. [blanket] is a bare `source = ...`, which covers
 * every factual field the row does not name individually.
 */
data class CellRow(
    val fields: Map<String, CellValue>,
    val provenance: Map<String, Provenance> = emptyMap(),
    val blanket: Provenance? = null,
) {
    /** What vouches for [field], naming the field itself before the blanket. */
    fun provenanceOf(field: String): Provenance? = provenance[field] ?: blanket
}

/**
 * One fence body, read.
 *
 * [id] and [from] sit beside [attributes] rather than in it, because they are
 * what one cell says about another and every reader wants them by name. Every
 * other key stays in [attributes] under whatever the author called it.
 */
data class Cell(
    val kind: CellKind,
    val id: String? = null,
    val from: String? = null,
    val attributes: Map<String, CellValue> = emptyMap(),
    val rows: List<CellRow> = emptyList(),
) {
    /** [name] as a string, or null when it is absent or is something else. */
    fun text(name: String): String? = (attributes[name] as? CellValue.Text)?.value

    /** [name] as a number, or null when it is absent or is something else. */
    fun number(name: String): Double? = (attributes[name] as? CellValue.Number)?.value

    /**
     * [name] as a list of strings, or null when any element is not one.
     *
     * All or nothing: a column list with a number in it names no column, and
     * half of it is worse than none.
     */
    fun names(name: String): List<String>? = (attributes[name] as? CellValue.Series)
        ?.values?.map { (it as? CellValue.Text)?.value ?: return null }
}

/** What went wrong with one cell, in the words the operator sees. */
data class CellProblem(val reason: String, val line: Int? = null)

/**
 * A [CellProblem] as a throwable, which is what a failed `Result` carries.
 *
 * One type for every way reading a cell can fail, so a caller matches on this
 * rather than on the TOML parser's own hierarchy.
 */
class CellProblemException(val problem: CellProblem) : Exception(problem.reason)

/**
 * What a cell's kind requires, and which of its row fields are claims about the
 * world.
 *
 * Factual fields are the ones `spec/038` names: coordinates, addresses, opening
 * hours, times and prices. `notes` is narrative and is presumed reasoned. Every
 * other field is an extra a referrer may want and is nobody's claim.
 */
data class CellSchema(val required: Set<String>, val factual: Set<String>)

/** The schema of each kind, which is the whole of what a kind means here. */
val SCHEMAS: Map<CellKind, CellSchema> = mapOf(
    CellKind.Map to CellSchema(
        required = setOf("title"),
        factual = setOf("at", "address", "hours", "arrival_time", "price"),
    ),
    CellKind.Table to CellSchema(required = setOf("columns"), factual = emptySet()),
)

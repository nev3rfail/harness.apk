package apk.harness.cells

import apk.harness.ui.MarkdownBlock
import apk.harness.ui.Spanned

/**
 * A document's blocks with its cells promoted, and what did not promote.
 *
 * [problems] is what a tool result carries back to the agent: why a cell stayed
 * a code block, and its line in the document.
 */
data class Promotion(val blocks: List<Spanned>, val problems: List<CellProblem>)

/**
 * Every cell fence in [blocks], read and checked.
 *
 * A fence whose label names a kind and whose body parses and checks becomes a
 * [MarkdownBlock.Widget]; one that does not stays the code block it arrived as,
 * with the reason under its own source. Everything else is passed through
 * untouched.
 */
fun promoteCells(blocks: List<Spanned>): Promotion {
    // Parse first, so `from` resolves against every id in the document rather
    // than against the ones above it.
    val parsed = blocks.map { spanned ->
        val block = spanned.block
        val kind = (block as? MarkdownBlock.Code)?.language
            ?.let { label -> CellKind.entries.firstOrNull { it.label == label } }
        spanned to kind?.let { parseCell(it, block.text) }
    }
    val ids = rowsById(parsed.mapNotNull { it.second?.getOrNull() })
    val claimed = mutableSetOf<String>()

    val problems = mutableListOf<CellProblem>()
    val out = parsed.map { (spanned, result) ->
        if (result == null) return@map spanned
        val cell = result.getOrNull()
        val found =
            if (cell == null) listOf(result.problem)
            else checkCell(cell, ids) + duplicateProblems(cell, claimed)
        if (cell != null && found.isEmpty()) {
            Spanned(MarkdownBlock.Widget(cell), spanned.lines)
        } else {
            // A parser position is a line inside the body, and a body starts one
            // line after the fence's opening line. A checker problem has no line
            // of its own and lands on the fence.
            val placed = found.map { problem ->
                problem.copy(
                    line = problem.line?.let { spanned.lines.first + 1 + it }
                        ?: spanned.lines.first,
                )
            }
            problems += placed
            Spanned(degrade(spanned.block as MarkdownBlock.Code, placed), spanned.lines)
        }
    }
    return Promotion(out, problems)
}

/**
 * Whether [cell] is the second in the document to claim its id.
 *
 * Only a whole-document pass can tell, which is why this is not a checking rule:
 * [checkCell] is handed ids rather than cells and cannot tell a first claimant
 * from a second. [rowsById] is first-claim-wins, so a later claim leaves its own
 * rows unreachable through `from` while its id says otherwise -- which defeats
 * the reason ids exist, that two views of one dataset cannot disagree. Rows are
 * what a claim takes, so a cell with none takes nothing. [claimed] carries what
 * the cells above took, and the first claimant broke nothing and hears nothing.
 */
private fun duplicateProblems(cell: Cell, claimed: MutableSet<String>): List<CellProblem> {
    val id = cell.id?.takeIf { cell.rows.isNotEmpty() } ?: return emptyList()
    return if (claimed.add(id)) emptyList() else listOf(CellProblem("duplicate id: $id"))
}

/**
 * The problem a failed [parseCell] carries.
 *
 * Always a [CellProblemException] by that function's contract; anything else
 * still has to render, so its message stands in rather than throwing again from
 * inside a document.
 */
private val Result<Cell>.problem: CellProblem
    get() = when (val thrown = exceptionOrNull()) {
        is CellProblemException -> thrown.problem
        else -> CellProblem(thrown?.message ?: "unreadable cell")
    }

/**
 * A cell that did not check, as its own source with the reason under it.
 *
 * The reason goes in as a TOML comment, so the fallback colours as the format
 * the body is written in and reads as something a person put there.
 */
private fun degrade(code: MarkdownBlock.Code, problems: List<CellProblem>): MarkdownBlock.Code {
    val notes = problems.joinToString("\n") { problem ->
        "# " + problem.reason + (problem.line?.let { " (line ${it + 1})" } ?: "")
    }
    return code.copy(text = code.text.trimEnd('\n') + "\n\n" + notes)
}

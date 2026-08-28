package apk.harness.ide

import apk.harness.ui.Spanned

/**
 * A range of source lines in a document.
 *
 * Zero-based and inclusive, because that is what goes on the wire. The selected
 * text is sliced at send time and never stored, so it cannot drift from the
 * lines it claims to be.
 */
data class Selection(val path: String, val first: Int, val last: Int)

/**
 * A thing in a document a person can point at.
 *
 * [block] is its index in the document's blocks and [line] is a line inside a
 * fence, or null for the block taken whole. Two units are the same unit when
 * both agree, which is what makes tapping the selected one clear it.
 */
data class Unit(val block: Int, val line: Int?, val lines: IntRange)

/** The unit for a block taken whole. */
fun unitOf(index: Int, spanned: Spanned): Unit = Unit(index, null, spanned.lines)

/**
 * The unit for one line inside a fence.
 *
 * A fence's span covers its opening and closing lines, so its body starts one
 * line in. [bodyLine] is the line within the body, which is what a text layout
 * reports.
 */
fun fenceUnit(index: Int, fence: Spanned, bodyLine: Int): Unit {
    val line = fence.lines.first + 1 + bodyLine
    return Unit(index, bodyLine, line..line)
}

/** The lines from an anchor to a target, whichever way round they are. */
fun spanOf(anchor: Unit, target: Unit): IntRange =
    minOf(anchor.lines.first, target.lines.first)..maxOf(anchor.lines.last, target.lines.last)

/** The text of [lines] in [source], for the `text` field on the wire. */
fun sliceOf(source: String, lines: IntRange): String {
    // A closing newline ends the last line rather than opening another one, so
    // the empty string `lines` leaves after it is not a line of the document and
    // is not a line anything can select.
    val all = source.lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    // A range beyond the document is coerced rather than refused: a selection
    // outlives an edit to the file under it.
    val first = lines.first.coerceIn(all.indices)
    val last = lines.last.coerceIn(first, all.lastIndex)
    return all.subList(first, last + 1).joinToString("\n")
}

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

/**
 * The selection as a person reads it, `L113-119`.
 *
 * One is added here, and nowhere else on the way to a screen: [Selection] is
 * zero-based because that is what goes on the wire. A single line drops the
 * range, because `L113-113` says the same thing twice.
 */
fun Selection.label(): String {
    val first = this.first + 1
    val last = this.last + 1
    return if (first == last) "L$first" else "L$first-$last"
}

/**
 * What pointing at [target] leaves selected, or null for nothing.
 *
 * The rule for one point, in the one place both halves of it can be seen. A long
 * press ([anchoring]) takes the unit alone and is where the anchor is dropped. A
 * tap extends from the anchor to the unit, and a tap on a unit the selection
 * already covers drops the selection -- which is why this is decided here and
 * not in a block: a block knows it was pointed at and nothing else.
 *
 * [anchor] is null after a restore, which brings a selection back without the
 * run of pointing that made it. The selection's own first line stands in, so the
 * first tap extends from where the selection starts.
 */
fun pointAt(
    path: String,
    anchor: Unit?,
    selection: Selection?,
    target: Unit,
    anchoring: Boolean,
): Selection? {
    val alone = Selection(path, target.lines.first, target.lines.last)
    if (anchoring) return alone
    if (selection != null && target.lines.first >= selection.first &&
        target.lines.last <= selection.last
    ) {
        return null
    }
    val from = anchor?.lines ?: selection?.let { it.first..it.first } ?: return alone
    val span = minOf(from.first, target.lines.first)..maxOf(from.last, target.lines.last)
    return Selection(path, span.first, span.last)
}

/**
 * The lines of [fence]'s body that [lines] covers, or null when it covers none.
 *
 * A fence is drawn from its body alone, so a selection measured in document
 * lines has to be moved into that body's own numbering before the fence can draw
 * it. Doing it here is what keeps a fence from learning about document
 * positions. The body starts one line after the opening fence, so the last line
 * of a closed fence's span is its closing marker and lands past the body; the
 * fence clamps to the lines it actually laid out.
 */
fun bodyLinesOf(fence: Spanned, lines: IntRange): IntRange? {
    val body = fence.lines.first + 1
    val from = maxOf(lines.first, body)
    val to = minOf(lines.last, fence.lines.last)
    if (from > to) return null
    return (from - body)..(to - body)
}

/**
 * What to send about a document, or null to send nothing.
 *
 * The decision apart from the socket that carries it: [IdeServer] speaks to
 * `android.util.Log` and cannot be run off a device, and this is the part with
 * cases in it. [lines] and [text] are absent together -- a document open with
 * nothing highlighted -- or present together, so what is sent never names a
 * range without saying what is on it.
 */
data class Report(val path: String, val lines: IntRange?, val text: String?)

/**
 * What the agent owning [document] is told about it, or null when there is
 * nothing to tell.
 *
 * The notification mirrors the state, so this is a function of the state and
 * nothing else: a document with no selection is its path, a selection is its
 * path with its lines and their text, and no document is nothing at all. The
 * text is sliced here rather than held with the selection, so it cannot drift
 * from the lines it claims to be.
 *
 * Whether the document is on screen or behind a band is not asked, which is what
 * leaves minimising and restoring silent: neither changes what the app believes
 * about the document, so neither changes what this answers.
 *
 * [selection] is the one held in [document]. Nothing holds a selection into a
 * document that is not on screen, so the two agree by construction.
 */
fun reportFor(document: Surface.Document?, selection: Selection?): Report? {
    if (document == null) return null
    if (selection == null) return Report(document.path, null, null)
    val lines = selection.first..selection.last
    return Report(document.path, lines, sliceOf(document.markdown, lines))
}

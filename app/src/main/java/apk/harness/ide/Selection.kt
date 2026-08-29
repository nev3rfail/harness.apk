package apk.harness.ide

import apk.harness.ui.Item
import apk.harness.ui.Spanned
import org.json.JSONObject

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
 * block that answers per line -- a fence's body line, or a list item's own first
 * line -- or null for the block taken whole. Two units are the same unit when
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

/**
 * The unit for one item of a list, and everything nested under it.
 *
 * The item's own first line names it, so two touches anywhere on one item give
 * the same unit. Its lines are the subtree's, so a selection made from it takes
 * everything under it.
 */
fun itemUnit(index: Int, item: Item): Unit = Unit(index, item.lines.first, item.lines)

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
 * The selection as a person reads it, `L113-119`, or null when the document
 * holds no line of the file.
 *
 * [lineOffset] is the document's own, and it is asked for rather than defaulted
 * so that no call site can quietly draw the document's numbering instead of the
 * file's: a fenced source sits one line in, and a band reading `L2` for file
 * line 1 disagrees with what the agent was told. The band is the only place the
 * operator can read that back, so it may not disagree.
 *
 * One is added here, and nowhere else on the way to a screen: [Selection] is
 * zero-based because that is what goes on the wire. A single line drops the
 * range, because `L113-113` says the same thing twice.
 *
 * A null offset is a document that describes a file rather than showing it. Its
 * lines are not the file's, so there is no range to draw.
 */
fun Selection.label(lineOffset: Int?): String? {
    if (lineOffset == null) return null
    val first = this.first + lineOffset + 1
    val last = this.last + lineOffset + 1
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
 * What [key]'s agent should be told, given the whole of what the app believes,
 * or null when there is nothing to tell.
 *
 * The notification mirrors the state, so this is a function of the state and
 * nothing else. A document is [key]'s when it is on screen and [owner] is
 * [key], or when it is the entry [key] has parked. Both answer the same thing,
 * which is what leaves minimising and restoring silent.
 *
 * A closing entry is the exception: the document is closed and the app has
 * dropped the range, so it reports the path alone until the entry itself goes.
 *
 * A document nobody owns reaches no chat: no chat holds [NO_CHAT], so a surface
 * the app put up itself is announced to nobody.
 *
 * The lines reported are the file's, shifted by the document's own offset, while
 * the text is sliced at the document's lines -- that is where the text is, and
 * for a fenced source those are the same characters either way. A document with
 * no offset holds no line of the file, so a selection in one reports the path
 * alone: the same thing it reports with nothing selected.
 */
fun reportFor(
    key: Long,
    visible: Surface?,
    owner: Long,
    selection: Selection?,
    parked: Surfaces.Parked?,
): Report? {
    val shown = (visible as? Surface.Document)?.takeIf { owner == key }
    if (shown != null) return reportOf(shown, selection)
    val put = parked ?: return null
    // A closing entry reports the path with no selection -- the same thing a
    // cleared selection reports, and for the same reason.
    return reportOf(put.document, if (put.closing) null else put.selection)
}

/** [document]'s path, with [selection]'s lines and their text when it has both. */
private fun reportOf(document: Surface.Document, selection: Selection?): Report {
    val offset = document.lineOffset
    if (selection == null || offset == null) return Report(document.path, null, null)
    val lines = selection.first..selection.last
    return Report(document.path, lines.shift(offset), sliceOf(document.markdown, lines))
}

private fun IntRange.shift(by: Int) = (first + by)..(last + by)

/**
 * The `selection_changed` params for a document with nothing highlighted.
 *
 * `filePath` is the only field the notification requires, but `selection` is
 * sent as an explicit null rather than left out: an absent key and a null one
 * are different messages, and this one is a statement about the selection. The
 * app has discarded it, and an `ide_selection` block still naming a range would
 * be a claim the app no longer believes.
 *
 * An empty range at line 0 would say the same thing in a form the CLI could not
 * tell from a real selection, so it is not sent -- that would be a claim about
 * the file.
 */
fun selectionParams(path: String): JSONObject =
    JSONObject().put("filePath", path).put("selection", JSONObject.NULL)

/**
 * The `selection_changed` params for the lines [first] through [last].
 *
 * [last] is the last selected line, inclusive, because that is the selection the
 * app holds and what a person points at. The wire is not: `selection` is a pair
 * of *positions*, and character 0 of the last selected line is the point before
 * that line, so sending it drops the line -- a four-line selection arrives as
 * three, and a one-line selection arrives as `1 to 0`, which reads as nothing
 * selected. The end therefore names the line after the last selected one, and
 * this is the one place that conversion happens.
 *
 * Both characters stay at 0 rather than becoming a second thing to get wrong.
 * `text` carries the content and the CLI reads the file to name the range, so
 * nothing here needs a column.
 */
fun selectionParams(path: String, first: Int, last: Int, text: String): JSONObject =
    JSONObject()
        .put(
            "selection",
            JSONObject()
                .put("start", JSONObject().put("line", first).put("character", 0))
                .put("end", JSONObject().put("line", last + 1).put("character", 0)),
        )
        .put("text", text)
        .put("filePath", path)

package apk.harness.ui

/**
 * Splits markdown into the parts a renderer handles and the parts it does not.
 *
 * The Compose markdown renderer has no table component, and its hook for unknown
 * element types also carries the contents of list items, so overriding it eats
 * text that should have been drawn. Lifting tables out of the source first
 * leaves the renderer with only what it already does well. A fence is lifted for
 * the same reason: what it should draw -- numbered lines and coloured tokens --
 * is not what the renderer draws.
 *
 * A fenced region is one block from end to end. A file wrapped in a fence to be
 * shown as itself may contain anything, pipes included, and none of it is a
 * table.
 */
sealed interface MarkdownBlock {
    data class Prose(val text: String) : MarkdownBlock

    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock

    /**
     * A fenced block: what was between the fence lines, and the info string the
     * opening line carried.
     *
     * [language] is that info string's first word, lowercased, and null when the
     * fence carried none. It is a label rather than a fact -- whoever wrote the
     * fence chose it -- so nothing downstream may fail on one it does not know.
     */
    data class Code(val language: String?, val text: String) : MarkdownBlock
}

/**
 * A block and the lines of source it came from.
 *
 * Zero-based and inclusive, the same numbers `selection_changed` reports. A
 * wrapper rather than a field on each variant, so a block stays comparable
 * without carrying a position nothing was testing.
 */
data class Spanned(val block: MarkdownBlock, val lines: IntRange)

/**
 * Splits [source] into blocks, each carrying the lines it came from.
 *
 * Prose comes out one paragraph per block rather than one run per gap between
 * tables and fences, because a paragraph is the smallest thing a person points
 * at and a block is what a selection is measured against.
 */
fun markdownBlocks(source: String): List<Spanned> {
    val lines = source.lines()
    val blocks = mutableListOf<Spanned>()
    // The prose unit being accumulated: its text, the line it started on, the
    // last non-blank line in it, and whether its first line opened a list.
    val prose = StringBuilder()
    var unitFirst = -1
    var unitLast = -1
    var unitOpensList = false

    fun flushProse() {
        if (prose.isNotBlank()) {
            blocks += Spanned(MarkdownBlock.Prose(prose.toString().trim('\n')), unitFirst..unitLast)
        }
        prose.setLength(0)
        unitFirst = -1
        unitLast = -1
        unitOpensList = false
    }

    var index = 0

    while (index < lines.size) {
        val line = lines[index]

        val opened = opensFence(line)
        if (opened != null) {
            flushProse()
            // Inside a fence every line is code, rows included, until the fence
            // closes. A fence that never closes runs to the end of the source,
            // which is what a renderer does with one and what a file truncated
            // part way through writing looks like.
            val body = mutableListOf<String>()
            var cursor = index + 1
            while (cursor < lines.size && !closesFence(lines[cursor], opened)) {
                body += lines[cursor]
                cursor++
            }
            // Splitting a source that ends in a newline leaves a trailing empty
            // line. A closed fence's closing line absorbs it; an unclosed one
            // would otherwise end in a blank line nobody typed.
            if (cursor >= lines.size && body.lastOrNull() == "") body.removeAt(body.lastIndex)
            // A closed fence ends on its closing line. An unclosed one ends on
            // its last body line, which is not the cursor: the cursor stopped
            // past the end, and a trailing empty line dropped from the body is
            // no part of the fence either.
            val end = if (cursor < lines.size) cursor else index + body.size
            blocks += Spanned(MarkdownBlock.Code(opened.language, body.joinToString("\n")), index..end)
            // Past the closing line, or past the end when there was none.
            index = cursor + 1
            continue
        }

        val separator = lines.getOrNull(index + 1)
        if (isRow(line) && separator != null && isSeparator(separator)) {
            flushProse()
            var cursor = index + 2
            val rows = mutableListOf<List<String>>()
            while (cursor < lines.size && isRow(lines[cursor])) {
                rows += cells(lines[cursor])
                cursor++
            }
            blocks += Spanned(MarkdownBlock.Table(cells(line), rows), index..cursor - 1)
            index = cursor
            continue
        }

        if (line.isBlank()) {
            // A blank line ends the unit it follows. Runs of them between
            // paragraphs belong to no unit, so they are stepped over rather than
            // becoming a block of their own.
            if (prose.isEmpty()) {
                index++
                continue
            }
            val next = (index + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            if (continuesList(unitOpensList, next?.let { lines[it] })) {
                // Kept in the text so the renderer still sees a loose list.
                prose.append(line).append('\n')
            } else {
                flushProse()
            }
            index++
            continue
        }

        if (prose.isEmpty()) {
            unitFirst = index
            unitOpensList = opensItem(line.trimStart())
        }
        prose.append(line).append('\n')
        unitLast = index
        index++
    }
    flushProse()
    return blocks
}

/** A line that opens a list item: `- `, `* `, `+ `, or `1. `. */
private fun opensItem(line: String): Boolean {
    if (line.length > 1 && line[0] in "-*+" && line[1] == ' ') return true
    val number = line.takeWhile { it.isDigit() }
    return number.isNotEmpty() && line.startsWith(". ", number.length)
}

/**
 * Whether a unit that began a list continues past a blank line.
 *
 * A loose list is one list -- `1. a`, blank, `2. b` -- and splitting it restarts
 * the numbering in each half. So a blank line does not end a unit whose first
 * line opened a list and whose next non-blank line either opens another item or
 * is indented under one.
 */
private fun continuesList(unitOpensList: Boolean, next: String?): Boolean =
    unitOpensList && next != null && (opensItem(next.trimStart()) || next.startsWith("  "))

private fun isRow(line: String): Boolean = line.trim().startsWith("|")

/** The `| --- | :-: |` line that makes the row above it a header. */
private fun isSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return false
    val parts = split(trimmed)
    if (parts.isEmpty()) return false
    return parts.all { part ->
        val cell = part.trim()
        cell.isNotEmpty() && cell.all { it == '-' || it == ':' } && cell.contains('-')
    }
}

private fun cells(line: String): List<String> = split(line.trim()).map { it.trim() }

private fun split(row: String): List<String> =
    row.removePrefix("|").removeSuffix("|").split('|')

/** An open fence: the character that opened it, how many of them, and its label. */
private data class Fence(val marker: Char, val length: Int, val language: String?)

/** A run of three or more backticks or tildes, indented no more than three spaces. */
private fun opensFence(line: String): Fence? {
    val body = line.trimStart()
    if (line.length - body.length > 3) return null
    val marker = body.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    val run = body.takeWhile { it == marker }.length
    if (run < 3) return null
    // The info string is whatever follows the run. Its first word is the
    // language; the rest is whatever else the writer put there.
    val info = body.drop(run).trim().substringBefore(' ')
    return Fence(marker, run, info.lowercase().ifEmpty { null })
}

/** A closing fence carries no info string, so the line is nothing but its marker. */
private fun closesFence(line: String, fence: Fence): Boolean {
    val body = line.trim()
    return body.length >= fence.length && body.all { it == fence.marker }
}

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

fun markdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = StringBuilder()

    fun flushProse() {
        if (prose.isNotBlank()) blocks += MarkdownBlock.Prose(prose.toString().trim('\n'))
        prose.setLength(0)
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
            blocks += MarkdownBlock.Code(opened.language, body.joinToString("\n"))
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
            blocks += MarkdownBlock.Table(cells(line), rows)
            index = cursor
        } else {
            prose.append(line).append('\n')
            index++
        }
    }
    flushProse()
    return blocks
}

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

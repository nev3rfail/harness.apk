package apk.harness.ui

/**
 * Splits markdown into the parts a renderer handles and the tables it does not.
 *
 * The Compose markdown renderer has no table component, and its hook for unknown
 * element types also carries the contents of list items, so overriding it eats
 * text that should have been drawn. Lifting tables out of the source first
 * leaves the renderer with only what it already does well.
 *
 * A fenced region is prose from end to end. A file wrapped in a fence to be
 * shown as itself may contain anything, pipes included, and none of it is a
 * table.
 */
sealed interface MarkdownBlock {
    data class Prose(val text: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
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
    var fence: Fence? = null

    while (index < lines.size) {
        val line = lines[index]
        val open = fence

        // Inside a fence every line is prose, rows included, until the fence closes.
        if (open != null) {
            prose.append(line).append('\n')
            if (closesFence(line, open)) fence = null
            index++
            continue
        }

        val opened = opensFence(line)
        if (opened != null) {
            fence = opened
            prose.append(line).append('\n')
            index++
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

/** An open fence: the character that opened it, and how many of them. */
private data class Fence(val marker: Char, val length: Int)

/** A run of three or more backticks or tildes, indented no more than three spaces. */
private fun opensFence(line: String): Fence? {
    val body = line.trimStart()
    if (line.length - body.length > 3) return null
    val marker = body.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    val run = body.takeWhile { it == marker }.length
    return if (run >= 3) Fence(marker, run) else null
}

/** A closing fence carries no info string, so the line is nothing but its marker. */
private fun closesFence(line: String, fence: Fence): Boolean {
    val body = line.trim()
    return body.length >= fence.length && body.all { it == fence.marker }
}

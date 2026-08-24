package apk.harness.ui

/**
 * Splits markdown into the parts a renderer handles and the tables it does not.
 *
 * The Compose markdown renderer has no table component, and its hook for unknown
 * element types also carries the contents of list items, so overriding it eats
 * text that should have been drawn. Lifting tables out of the source first
 * leaves the renderer with only what it already does well.
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
    while (index < lines.size) {
        val header = lines[index]
        val separator = lines.getOrNull(index + 1)

        if (isRow(header) && separator != null && isSeparator(separator)) {
            flushProse()
            var cursor = index + 2
            val rows = mutableListOf<List<String>>()
            while (cursor < lines.size && isRow(lines[cursor])) {
                rows += cells(lines[cursor])
                cursor++
            }
            blocks += MarkdownBlock.Table(cells(header), rows)
            index = cursor
        } else {
            prose.append(header).append('\n')
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

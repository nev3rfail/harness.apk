package apk.harness.ui

import java.io.File

/**
 * A file as a markdown document.
 *
 * One renderer draws every file, so a file that is not markdown is wrapped in a
 * code fence. That is what keeps source readable: the renderer preserves soft
 * line breaks, but source punctuation is markup -- a leading `#` is a heading,
 * `*ptr` is emphasis with the asterisk eaten, `<T>` is a tag, and indentation
 * collapses.
 *
 * A file that should not be drawn at all is described instead. The description
 * is decided from the first few kilobytes and the size, before the whole file is
 * read, which is the only order in which refusing costs less than showing.
 */
sealed interface FileKind {
    /** Rendered as itself. */
    data object Markdown : FileKind

    /** Wrapped in a fence, labelled where the extension names a language. */
    data class Text(val language: String?) : FileKind

    /** Described rather than drawn. */
    data object Binary : FileKind
}

/**
 * Beyond this a file is described rather than drawn. A fenced file renders as
 * one `AnnotatedString`, so a large file is one large text layout -- this is
 * the size past which that stops being drawable.
 */
const val MAX_DOCUMENT_BYTES: Long = 1L shl 20

/** What git's `buffer_is_binary` reads to decide the same question. */
private const val SNIFF_BYTES = 8000

/**
 * Text or binary comes from the bytes, and the language from the name.
 *
 * A NUL byte in the prefix is the whole binary test. Checking UTF-8 validity as
 * well would classify a latin-1 source file as binary, and a decoder's
 * replacement characters are a better answer than a refusal.
 */
fun fileKind(path: String, prefix: ByteArray): FileKind {
    if (prefix.any { it == ZERO }) return FileKind.Binary

    val name = path.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension == "md" || extension == "markdown") return FileKind.Markdown
    return FileKind.Text(LANGUAGES[extension])
}

/**
 * Long enough that nothing inside the file can close it.
 *
 * Only a line of nothing but backticks closes a fence, so scanning the whole
 * file over-counts: an inline `` `x` `` widens the fence for no reason. That is
 * the trade -- one pass, and it cannot be wrong.
 */
fun fenceWidth(text: String): Int {
    var longest = 0
    var run = 0
    for (character in text) {
        if (character == '`') {
            run++
            if (run > longest) longest = run
        } else {
            run = 0
        }
    }
    return maxOf(MIN_FENCE, longest + 1)
}

/** Markdown as itself; anything else as one fenced block. */
fun fileDocument(kind: FileKind, text: String): String = when (kind) {
    FileKind.Markdown -> text
    // Unreachable from documentFor, which never reads text for a binary file;
    // kept only so this `when` covers every FileKind.
    FileKind.Binary -> "**Binary file**\n"
    is FileKind.Text -> {
        val fence = "`".repeat(fenceWidth(text))
        // The closing fence needs a line of its own.
        val body = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
        "$fence${kind.language.orEmpty()}\n$body$fence\n"
    }
}

/** What a file is and how big, in place of contents. */
fun refusalDocument(path: String, size: Long, reason: String): String {
    val name = path.substringAfterLast('/')
    val heading = if (name.isEmpty()) reason else "$reason: `$name`"
    return "**$heading**\n\n$size bytes\n"
}

/**
 * A document, and where its lines sit against the file's.
 *
 * A file line is a document line plus [lineOffset]. Markdown is shown as itself,
 * so the offset is zero; a source file is wrapped in a fence that pushes every
 * line of it down by one, so the offset is minus one. A document that describes
 * a file rather than showing it holds no line of the file at all, and its offset
 * is null: a range in a sentence the app wrote is a claim about the file that
 * nothing supports.
 */
data class Rendered(val markdown: String, val lineOffset: Int?)

/**
 * The one reader. Both the tree and the agent's `openFile` come through here, so
 * a path shows the same document whoever asked for it.
 *
 * This is the last place that knows which kind the file is, so it answers with
 * the offset rather than leaving a caller to derive it from a kind it no longer
 * has.
 */
fun documentFor(file: File): Rendered {
    val size = file.length()

    val prefix = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(SNIFF_BYTES)
            // read(byte[]) is free to return fewer bytes than are available, so
            // filling the sniff window takes a loop, not one call.
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            buffer.copyOf(filled)
        }
    }.getOrElse { return described(file.path, size, "Cannot read") }

    val kind = fileKind(file.path, prefix)
    if (kind == FileKind.Binary) return described(file.path, size, "Binary file")
    if (size > MAX_DOCUMENT_BYTES) return described(file.path, size, "Too large to show")

    val text = runCatching { file.readText() }
        .getOrElse { return described(file.path, size, "Cannot read") }
    return Rendered(fileDocument(kind, text), if (kind == FileKind.Markdown) 0 else -1)
}

/** A refusal as a document: a description of the file, holding none of its lines. */
private fun described(path: String, size: Long, reason: String) =
    Rendered(refusalDocument(path, size, reason), null)

private const val MIN_FENCE = 3
private const val ZERO: Byte = 0

/**
 * Extension to fence info string. Absent means an unlabelled fence, which still
 * preserves every line.
 *
 * Internal because it is the only place that decides what a fence is labelled,
 * and what colours a fence reads those labels: the two lists are checked against
 * each other by a test rather than by inspection.
 */
internal val LANGUAGES = mapOf(
    "kt" to "kotlin", "kts" to "kotlin", "java" to "java",
    "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "hpp" to "cpp",
    "py" to "python", "rb" to "ruby", "rs" to "rust", "go" to "go",
    "js" to "javascript", "ts" to "typescript", "jsx" to "javascript",
    "sh" to "bash", "bash" to "bash", "zsh" to "bash",
    "json" to "json", "xml" to "xml", "html" to "html", "css" to "css",
    "yml" to "yaml", "yaml" to "yaml", "toml" to "toml", "sql" to "sql",
    "gradle" to "groovy", "properties" to "properties",
    "diff" to "diff", "patch" to "diff", "zig" to "zig", "swift" to "swift",
)

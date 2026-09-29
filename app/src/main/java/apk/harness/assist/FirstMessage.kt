package apk.harness.assist

/** Selected or shared text kept in full up to this many characters. */
const val QUOTE_LIMIT = 100_000
const val QUOTE_CUT = "[the selection was longer; the rest was cut]"

/**
 * The first message of a handed-over panel: the quote as `Selected text:` and
 * a fenced block, then the operator's [text], then one `Attached: <path>` line
 * per copied file.
 */
fun compose(quote: String?, text: String, files: List<String>): String = buildList {
    if (quote != null) {
        add("Selected text:\n" + fenced(cutToLimit(quote)) + if (quote.length > QUOTE_LIMIT) "\n$QUOTE_CUT" else "")
    }
    if (text.isNotBlank()) add(text)
    if (files.isNotEmpty()) add(files.joinToString("\n") { "Attached: $it" })
}.joinToString("\n\n")

/** [text] cut to [QUOTE_LIMIT] characters without splitting a surrogate pair. */
fun cutToLimit(text: String): String {
    if (text.length <= QUOTE_LIMIT) return text
    var end = QUOTE_LIMIT
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/** [text], cut to [QUOTE_LIMIT] with the [QUOTE_CUT] line after it when longer. */
fun cutWithLine(text: String): String = if (text.length > QUOTE_LIMIT) cutToLimit(text) + "\n" + QUOTE_CUT else text

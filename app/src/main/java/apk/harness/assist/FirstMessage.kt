package apk.harness.assist

/** Selected text quoted in full up to this many characters. */
const val QUOTE_LIMIT = 100_000
const val QUOTE_CUT = "[the selection was longer; the rest was cut]"

/**
 * The first message of a handed-over panel: the quote as `Selected text:` and
 * a fenced block, then the operator's [text], then one `Attached: <path>` line
 * per copied file.
 */
fun compose(quote: String?, text: String, files: List<String>): String = buildList {
    if (quote != null) {
        val cut = quote.length > QUOTE_LIMIT
        add("Selected text:\n" + fenced(if (cut) cutQuote(quote) else quote) + if (cut) "\n$QUOTE_CUT" else "")
    }
    if (text.isNotBlank()) add(text)
    if (files.isNotEmpty()) add(files.joinToString("\n") { "Attached: $it" })
}.joinToString("\n\n")

private fun cutQuote(quote: String): String {
    var end = QUOTE_LIMIT
    if (Character.isHighSurrogate(quote[end - 1])) end--
    return quote.substring(0, end)
}

package apk.harness.assist

/** One view of the screen, as the adapter reads it from `AssistStructure.ViewNode`. */
data class ScreenNode(
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val visible: Boolean = true,
    /** `ViewNode.getInputType()`: the class and variation of an input field. */
    val inputType: Int = 0,
    val webDomain: String? = null,
    /** The HTML `type` attribute of a web input, where `ViewNode.getHtmlInfo()` has one. */
    val htmlType: String? = null,
    val children: List<ScreenNode> = emptyList(),
)

/** About 6 000 tokens: a screen of text fits, and a WebView tree cannot fill the context. */
const val SCREEN_TEXT_LIMIT = 24_000
const val SCREEN_DEPTH_LIMIT = 64
const val SCREEN_CUT = "[the screen held more text; the rest was cut]"

/**
 * The text on the screen, one line per node with something to say: its text,
 * its description in parentheses where it differs, its hint where it has no
 * text, and `[web <domain>]` where it names one; `[url …]` first. Invisible
 * nodes, password fields and nodes past [SCREEN_DEPTH_LIMIT] are skipped with
 * everything under them.
 */
fun flattenScreen(roots: List<ScreenNode>, webUri: String? = null): String {
    val out = StringBuilder()
    val room = SCREEN_TEXT_LIMIT - SCREEN_CUT.length - 1
    var cut = false

    fun line(text: String): Boolean {
        if (out.length + text.length + 1 > room) { cut = true; return false }
        out.append(text).append('\n')
        return true
    }

    fun visit(node: ScreenNode, depth: Int): Boolean {
        if (depth > SCREEN_DEPTH_LIMIT || !node.visible || isPassword(node)) return true
        node.webDomain?.takeIf { it.isNotBlank() }?.let { if (!line("[web $it]")) return false }
        val text = node.text?.takeIf { it.isNotBlank() }
        val description = node.description?.takeIf { it.isNotBlank() && it != text }
        val hint = node.hint?.takeIf { text == null && it.isNotBlank() }
        val parts = listOfNotNull(text, description?.let { "($it)" }, hint?.let { "[hint: $it]" })
        if (parts.isNotEmpty() && !line(parts.joinToString(" "))) return false
        return node.children.all { visit(it, depth + 1) }
    }

    if (webUri != null) line("[url $webUri]")
    roots.all { visit(it, 0) }
    if (cut) out.append(SCREEN_CUT) else if (out.isNotEmpty()) out.setLength(out.length - 1)
    return out.toString()
}

// android.text.InputType's values, spelled here so this file runs on the JVM.
private const val CLASS_MASK = 0x0000000f
private const val VARIATION_MASK = 0x00000ff0
private const val CLASS_TEXT = 0x1
private const val CLASS_NUMBER = 0x2
private const val TEXT_PASSWORD = 0x80
private const val TEXT_VISIBLE_PASSWORD = 0x90
private const val TEXT_WEB_PASSWORD = 0xe0
private const val NUMBER_PASSWORD = 0x10

private fun isPassword(node: ScreenNode): Boolean {
    if (node.htmlType.equals("password", ignoreCase = true)) return true
    val variation = node.inputType and VARIATION_MASK
    return when (node.inputType and CLASS_MASK) {
        CLASS_TEXT -> variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD
        CLASS_NUMBER -> variation == NUMBER_PASSWORD
        else -> false
    }
}

/** [text] in a code fence longer than any run of backticks inside it. */
fun fenced(text: String): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "$fence\n$text\n$fence"
}

/** The text block screen text goes into the message as. */
fun screenBlock(app: String, flat: String): String = "Screen text from $app\n${fenced(flat)}"

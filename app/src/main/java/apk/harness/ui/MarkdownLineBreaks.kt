package apk.harness.ui

import androidx.compose.runtime.Composable
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Joining the lines of a paragraph the way markdown joins them.
 *
 * A newline inside a text block separates two words and nothing more, and the
 * text is wrapped to whatever width it is drawn at. Drawn as a line break
 * instead, a paragraph the writer wrapped at eighty columns is wrapped again by
 * the screen and then broken where the source broke it, which leaves a word or
 * two alone on a line for every line of the paragraph. A blank line is what
 * starts a new paragraph, and that is a matter for the splitter rather than for
 * anything here.
 *
 * A line break inside a paragraph is spelled `<br/>`, which is visible in the
 * source. Two spaces at the end of a line spell the same thing and are not
 * visible in the source, so prose carries them by accident more often than on
 * purpose; they are read as the trailing space they look like.
 */

/** `<br>`, `<br/>`, `<br />` and the rest of the ways to write the tag. */
private val BREAK_TAG = Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE)

/**
 * What [node] adds to the text being built, or null when it is neither a line
 * break nor the space around one and the renderer's own handling stands.
 *
 * [content] is the text the nodes were parsed from, which is what says whether a
 * tag is the break tag. [written] is whether anything has been written yet, so a
 * space with nothing before it is not written as a leading one.
 *
 * The answer comes from the node's neighbours rather than from what was seen
 * before it, so it reads the same whichever order the builder is driven in. The
 * space beside a break -- the newline the tag sits on its own line between, or
 * the spaces it is surrounded by -- belongs to the break and adds nothing of its
 * own. The newline of a wrapped line adds the space the two lines are joined by,
 * unless the continuation line is indented, in which case that indent is drawn
 * as the space instead.
 */
fun lineBreakText(node: ASTNode, content: CharSequence, written: Boolean): String? {
    if (isBreakTag(node, content)) return "\n"
    if (node.type == MarkdownTokenTypes.HARD_LINE_BREAK) return ""
    if (!isSpace(node)) return null

    val siblings = node.parent?.children.orEmpty()
    val at = siblings.indexOfFirst { it === node }
    val before = siblings.getOrNull(at - 1)
    val after = siblings.getOrNull(at + 1)
    return when {
        isBreakTag(before, content) || isBreakTag(after, content) -> ""
        node.type == MarkdownTokenTypes.EOL && after?.type == MarkdownTokenTypes.WHITE_SPACE -> ""
        !written -> ""
        else -> " "
    }
}

private fun isSpace(node: ASTNode): Boolean =
    node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE

private fun isBreakTag(node: ASTNode?, content: CharSequence): Boolean =
    node != null &&
        node.type == MarkdownTokenTypes.HTML_TAG &&
        BREAK_TAG.matches(content.subSequence(node.startOffset, node.endOffset))

/** The renderer's inline hook, answering line breaks and leaving the rest alone. */
@Composable
fun lineBreakAnnotator(): MarkdownAnnotator = markdownAnnotator { content, child ->
    val text = lineBreakText(child, content, length > 0) ?: return@markdownAnnotator false
    append(text)
    true
}

// The renderer's element dispatch is marked internal, and this file reaches it.
// The suppression is here rather than on the file that draws the document, so
// what it lets through is one call and not a whole composable.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package apk.harness.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.handleElement
import org.intellij.markdown.ast.ASTNode

/**
 * Draws one list item's own content, the way the renderer's own list does.
 *
 * [node] is the item, [content] the text it was parsed from, and [components]
 * the renderer's element slots. The dispatch has no case for a list item, so
 * what reaches the hook below it is exactly the item's text -- and every element
 * an item may hold, from emphasis to a fence, is drawn by the renderer rather
 * than by a second dispatch written here to drift from it.
 *
 * No spacer, because the item's own box carries its spacing.
 */
@Composable
fun ColumnScope.MarkdownItemBody(
    node: ASTNode,
    components: MarkdownComponents,
    content: String,
) {
    handleElement(node, components, content, includeSpacer = false)
}

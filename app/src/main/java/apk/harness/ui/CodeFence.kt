package apk.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Small enough to fit a useful number of columns on a phone, large enough to
// read. Tunable.
private val CodeSize = 12.sp

// The gap between the numbers and the code they belong to.
private val GutterGap = 10.dp

/**
 * Past this many characters a fence is not tokenized and stays plain.
 *
 * The bound is about the span list rather than about the tokenizer, which is
 * linear: one `AnnotatedString` carrying tens of thousands of ranges is laid out
 * on the main thread whatever produced it. Tunable.
 */
private const val HIGHLIGHT_MAX_CHARS = 128 * 1024

/**
 * A fenced block, with its lines numbered and its tokens coloured.
 *
 * Two text layouts and not two per line: the numbers are one string of every
 * line number joined by newlines, and the code is one [AnnotatedString]. Both
 * are the same monospace font at the same size, so their lines sit at the same
 * heights without either being told about the other.
 *
 * Only the code scrolls sideways. A long line takes itself off the screen and
 * leaves the numbers where they are.
 *
 * [selected] is counted in this fence's own body lines, from zero at the line
 * after the opening fence. The caller converts, so a fence draws a selection
 * without ever learning where in a document it sits. [onPointLine] answers in
 * the same numbers: the line a touch landed on, and whether it was a long
 * press. What that does to the selection is decided by whoever holds the anchor.
 */
@Composable
fun CodeFence(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
    selected: IntRange? = null,
    onPointLine: ((bodyLine: Int, anchoring: Boolean) -> Unit)? = null,
) {
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val accent = MaterialTheme.colorScheme.primary
    // One string for every number, with a span over the selected ones. Asking a
    // second layout which numbers to colour would be two layouts that have to
    // agree about where a line is.
    val gutter = remember(lineCount, selected, accent) {
        val width = lineCount.toString().length
        buildAnnotatedString {
            append((1..lineCount).joinToString("\n") { it.toString().padStart(width) })
            val band = selected?.clampedTo(lineCount) ?: return@buildAnnotatedString
            // Every number is padded to the same width and joined by a single
            // newline, so a line's own characters start at a fixed stride.
            val stride = width + 1
            addStyle(SpanStyle(color = accent), band.first * stride, band.last * stride + width)
        }
    }

    // The layout that drew the body: it is what turns a touch into a line and a
    // line into the height a band is drawn at, exactly, rather than inferred
    // from a font size the layout was free to adjust.
    var layout by remember(code) { mutableStateOf<TextLayoutResult?>(null) }
    // A gesture outlives the composition that started it, so the handler is
    // taken as it is when the touch lands rather than as it was then.
    val point by rememberUpdatedState(onPointLine)
    val highlight = accent.copy(alpha = SELECTION_ALPHA)

    // A keyword takes the theme's own accent -- the same one a selected line
    // number takes; the other three are named here, because the scheme's
    // remaining roles are not distinct enough from each other on this palette to
    // tell a string from a number.
    val palette = remember(accent) { Palette(CommentColour, TextColour, NumberColour, accent) }

    // Plain first, coloured when the pass finishes. A fence large enough for the
    // tokenizing to be noticeable is a fence large enough that waiting to draw
    // it would be worse.
    val highlighted by produceState(AnnotatedString(code), code, language, palette) {
        val grammar = grammarFor(language)
        if (grammar == null || code.length > HIGHLIGHT_MAX_CHARS) return@produceState
        value = withContext(Dispatchers.IO) { colour(code, tokenize(code, grammar), palette) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = gutter,
            modifier = Modifier.padding(start = 8.dp, end = GutterGap),
            fontFamily = FontFamily.Monospace,
            fontSize = CodeSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
        )
        Text(
            text = highlighted,
            // The scroll is on the text rather than around the row, so the
            // numbers stay put while the code moves.
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(end = 8.dp)
                // Both of these sit inside the scroll, where the width is the
                // longest line's rather than the window's: a band covers the
                // whole of the lines it marks, and a touch arrives in the
                // text's own coordinates. Only the vertical offset is read, and
                // a horizontal scroll leaves that alone either way.
                .drawBehind {
                    val lines = layout ?: return@drawBehind
                    val band = selected?.clampedTo(lines.lineCount) ?: return@drawBehind
                    val top = lines.getLineTop(band.first)
                    drawRect(
                        color = highlight,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, lines.getLineBottom(band.last) - top),
                    )
                }
                .then(
                    if (onPointLine == null) Modifier
                    else Modifier.pointerInput(code) {
                        detectTapGestures(
                            onLongPress = { at -> report(layout, at.y, true, point) },
                            onTap = { at -> report(layout, at.y, false, point) },
                        )
                    }
                ),
            onTextLayout = { layout = it },
            fontFamily = FontFamily.Monospace,
            fontSize = CodeSize,
            color = MaterialTheme.colorScheme.onSurface,
            // Never wrapped: a wrapped line would put itself opposite the wrong
            // number, and every number after it.
            softWrap = false,
        )
    }
}

/**
 * The lines of a body that were laid out, or null when the range names none.
 *
 * A fence's span runs to its closing marker, so a selection over the whole fence
 * asks for one line more than the body has, and a selection that reaches the
 * fence from below can name only that marker. The layout is the authority on how
 * many lines there are.
 */
private fun IntRange.clampedTo(lineCount: Int): IntRange? {
    if (first > lineCount - 1 || last < 0) return null
    val from = first.coerceAtLeast(0)
    return from..last.coerceIn(from, lineCount - 1)
}

/** The line [y] fell on, told to [point]. Without a layout there is no line. */
private fun report(
    layout: TextLayoutResult?,
    y: Float,
    anchoring: Boolean,
    point: ((Int, Boolean) -> Unit)?,
) {
    val lines = layout ?: return
    point?.invoke(lines.getLineForVerticalPosition(y), anchoring)
}

// Against the fence's own background, which is the scheme's surfaceVariant.
// Dimmer than the code, clearly so.
private val CommentColour = Color(0xFF7E7466)

// A string, in the one hue nothing else here uses.
private val TextColour = Color(0xFF9FCF87)

// A literal: warm, and apart from both the keyword accent and the string.
private val NumberColour = Color(0xFFD9B45B)

/** What each kind of token is drawn in. */
private data class Palette(
    val comment: Color,
    val text: Color,
    val number: Color,
    val keyword: Color,
)

/** [source] with a span over each token. Nothing Compose-shaped, so it runs anywhere. */
private fun colour(source: String, tokens: List<Token>, palette: Palette): AnnotatedString =
    buildAnnotatedString {
        append(source)
        tokens.forEach { token ->
            val shade = when (token.kind) {
                TokenKind.Comment -> palette.comment
                TokenKind.Text -> palette.text
                TokenKind.Number -> palette.number
                TokenKind.Keyword -> palette.keyword
            }
            addStyle(SpanStyle(color = shade), token.start, token.end)
        }
    }

package apk.harness.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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
 */
@Composable
fun CodeFence(language: String?, code: String, modifier: Modifier = Modifier) {
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val gutter = remember(lineCount) {
        val width = lineCount.toString().length
        (1..lineCount).joinToString("\n") { it.toString().padStart(width) }
    }

    // A keyword takes the theme's own accent; the other three are named here,
    // because the scheme's remaining roles are not distinct enough from each
    // other on this palette to tell a string from a number.
    val keyword = MaterialTheme.colorScheme.primary
    val palette = remember(keyword) { Palette(CommentColour, TextColour, NumberColour, keyword) }

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
                .padding(end = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = CodeSize,
            color = MaterialTheme.colorScheme.onSurface,
            // Never wrapped: a wrapped line would put itself opposite the wrong
            // number, and every number after it.
            softWrap = false,
        )
    }
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

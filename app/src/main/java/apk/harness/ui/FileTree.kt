package apk.harness.ui

import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether a path is a symbolic link and what it points at, as the link writes
 * it, or null when the path is not a link.
 *
 * `Os.lstat` rather than `Files.isSymbolicLink`, which needs API 26 against a
 * `minSdk` of 24 with no desugaring, and rather than comparing canonical to
 * absolute paths, which calls every path under a linked ancestor a link. The
 * `readlink` runs only once the `lstat` has said there is a link to read, so a
 * directory of ordinary files costs one call an entry.
 *
 * The two calls fail apart. The `lstat` alone answers whether there is a link,
 * so a `readlink` that fails -- a delete racing the walk -- gives a link with
 * an unknown target rather than an ordinary entry. `File.isDirectory` resolves
 * through a link, so an entry that stopped being a link would become a
 * directory the tree opens, and a link back to an ancestor would be a cycle.
 */
fun symbolicLink(file: File): SymbolicLink? {
    val isLink = runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }
        .getOrDefault(false)
    if (!isLink) return null
    return SymbolicLink(runCatching { Os.readlink(file.path) }.getOrNull())
}

/**
 * The project's files, as rows that open and close.
 *
 * Rows are lazy over a flattened list. Nested columns inside a scroll container
 * measure every row, which throws away what the lazy read bought.
 */
@Composable
fun FileTree(
    root: File,
    expanded: Set<String>,
    onToggle: (File) -> Unit,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    // Off the composition thread: a directory in the Gradle cache has thousands
    // of children, and this runs again on every change to the open set.
    val rows by produceState(initialValue = emptyList<TreeRow>(), root, expanded) {
        value = withContext(Dispatchers.IO) { visibleRows(root, expanded, ::symbolicLink) }
    }

    MaterialSurface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // The tree keeps its own first row clear of the status bar.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Header(title = root.name, subtitle = root.path, onDismiss = onDismiss)
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                items(rows, key = { it.file.path }) { row ->
                    Entry(
                        row = row,
                        isOpen = row.file.path in expanded,
                        onClick = {
                            when {
                                row.unreadable -> Unit
                                row.isLink && !row.isDirectory -> onPick(row.file)
                                row.isLink -> Unit
                                row.isDirectory -> onToggle(row.file)
                                else -> onPick(row.file)
                            }
                        },
                    )
                }
            }
        }
    }
}

// A note beside the name rather than a second name: smaller than the row's own
// text, in the colour the tree already uses for what it will not open.
private val TargetFontSize = 11.sp

// The most of a row the target may claim. Around twenty-one characters at
// [TargetFontSize], which is under half the usable width of the narrowest
// screen this runs on, so the name keeps the larger share of every row while a
// short target shrinks to fit and hands the rest back. Tunable.
private val TargetMaxWidth = 140.dp

// Named Entry rather than Row: a composable called Row in this file would shadow
// the layout Row that anything added here reaches for next.
@Composable
private fun Entry(row: TreeRow, isOpen: Boolean, onClick: () -> Unit) {
    val icon = when {
        row.unreadable -> "🚫"
        row.isLink -> "🔗"
        row.isDirectory && isOpen -> "📂"
        row.isDirectory -> "📁"
        else -> "📄"
    }
    val guide = MaterialTheme.colorScheme.outline.copy(alpha = GuideAlpha)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .treeGuides(
                depth = row.depth,
                ancestorsContinue = row.ancestorsContinue,
                isLastSibling = row.isLastSibling,
                hasChildren = row.hasChildren,
                colour = guide,
            )
            .padding(
                start = guideIndent(row.depth),
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(IconGap))
        Text(
            text = row.file.name,
            // The only weighted child, so it measures against whatever the
            // target leaves and takes all of it: an unweighted name would
            // measure first against the full width and could leave the target
            // nothing at all, losing the arrow along with it.
            modifier = Modifier.weight(1f, fill = false),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (row.unreadable) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        // Where the link points, after the name it points from. Dimmed and
        // smaller, and capped rather than weighted: an unweighted child is
        // measured before the name, so it takes only the width it needs up to
        // [TargetMaxWidth] and the name takes everything else. Weighting both
        // instead would split the remaining width in half whatever the target
        // measured, because a weighted child's slack is not handed on.
        //
        // The target as the link writes it: a relative target reads as the step
        // it is, and an absolute one reads as the path it names. Both are the
        // string `readlink` returned, which is the only form the operator can
        // check against the link itself.
        row.linkTarget?.let { target ->
            Spacer(modifier = Modifier.width(IconGap))
            Text(
                text = "→ " + target,
                modifier = Modifier.widthIn(max = TargetMaxWidth),
                fontFamily = FontFamily.Monospace,
                fontSize = TargetFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

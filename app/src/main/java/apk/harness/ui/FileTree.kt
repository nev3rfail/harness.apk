package apk.harness.ui

import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether a path is a symbolic link.
 *
 * `Os.lstat` rather than `Files.isSymbolicLink`, which needs API 26 against a
 * `minSdk` of 24 with no desugaring, and rather than comparing canonical to
 * absolute paths, which calls every path under a linked ancestor a link.
 */
fun isSymbolicLink(file: File): Boolean =
    runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)

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
        value = withContext(Dispatchers.IO) { visibleRows(root, expanded, ::isSymbolicLink) }
    }

    MaterialSurface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
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

// Named Entry rather than Row: a composable called Row in this file would shadow
// the layout Row that anything added here reaches for next.
@Composable
private fun Entry(row: TreeRow, isOpen: Boolean, onClick: () -> Unit) {
    val marker = when {
        row.unreadable -> "✕"
        row.isLink -> "→"
        row.isDirectory && isOpen -> "▾"
        row.isDirectory -> "▸"
        else -> " "
    }
    val suffix = if (row.unreadable) "  (cannot read)" else ""

    Text(
        text = "${" ".repeat(row.depth * 2)}$marker ${row.file.name}$suffix",
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        maxLines = 1,
        color = if (row.unreadable) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
    )
}

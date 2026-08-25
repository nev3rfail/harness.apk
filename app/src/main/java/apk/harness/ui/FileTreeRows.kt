package apk.harness.ui

import java.io.File

/**
 * One row of the tree.
 *
 * A link is marked rather than followed, which is what keeps a cycle out of the
 * tree without tracking visited inodes -- and this app's own home contains one,
 * because the rootfs home is a link back to it.
 */
data class TreeRow(
    val file: File,
    val depth: Int,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val unreadable: Boolean,
)

/**
 * One directory's children: directories first, then files, each alphabetical
 * without regard to case.
 *
 * Nothing is hidden and nothing is filtered. A dotfile is often the file being
 * looked for, and the toolchain directories are large but cost nothing until
 * they are opened.
 */
fun childRows(directory: File, depth: Int, isLink: (File) -> Boolean): List<TreeRow> {
    val entries = directory.listFiles() ?: return emptyList()
    return entries
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { entry ->
            val link = isLink(entry)
            TreeRow(
                file = entry,
                depth = depth,
                isDirectory = entry.isDirectory,
                isLink = link,
                unreadable = entry.isDirectory && !link && entry.listFiles() == null,
            )
        }
}

/**
 * The rows on screen for a root and the set of directories the operator has
 * opened, flattened so the list can be drawn lazily.
 *
 * Expansion is one `listFiles` per open directory rather than one walk at the
 * root: the project root holds tens of thousands of entries, and a prewalk pays
 * for all of them to show one directory.
 */
fun visibleRows(
    root: File,
    expanded: Set<String>,
    isLink: (File) -> Boolean,
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(directory: File, depth: Int) {
        childRows(directory, depth, isLink).forEach { row ->
            rows += row
            // A link is drawn as a link, so an expanded link stays closed.
            if (row.isDirectory && !row.isLink && !row.unreadable &&
                row.file.path in expanded
            ) {
                walk(row.file, depth + 1)
            }
        }
    }

    walk(root, 0)
    return rows
}

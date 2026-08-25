package apk.harness.ui

import java.io.File

/**
 * One row of the tree.
 *
 * A link is marked rather than followed, which is what keeps a cycle out of the
 * tree without tracking visited inodes -- and this app's own home contains one,
 * because the rootfs home is a link back to it.
 *
 * [linkTarget] is what a link points at, written out against the link's own
 * directory when the link gives it relatively, and null for every row that is
 * not a link. It names the target; it is not a step toward it.
 *
 * [ancestorsContinue] and [isLastSibling] are the row's place in the hierarchy:
 * one entry per level above the row, outermost first, saying whether that
 * ancestor has a sibling below it, and whether this row is the last of its own.
 * Together they are what a guide line is drawn from -- indentation alone cannot
 * say whether a level above continues past this row.
 */
data class TreeRow(
    val file: File,
    val depth: Int,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val linkTarget: String?,
    val unreadable: Boolean,
    val ancestorsContinue: List<Boolean>,
    val isLastSibling: Boolean,
)

/**
 * One directory's children: directories first, then files, each alphabetical
 * without regard to case.
 *
 * Nothing is hidden and nothing is filtered. A dotfile is often the file being
 * looked for, and the toolchain directories are large but cost nothing until
 * they are opened.
 *
 * The depth is the length of [ancestorsContinue] rather than a parameter of its
 * own, so a row's indentation and its guides are read from one value. A link is
 * known the same way: [linkTarget] answers with what the entry points at, or
 * null when it points at nothing, so being a link and having a target cannot
 * disagree.
 */
fun childRows(
    directory: File,
    ancestorsContinue: List<Boolean>,
    linkTarget: (File) -> String?,
): List<TreeRow> {
    val entries = directory.listFiles() ?: return emptyList()
    val ordered = entries
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
    return ordered.mapIndexed { index, entry ->
        val target = linkTarget(entry)
        TreeRow(
            file = entry,
            depth = ancestorsContinue.size,
            isDirectory = entry.isDirectory,
            isLink = target != null,
            linkTarget = target?.let { written -> targetPath(entry, written) },
            unreadable = entry.isDirectory && target == null && entry.listFiles() == null,
            ancestorsContinue = ancestorsContinue,
            isLastSibling = index == ordered.lastIndex,
        )
    }
}

/**
 * A link's target as the link writes it, made absolute against the directory
 * the link is in when it is written relatively.
 *
 * String work over the two paths, and nothing more: `canonicalFile` would walk
 * to the far end of the link, and not walking it is the whole point of marking
 * a link. A target naming an ancestor stays written as it was reached, `..` and
 * all, because tidying it up is another walk.
 */
private fun targetPath(link: File, written: String): String =
    if (written.startsWith("/")) written else File(link.parentFile, written).path

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
    linkTarget: (File) -> String?,
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(directory: File, ancestorsContinue: List<Boolean>) {
        childRows(directory, ancestorsContinue, linkTarget).forEach { row ->
            rows += row
            // A link is drawn as a link, so an expanded link stays closed.
            if (row.isDirectory && !row.isLink && !row.unreadable &&
                row.file.path in expanded
            ) {
                // A directory continues for everything inside it exactly when
                // it has a sibling of its own below.
                walk(row.file, ancestorsContinue + !row.isLastSibling)
            }
        }
    }

    walk(root, emptyList())
    return rows
}

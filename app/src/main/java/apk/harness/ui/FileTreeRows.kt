package apk.harness.ui

import java.io.File

/**
 * An entry that is a symbolic link, together with what it points at.
 *
 * [target] is what the link writes, and null when there is a link but its
 * target could not be read. The two answers are separate because a lookup that
 * fails on the second must not retract the first: a link with no readable
 * target is still a link, and an entry that stops being a link is descended
 * into.
 */
data class SymbolicLink(val target: String?)

/**
 * One row of the tree.
 *
 * A link is marked rather than followed, which is what keeps a cycle out of the
 * tree without tracking visited inodes -- and this app's own home contains one,
 * because the rootfs home is a link back to it.
 *
 * [isLink] is the answer to whether the row is a link, and the only thing the
 * walk consults. [linkTarget] is what that link points at, exactly as the link
 * writes it; it is null for a row that is not a link and equally for a link
 * whose target could not be read, so it says nothing about [isLink]. It names
 * the target; it is not a step toward it, and nothing resolves, normalises or
 * stats it.
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
 * own, so a row's indentation and its guides are read from one value.
 *
 * [symbolicLink] decides whether an entry is a link, and names the target as a
 * second answer within that one: a link whose target cannot be read is a link
 * with nothing to name, never an ordinary entry.
 *
 * Every entry is asked for its kind and its link once, before the sort, and the
 * answers are carried through it. `java.io.File` holds no attributes, so each
 * `isDirectory` is a fresh `stat`; sorting on the live call would put two of
 * them in every one of a sort's N log N comparisons, and a directory of a few
 * thousand entries would spend an order of magnitude more syscalls ordering
 * itself than reading itself.
 */
fun childRows(
    directory: File,
    ancestorsContinue: List<Boolean>,
    symbolicLink: (File) -> SymbolicLink?,
): List<TreeRow> {
    val entries = directory.listFiles() ?: return emptyList()
    val ordered = entries
        .map { entry -> Child(entry, entry.isDirectory, symbolicLink(entry)) }
        .sortedWith(
            compareBy({ !it.isDirectory }, { it.file.name.lowercase() }, { it.file.name }),
        )
    return ordered.mapIndexed { index, child ->
        TreeRow(
            file = child.file,
            depth = ancestorsContinue.size,
            isDirectory = child.isDirectory,
            isLink = child.link != null,
            linkTarget = child.link?.target,
            unreadable = child.isDirectory && child.link == null &&
                child.file.listFiles() == null,
            ancestorsContinue = ancestorsContinue,
            isLastSibling = index == ordered.lastIndex,
        )
    }
}

/**
 * A directory entry with the two answers about it that cost a syscall, asked
 * once and held: whether it is a directory, and its link if it is one.
 */
private class Child(val file: File, val isDirectory: Boolean, val link: SymbolicLink?)

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
    symbolicLink: (File) -> SymbolicLink?,
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(directory: File, ancestorsContinue: List<Boolean>) {
        childRows(directory, ancestorsContinue, symbolicLink).forEach { row ->
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

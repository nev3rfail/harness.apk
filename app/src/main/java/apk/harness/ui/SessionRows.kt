package apk.harness.ui

import apk.harness.chats.Chat
import apk.harness.chats.Project

/**
 * One row of the chat tree.
 *
 * The tree is two levels deep: a project at depth 0, its chats at depth 1. Both
 * carry the same four fields the file tree's rows carry, so one guide drawing
 * serves both drawers.
 *
 * [ancestorsContinue] is one entry per level above the row, outermost first,
 * saying whether that level has a sibling below it; [isLastSibling] says the
 * same of the row itself; [hasChildren] says the row below this one is inside
 * it, which is what the guide from a project into its chats is drawn from.
 */
sealed interface SessionRow {
    val id: String
    val depth: Int
    val ancestorsContinue: List<Boolean>
    val isLastSibling: Boolean
    val hasChildren: Boolean

    data class ProjectRow(
        val project: Project,
        override val isLastSibling: Boolean,
        override val hasChildren: Boolean,
    ) : SessionRow {
        override val id: String get() = "p:" + project.key
        override val depth: Int get() = 0
        override val ancestorsContinue: List<Boolean> get() = emptyList()
    }

    data class ChatRow(
        val project: Project,
        val chat: Chat,
        override val ancestorsContinue: List<Boolean>,
        override val isLastSibling: Boolean,
    ) : SessionRow {
        override val id: String get() = "c:" + chat.transcript.path
        override val depth: Int get() = 1
        override val hasChildren: Boolean get() = false
    }

    /**
     * The chats an open project holds that are not drawn, and the tap that draws
     * them.
     *
     * [isLastSibling] is fixed rather than passed: the row exists only where it
     * is the last thing inside its project, so the elbow that closes the
     * project's column lands here and the chat above it draws a guide that
     * continues past it.
     */
    data class MoreRow(
        val project: Project,
        /** How many of the project's chats are not drawn. */
        val hidden: Int,
        override val ancestorsContinue: List<Boolean>,
    ) : SessionRow {
        override val id: String get() = "m:" + project.key
        override val depth: Int get() = 1
        override val isLastSibling: Boolean get() = true
        override val hasChildren: Boolean get() = false
    }
}

// What an open project lists before it offers to list the rest. Enough that a
// project's recent work is on screen, few enough that the projects either side
// of it are too -- which is what the drawer is opened to choose between.
private const val VISIBLE_CHATS = 6

/**
 * The rows on screen for a list of projects, the set of them the operator has
 * opened and the set whose chats are all drawn, flattened so the list can be
 * drawn lazily.
 *
 * A project's chats continue past a chat row exactly when a project follows the
 * one they belong to, so a chat's ancestor is settled by the project's place in
 * the list rather than by anything about the chat.
 *
 * An open project holding more than six chats and not in [revealed] lists six
 * and then a [SessionRow.MoreRow] for the remainder. The cut is made here rather
 * than in the composable because it decides `isLastSibling` and adds a row, and
 * both are this function's contract.
 */
fun sessionRows(
    projects: List<Project>,
    expanded: Set<String>,
    revealed: Set<String>,
): List<SessionRow> =
    buildList {
        projects.forEachIndexed { index, project ->
            val isLast = index == projects.lastIndex
            val open = project.key in expanded
            add(
                SessionRow.ProjectRow(
                    project = project,
                    isLastSibling = isLast,
                    // The cap never empties a project, so this is what it was.
                    hasChildren = open && project.chats.isNotEmpty(),
                ),
            )
            if (!open) return@forEachIndexed
            val whole = project.key in revealed || project.chats.size <= VISIBLE_CHATS
            val drawn = if (whole) project.chats else project.chats.take(VISIBLE_CHATS)
            drawn.forEachIndexed { chatIndex, chat ->
                add(
                    SessionRow.ChatRow(
                        project = project,
                        chat = chat,
                        ancestorsContinue = listOf(!isLast),
                        isLastSibling = whole && chatIndex == drawn.lastIndex,
                    ),
                )
            }
            if (!whole) {
                add(
                    SessionRow.MoreRow(
                        project = project,
                        hidden = project.chats.size - VISIBLE_CHATS,
                        ancestorsContinue = listOf(!isLast),
                    ),
                )
            }
        }
    }

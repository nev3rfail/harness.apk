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
        override val id: String get() = "p:" + project.path
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
}

/**
 * The rows on screen for a list of projects and the set of them the operator
 * has opened, flattened so the list can be drawn lazily.
 *
 * A project's chats continue past a chat row exactly when a project follows the
 * one they belong to, so a chat's ancestor is settled by the project's place in
 * the list rather than by anything about the chat.
 */
fun sessionRows(projects: List<Project>, expanded: Set<String>): List<SessionRow> =
    buildList {
        projects.forEachIndexed { index, project ->
            val isLast = index == projects.lastIndex
            val open = project.path in expanded
            add(
                SessionRow.ProjectRow(
                    project = project,
                    isLastSibling = isLast,
                    hasChildren = open && project.chats.isNotEmpty(),
                ),
            )
            if (!open) return@forEachIndexed
            project.chats.forEachIndexed { chatIndex, chat ->
                add(
                    SessionRow.ChatRow(
                        project = project,
                        chat = chat,
                        ancestorsContinue = listOf(!isLast),
                        isLastSibling = chatIndex == project.chats.lastIndex,
                    ),
                )
            }
        }
    }

package apk.harness.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import apk.harness.chats.Chat
import apk.harness.chats.Project
import apk.harness.chats.projects
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The chats an agent home holds, as rows that open and close.
 *
 * A project's directory is a directory on this device, and its chats are the
 * transcripts filed under it. Both come off the filesystem: see
 * `apk.harness.chats.ChatTree`.
 */
@Composable
fun SessionTree(
    /** The agent's config directory -- `~/.claude`. */
    agentHome: File,
    /** The conversations with a running agent behind them. */
    live: Set<String>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpenChat: (Project, Chat) -> Unit,
    onNewChat: (Project) -> Unit,
    onDismiss: () -> Unit,
) {
    // Off the composition thread: this reads the end of every transcript in the
    // home, which is a hundred files on a device that has been used.
    val found by produceState(initialValue = emptyList<Project>(), agentHome) {
        value = withContext(Dispatchers.IO) { projects(agentHome) }
    }

    val rows = remember(found, expanded) { flatten(found, expanded) }

    MaterialSurface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // The list keeps its own first row clear of the status bar.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Header(
                title = "Chats",
                subtitle = summary(found),
                onDismiss = onDismiss,
            )
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                items(rows, key = { it.id }) { row ->
                    when (row) {
                        is SessionRow.ProjectRow -> ProjectEntry(
                            project = row.project,
                            isOpen = row.project.path in expanded,
                            onClick = { onToggle(row.project.path) },
                            onNew = { onNewChat(row.project) },
                        )

                        is SessionRow.ChatRow -> ChatEntry(
                            chat = row.chat,
                            isLive = row.chat.sessionId in live,
                            enabled = row.project.reachable,
                            onClick = { onOpenChat(row.project, row.chat) },
                        )
                    }
                }
            }
        }
    }
}

/** A flattened row, so the list is lazy over one list rather than nested columns. */
private sealed interface SessionRow {
    val id: String

    class ProjectRow(val project: Project) : SessionRow {
        override val id: String get() = "p:" + project.path
    }

    class ChatRow(val project: Project, val chat: Chat) : SessionRow {
        override val id: String get() = "c:" + chat.transcript.path
    }
}

private fun flatten(projects: List<Project>, expanded: Set<String>): List<SessionRow> =
    buildList {
        for (project in projects) {
            add(SessionRow.ProjectRow(project))
            if (project.path in expanded) {
                project.chats.forEach { add(SessionRow.ChatRow(project, it)) }
            }
        }
    }

private fun summary(projects: List<Project>): String {
    val chats = projects.sumOf { it.chats.size }
    return "${projects.size} projects, $chats chats"
}

// One level of indentation, matching the file tree's, so the two drawers read
// as one shape from two sides.
private val IndentStep = 12.dp
private val IconGap = 6.dp

// The live marker. Small enough to sit inside a row's height and bright enough
// to be the only thing on the row that is not text.
private val DotSize = 8.dp
private const val DotDimmest = 0.25f
private const val PulseMillis = 900

@Composable
private fun ProjectEntry(
    project: Project,
    isOpen: Boolean,
    onClick: () -> Unit,
    onNew: () -> Unit,
) {
    val icon = when {
        !project.reachable -> "🚫"
        isOpen -> "📂"
        else -> "📁"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(IconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // A short name, which is what tells two projects apart on a
                // screen this narrow. The whole path is underneath it.
                text = project.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (project.reachable) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // A guessed path is marked, because flattening a directory name
                // cannot be undone and the result is often wrong.
                text = project.path + if (project.guessed) "  (guessed)" else "",
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${project.chats.size}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A project whose directory is gone can be read and not entered, so it
        // is offered no way to start a conversation in it.
        if (project.reachable) {
            Text(
                text = "＋",
                modifier = Modifier
                    .clickable(onClick = onNew)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ChatEntry(chat: Chat, isLive: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = IndentStep * 2, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(DotSize), contentAlignment = Alignment.Center) {
            if (isLive) LiveDot()
        }
        Spacer(modifier = Modifier.width(IconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.label,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(chat.modified).toString(),
                fontSize = 10.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The marker for a conversation with a running agent behind it.
 *
 * It pulses rather than sitting still, because a still dot beside a row of text
 * is read as punctuation. What it says is only that the process is alive: what
 * that agent is doing is not something the app can see from outside it.
 */
@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = DotDimmest,
        animationSpec = infiniteRepeatable(
            animation = tween(PulseMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Box(
        modifier = Modifier
            .size(DotSize)
            // Drawn rather than given a background colour, so a frame of the
            // pulse redraws the dot rather than recomposing the row.
            .drawBehind {
                drawCircle(color = LiveGreen, alpha = alpha, radius = size.minDimension / 2f)
            },
    )
}

// Bright enough to read against both themes' surfaces, which neither the
// scheme's primary nor its tertiary is on this palette.
private val LiveGreen = Color(0xFF3DDC84)

package apk.harness.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import apk.harness.agents.RunningSession
import apk.harness.chats.Chat
import apk.harness.chats.Project
import apk.harness.chats.UNATTRIBUTED
import apk.harness.chats.foldHome

/**
 * The chats an agent home holds, as rows that open and close.
 *
 * The tree draws what it is handed. Reading belongs to the caller, so the
 * drawer polls the roster while it is open and the tree draws the result.
 *
 * [running] is keyed by session id, [current] is the session on screen, and
 * [openTabs] is the sessions that have a tab: between them they decide the
 * marker, the background and the trailing control of every chat row. [canClose]
 * says whether the closing control can act, which is a fact about the tabs
 * rather than about any one chat.
 *
 * [homes] is every spelling of the agent's home, so a project row can fold it
 * away and keep the segment that says which project it is.
 *
 * [revealed] is the projects listing every chat they hold. A project outside it
 * lists six and a row that reveals the rest, which [onReveal] adds it to.
 */
@Composable
fun SessionTree(
    projects: List<Project>,
    homes: List<String>,
    running: Map<String, RunningSession>,
    current: String?,
    openTabs: Set<String>,
    canClose: Boolean,
    expanded: Set<String>,
    revealed: Set<String>,
    onToggle: (String) -> Unit,
    onReveal: (String) -> Unit,
    onOpenChat: (Project, Chat) -> Unit,
    onContinueHere: (Project, Chat) -> Unit,
    onCloseChat: (Chat) -> Unit,
    onNewChat: (Project) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(projects, expanded, revealed) {
        sessionRows(projects, expanded, revealed)
    }

    MaterialSurface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // The list keeps its own first row clear of the status bar.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Header(
                title = "Chats",
                subtitle = summary(projects),
                onDismiss = onDismiss,
            )
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                items(rows, key = { it.id }) { row ->
                    when (row) {
                        is SessionRow.ProjectRow -> ProjectEntry(
                            row = row,
                            homes = homes,
                            isOpen = row.project.key in expanded,
                            isCurrent = row.project.chats.any { it.sessionId == current },
                            onClick = { onToggle(row.project.key) },
                            onNew = { onNewChat(row.project) },
                        )

                        is SessionRow.ChatRow -> ChatEntry(
                            row = row,
                            agent = running[row.chat.sessionId],
                            isCurrent = row.chat.sessionId == current,
                            hasTab = row.chat.sessionId in openTabs,
                            canClose = canClose,
                            onClick = { onOpenChat(row.project, row.chat) },
                            onContinue = { onContinueHere(row.project, row.chat) },
                            onClose = { onCloseChat(row.chat) },
                        )

                        is SessionRow.MoreRow -> MoreEntry(
                            row = row,
                            onClick = { onReveal(row.project.key) },
                        )
                    }
                }
            }
        }
    }
}

private fun summary(projects: List<Project>): String {
    val chats = projects.sumOf { it.chats.size }
    return "${count(projects.size, "project")}, ${count(chats, "chat")}"
}

/** A count and its noun, singular when there is one of them. */
private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

// The live marker. Small enough to sit inside a row's height and still be the
// only thing on the row that is not text.
private val DotSize = 8.dp
private const val DotDimmest = 0.25f
private const val DotFull = 1f
private const val PulseMillis = 900

// The row's own text and the note under it. The sizes the file tree uses, so
// the two drawers side by side read as one application.
private val RowFontSize = 13.sp
private val NoteFontSize = 11.sp

// The space above and below a row's text. Small, because the row carries two
// lines of text against the file tree's one, and the two drawers stand beside
// each other at a height the operator reads as the same.
private val RowPadding = 6.dp

// The platform's minimum tap area, spent on width. A trailing control is this
// wide and as tall as the row, which puts a finger's worth of target under a
// glyph while the row keeps the height its text asks for.
private val TouchTarget = 48.dp

// The glyph inside that area, large enough to read as a control rather than as
// more of the row's text.
private val ControlFontSize = 18.sp

@Composable
private fun ProjectEntry(
    row: SessionRow.ProjectRow,
    homes: List<String>,
    isOpen: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onNew: () -> Unit,
) {
    val project = row.project
    val guide = MaterialTheme.colorScheme.outline.copy(alpha = GuideAlpha)
    val icon = when {
        !project.reachable -> "🚫"
        isOpen -> "📂"
        else -> "📁"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The row's text settles the height, and the height is definite, so
            // a trailing control fills it top to bottom.
            .height(IntrinsicSize.Min)
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
                end = 4.dp,
                top = RowPadding,
                bottom = RowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(IconSlot), contentAlignment = Alignment.CenterStart) {
            Text(text = icon, fontSize = RowFontSize)
        }
        Spacer(modifier = Modifier.width(IconGap))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // The path itself, which is what a project is. A shortened
                    // name drawn above it said the same thing twice and agreed
                    // with neither the path nor the directory it was folded
                    // from. The home is folded to `~` because it is the head of
                    // almost every path here, and the tail -- which is what an
                    // ellipsis eats -- is the part that names the project. A
                    // project naming no directory draws the word instead of a
                    // path, there being no directory to draw.
                    text = project.path?.let { foldHome(it, homes) } ?: UNATTRIBUTED,
                    fontFamily = FontFamily.Monospace,
                    fontSize = RowFontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (project.reachable) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    // Shrinks to leave the label beside it room, and takes no
                    // more than it needs when the label is not there.
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Every project here is named the same thing, so which one holds
                // the conversation on screen is worth saying on the row that is
                // collapsed as well as on the chat inside it.
                if (isCurrent) {
                    Spacer(modifier = Modifier.width(IconGap))
                    Text(
                        text = "current",
                        fontFamily = FontFamily.Monospace,
                        fontSize = NoteFontSize,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        // The column ends where its text ends, so without this the count is
        // butted against whatever the row's last word was.
        Spacer(modifier = Modifier.width(IconGap))
        Text(
            text = "${project.chats.size}",
            fontFamily = FontFamily.Monospace,
            fontSize = NoteFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A project whose directory is gone can be read and not entered, so it
        // is offered no way to start a conversation in it.
        if (project.reachable) {
            Control(
                glyph = "＋",
                colour = MaterialTheme.colorScheme.primary,
                onClick = onNew,
            )
        }
    }
}

@Composable
private fun ChatEntry(
    row: SessionRow.ChatRow,
    agent: RunningSession?,
    isCurrent: Boolean,
    hasTab: Boolean,
    canClose: Boolean,
    onClick: () -> Unit,
    onContinue: () -> Unit,
    onClose: () -> Unit,
) {
    val chat = row.chat
    val enabled = row.project.reachable
    val guide = MaterialTheme.colorScheme.outline.copy(alpha = GuideAlpha)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The row's text settles the height, and the height is definite, so
            // a trailing control fills it top to bottom.
            .height(IntrinsicSize.Min)
            // Under the tap feedback, so the ripple lands on top of the marked
            // row's colour, and under the guides, so the column the drawer
            // draws runs across that row.
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .treeGuides(
                depth = row.depth,
                ancestorsContinue = row.ancestorsContinue,
                isLastSibling = row.isLastSibling,
                hasChildren = row.hasChildren,
                colour = guide,
                // The row draws no icon, so the elbow carries on to the text.
                elbow = ElbowToText,
            )
            .padding(
                start = guideIndent(row.depth),
                end = 4.dp,
                top = RowPadding,
                bottom = RowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A chat carries no icon, and reserves the width of one so its text
        // lines up a step to the right of the project holding it.
        Spacer(modifier = Modifier.width(IconSlot + IconGap))
        Column(modifier = Modifier.weight(1f)) {
            // The arrangement a project row uses for `current`: the name is the
            // only weighted child, so it shrinks to leave the age room and takes
            // no more than it needs when the name is short. A long name
            // ellipsises and the age stays whole.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = RowFontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(IconGap))
                // Abbreviated, because an unweighted child measures at its
                // intrinsic width and the name takes what is left: `3 hr. ago`
                // leaves a row's worth of name where `3 hours ago` does not.
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        chat.modified,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    ).toString(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = NoteFontSize,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A row is one line or two and its height follows its content. The
            // shell measures at `IntrinsicSize.Min`, so the trailing control
            // fills whichever height the text settled and the dot stays centred
            // against it.
            chat.lastLine?.let { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = NoteFontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The marker comes from the roster and from nothing else: a chat the
        // roster does not name has no agent behind it and takes no marker. The
        // slot keeps its width when it is empty, so the control beside it sits
        // at the same offset on every row.
        if (agent == null) {
            Spacer(modifier = Modifier.width(DotSize))
        } else when (agent.status) {
            // Alive with nothing in hand, which is a thing at rest.
            "idle" -> Dot(colour = LiveGreen, alpha = DotFull)
            // Stopped on a question, which is the state that wants a person, so
            // it takes a colour of its own.
            "waiting" -> Dot(colour = LiveAmber, alpha = DotFull)
            // Busy, and equally a record that has not said yet: unknown is
            // nearer to working than to waiting.
            else -> LiveDot()
        }
        // One control at most, and only where it has something to do.
        when {
            // The last tab does not close, so its row carries no ×.
            hasTab && canClose -> Control(
                glyph = "×",
                colour = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onClose,
            )
            // A chat with a tab is reached by tapping its row, and a project
            // whose directory is gone is one no agent can be started in. What
            // is left is every chat this tab can be turned into.
            !hasTab && enabled -> Control(
                glyph = "↩",
                colour = MaterialTheme.colorScheme.primary,
                onClick = onContinue,
            )
            // The slot keeps its width with nothing in it, so every row ends at
            // the same edge whether it carries a control or not.
            else -> Spacer(modifier = Modifier.width(TouchTarget))
        }
    }
}

/**
 * The row that draws the rest of a project's chats.
 *
 * A row of the tree rather than a control beside it: it draws no icon, takes the
 * elbow that runs out to the text, and reserves the same trailing slots a chat
 * does, so its text starts and the row ends where every chat's does. The whole
 * width is the tap, as a project row's whole width is its toggle.
 *
 * Nothing here puts it back. Revealing lasts as long as the project stays open,
 * and collapsing the project is how it is put back.
 */
@Composable
private fun MoreEntry(row: SessionRow.MoreRow, onClick: () -> Unit) {
    val guide = MaterialTheme.colorScheme.outline.copy(alpha = GuideAlpha)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .treeGuides(
                depth = row.depth,
                ancestorsContinue = row.ancestorsContinue,
                isLastSibling = row.isLastSibling,
                hasChildren = row.hasChildren,
                colour = guide,
                // The row draws no icon, so the elbow carries on to the text.
                elbow = ElbowToText,
            )
            .padding(
                start = guideIndent(row.depth),
                end = 4.dp,
                top = RowPadding,
                bottom = RowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(IconSlot + IconGap))
        Text(
            text = "${row.hidden} more",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = RowFontSize,
            maxLines = 1,
            // Tapping it does something, which is what separates it from the
            // rows around it that only say something.
            color = MaterialTheme.colorScheme.primary,
        )
        // The marker's slot and the control's, kept empty, so the row ends at
        // the edge every chat row ends at.
        Spacer(modifier = Modifier.width(DotSize))
        Spacer(modifier = Modifier.width(TouchTarget))
    }
}

/**
 * One trailing control: a glyph centred in a tap area of its own, a tap
 * target's width across and the row's full height down, so a finger aimed at it
 * lands on it and the row's own tap covers everything else.
 */
@Composable
private fun Control(glyph: String, colour: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(TouchTarget)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = ControlFontSize, color = colour)
    }
}

/**
 * The marker itself, in [colour] at [alpha].
 *
 * Drawn rather than given a background colour, so a frame of the pulse redraws
 * the dot and leaves the row alone.
 */
@Composable
private fun Dot(colour: Color, alpha: Float) {
    Box(
        modifier = Modifier
            .size(DotSize)
            .drawBehind {
                drawCircle(color = colour, alpha = alpha, radius = size.minDimension / 2f)
            },
    )
}

/**
 * The marker for a conversation whose agent is working.
 *
 * It pulses, which is what separates it from the steady markers a resting agent
 * takes: a still dot beside a row of text is read as punctuation.
 */
@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = DotFull,
        targetValue = DotDimmest,
        animationSpec = infiniteRepeatable(
            animation = tween(PulseMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Dot(colour = LiveGreen, alpha = alpha)
}

// Bright enough to read against both themes' surfaces, which neither the
// scheme's primary nor its tertiary is on this palette.
private val LiveGreen = Color(0xFF3DDC84)

// An agent stopped at a question only a person can answer.
private val LiveAmber = Color(0xFFFFB300)

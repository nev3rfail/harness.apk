package apk.harness.assist

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import apk.harness.agents.BACKENDS
import apk.harness.ui.MarkdownDocument

/** What the panel asks of whatever hosts it: the session window or AskActivity. */
interface PanelHost {
    /** True in the session window, which can read the screen and cannot ask for permissions. */
    val inSession: Boolean
    /** Closes the panel: a dismiss, unless an escalation is ending. */
    fun dismiss()
    /** Starts an activity while the panel is still shown; false when nothing takes it. */
    fun start(intent: Intent): Boolean
    fun askContext(text: Boolean, shot: Boolean)
    fun requestMic()
    fun keepScreenOn(on: Boolean)
}

@Composable
fun AskPanel(state: PanelState, host: PanelHost) {
    // Only an activity has a back dispatcher; the session hears back in onBackPressed.
    if (!host.inSession) BackHandler { state.back(host) }
    val inFlight = state.conversation.inFlight
    LaunchedEffect(inFlight) { host.keepScreenOn(inFlight) }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val sheet = maxHeight * SHEET_SHARE
        Box(
            Modifier.fillMaxSize().background(SCRIM)
                .clickable(remember { MutableInteractionSource() }, indication = null) { state.back(host) },
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = sheet),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (!state.escalating) host.dismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = CLOSE)
                    }
                }
                when (state.gate) {
                    PanelState.Gate.Locked -> Text(LOCKED, modifier = Modifier.padding(vertical = 16.dp))
                    PanelState.Gate.NotStaged -> {
                        Text(NOT_SET_UP, modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { state.openApp(host) }) { Text(OPEN_APP) }
                    }
                    // Markdown links open through the same start, then dismiss; a
                    // cell's own links go through MarkdownDocument's openExternal.
                    PanelState.Gate.Open -> CompositionLocalProvider(
                        LocalUriHandler provides object : UriHandler {
                            override fun openUri(uri: String) = state.openLink(host, uri)
                        },
                    ) {
                        when (val shown = state.disclosure) {
                            is Disclosing.Screen -> ScreenDisclosure(
                                provider = BACKENDS.first().provider,
                                always = false,
                                onAllow = { state.allow(host) },
                                onNotNow = { state.disclosure = null },
                                openPolicy = { url -> state.openLink(host, url) },
                            )
                            is Disclosing.Mic -> MicDisclosure(
                                provider = BACKENDS.first().provider,
                                otherApps = shown.otherApps,
                                onContinue = { state.micContinue(host) },
                                onNotNow = { state.disclosure = null },
                            )
                            null -> Body(state, host, Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(state: PanelState, host: PanelHost, threadModifier: Modifier) {
    val turns = state.conversation.turns
    val list = rememberLazyListState()
    val lastLength = turns.lastOrNull()?.items?.sumOf { (it as? Item.Text)?.text?.length ?: 1 } ?: 0
    LaunchedEffect(turns.size, lastLength) { if (turns.isNotEmpty()) list.scrollToItem(turns.lastIndex) }

    LazyColumn(state = list, modifier = threadModifier) {
        items(turns.size) { index ->
            TurnView(turns[index], onContinue = { state.escalate(host) }, openLink = { state.openLink(host, it) })
        }
    }
    StatusLine(state, host)
    if (host.inSession) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.screenText, onClick = { state.chip(host, text = true, shot = false) }, label = { Text(SCREEN_TEXT) })
            FilterChip(selected = state.screenshot, onClick = { state.chip(host, text = false, shot = true) }, label = { Text(SCREENSHOT) })
        }
        if (state.nothingCame) Text(NOTHING_CAME, style = MaterialTheme.typography.bodySmall)
    }
    state.quote?.let { quote ->
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(quote, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
    state.attachments.forEach { Text(it.line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (state.copying > 0) {
        Text(
            if (state.copying == 1) "Copying 1 file…" else "Copying ${state.copying} files…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val context = LocalContext.current
    state.refused.forEach {
        val size = it.size?.let { bytes -> " (" + Formatter.formatShortFileSize(context, bytes) + ")" } ?: ""
        Text("${it.name}$size: ${it.why}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (state.focusField) LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
        state.focusField = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.field,
            onValueChange = { typed ->
                // A key typed stops listening; the recognizer sets the field without calling this.
                if (state.listening) state.voice?.stop()
                state.field = typed
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
            maxLines = 5,
        )
        IconButton(onClick = { state.mic(host) }) {
            Icon(if (state.listening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = MIC)
        }
        IconButton(onClick = state::send, enabled = state.canSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = SEND)
        }
    }
    state.voiceProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (state.micBlocked) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(MIC_BLOCKED, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.openAppSettings(host) }) { Text(OPEN_SETTINGS) }
    } else if (state.otherAppsMic) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(OTHER_APPS_MIC, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.disclosure = Disclosing.Mic(otherApps = true) }) { Text(ALLOW_MIC) }
    }
    if (state.escalating) Text(OPENING, modifier = Modifier.padding(vertical = 8.dp))
    else TextButton(onClick = { state.escalate(host) }) { Text(CONTINUE) }
}

@Composable
private fun TurnView(turn: Turn, onContinue: () -> Unit, openLink: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (turn.message.isNotEmpty()) Text(turn.message, color = MaterialTheme.colorScheme.primary)
        turn.attachments.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        turn.items.forEach { item ->
            when (item) {
                is Item.Text -> MarkdownDocument(content = item.text, openExternal = openLink)
                is Item.Tool -> Text("${item.name}: ${item.input}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                is Item.Denied -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$NEEDS_APPROVAL ${item.tool}", modifier = Modifier.weight(1f))
                    TextButton(onClick = onContinue) { Text(CONTINUE) }
                }
            }
        }
        if (!turn.done && (turn.thinking || turn.items.isEmpty())) Text(THINKING, color = MaterialTheme.colorScheme.onSurfaceVariant)
        turn.retrying?.let { Text("Retrying (attempt $it)", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun StatusLine(state: PanelState, host: PanelHost) {
    when (val status = state.conversation.status) {
        Status.Starting -> Text(STARTING, style = MaterialTheme.typography.bodySmall)
        Status.Ready -> {}
        Status.SignedOut -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SIGNED_OUT, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.signIn(host) }) { Text(SIGN_IN) }
        }
        is Status.Errors -> status.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        is Status.Stopped -> {
            Text("The agent stopped (exit ${status.code}).", color = MaterialTheme.colorScheme.error)
            status.stderr.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
        }
    }
}

private const val SHEET_SHARE = 0.75f
private val SCRIM = Color.Black.copy(alpha = 0.4f)
private const val TITLE = "Ask Agent"
private const val CLOSE = "Close"
private const val MIC = "Speak"
private const val SEND = "Send"
private const val LOCKED = "Unlock the phone to ask"
private const val NOT_SET_UP = "The agent is not set up yet."
private const val OPEN_APP = "Open the app"
private const val STARTING = "Starting the agent…"
private const val SIGNED_OUT = "The agent is not signed in."
private const val SIGN_IN = "Sign in in a tab"
private const val THINKING = "Thinking…"
private const val NEEDS_APPROVAL = "Needs your approval:"
private const val CONTINUE = "Continue in a tab"
private const val OPENING = "Opening in a tab…"
private const val SCREEN_TEXT = "Screen text"
private const val SCREENSHOT = "Screenshot"
private const val NOTHING_CAME = "Nothing came from the screen."
internal const val OTHER_APPS_MIC = "Voice input in other apps needs the microphone"
private const val ALLOW_MIC = "Allow"
internal const val MIC_BLOCKED = "The microphone is turned off for Ask Agent in the app's settings"
internal const val OPEN_SETTINGS = "Settings"

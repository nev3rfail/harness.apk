package apk.harness.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * The Android half of a handoff: the [Intent] it becomes, who would take it, and
 * the fire itself.
 *
 * Everything that can be decided is decided before this file is reached, so what
 * is left here is translation. That is also why it is the only part of the
 * feature with no unit tests: `android.content.Intent` off the device is a stub
 * that throws, so the seam between decided and fired is where the tests stop.
 */

/**
 * The intent [handoff] becomes, with a content grant for anything it carries.
 *
 * `FLAG_ACTIVITY_NEW_TASK` goes on every one of them: a tool call is not a user
 * gesture and there is no task of the receiving app's to start into.
 */
fun intentFor(context: Context, handoff: Handoff): Intent {
    val streams = handoff.content.map { contentUri(context, it) }

    val intent = when (handoff.action) {
        Action.View -> Intent(Intent.ACTION_VIEW).apply {
            val data = streams.firstOrNull() ?: Uri.parse(handoff.uri)
            if (handoff.mimeType != null) setDataAndType(data, handoff.mimeType) else setData(data)
        }

        Action.Share -> Intent(Intent.ACTION_SEND).apply {
            type = handoff.mimeType
            streams.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) }
        }

        Action.ShareMany -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = handoff.mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))
        }

        Action.Compose -> Intent(Intent.ACTION_SENDTO, Uri.parse(handoff.uri))

        Action.Dial -> Intent(Intent.ACTION_DIAL, Uri.parse(handoff.uri))

        Action.Settings -> settingsIntent(context, screenOf(handoff))

        // An absent package answers with nothing to launch. The implicit intent
        // in its place resolves to nothing either, so `appFor` says so and the
        // fire never happens, rather than this throwing on a null.
        Action.Launch -> context.packageManager.getLaunchIntentForPackage(handoff.target.orEmpty())
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(handoff.target)
    }

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (streams.isNotEmpty()) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (handoff.target != null && handoff.action != Action.Launch) {
        intent.setPackage(handoff.target)
    }
    applyExtras(intent, handoff)

    // The sheet is a task of its own, so it needs the flag the intent inside it
    // already carries.
    return if (chooserInstead(handoff)) {
        Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        intent
    }
}

/**
 * The label of the app that would take this, or null when nothing would.
 *
 * Asked before the person is, because *"an app"* is not something anybody can
 * weigh, and because a fire nothing handles is better refused as a sentence than
 * thrown as an `ActivityNotFoundException`.
 *
 * A share resolves to the sheet rather than to an app: the person is about to
 * pick, so there is nothing yet to name.
 *
 * This answers at all because the app targets API 28. Package visibility
 * filtering arrives at API 30, and at that target without a `<queries>` block
 * this would silently answer null for everything.
 */
fun appFor(context: Context, handoff: Handoff): String? {
    if (chooserInstead(handoff)) return "the share sheet"
    val packages = context.packageManager
    return packages.resolveActivity(intentFor(context, handoff), 0)
        ?.activityInfo?.loadLabel(packages)?.toString()
}

/**
 * Starts it, and says what happened.
 *
 * Every way this can fail is caught here, because the caller is a tool call and
 * an exception from inside the platform would leave the MCP endpoint rather than
 * the agent. `SecurityException` is the one that matters most: a `content://`
 * the receiving app was not granted arrives as that and nothing else.
 */
fun fire(context: Context, handoff: Handoff): HandoffOutcome = try {
    val app = appFor(context, handoff)
    if (app == null) {
        HandoffOutcome.NoHandler
    } else {
        context.startActivity(intentFor(context, handoff))
        HandoffOutcome.Started(app)
    }
} catch (notFound: ActivityNotFoundException) {
    HandoffOutcome.NoHandler
} catch (refused: SecurityException) {
    HandoffOutcome.Failed("the system refused it: ${refused.message}")
} catch (bad: IllegalArgumentException) {
    HandoffOutcome.Failed("that cannot be handed over: ${bad.message}")
}

/**
 * A file as something another app can read.
 *
 * The authority is built from the running package name, so both channels are
 * right without a build-config constant. The grant is read-only and lasts as
 * long as the activity it starts.
 */
private fun contentUri(context: Context, path: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

/**
 * The extras a receiving app reads, under the names it reads them by.
 *
 * An agent names them the way the tool's own vocabulary does -- `text`,
 * `subject`, `to` -- and no published app looks for those; they look for
 * `android.intent.extra.TEXT` and its siblings. Anything not in the mapping goes
 * across under the name it was given, which is what an app-specific extra needs.
 */
private fun applyExtras(intent: Intent, handoff: Handoff) {
    for ((name, value) in handoff.extras) {
        // Consumed by the action itself: `settings` is a screen, not a payload.
        if (handoff.action == Action.Settings && name == SCREEN) continue
        val key = WELL_KNOWN[name] ?: name
        when (value) {
            is ExtraValue.Text -> intent.putExtra(key, value.value)
            is ExtraValue.Number -> intent.putExtra(key, value.value)
            is ExtraValue.Flag -> intent.putExtra(key, value.value)
            is ExtraValue.Series -> intent.putExtra(key, value.values.toTypedArray())
        }
    }
}

private val WELL_KNOWN = mapOf(
    "text" to Intent.EXTRA_TEXT,
    "subject" to Intent.EXTRA_SUBJECT,
    "to" to Intent.EXTRA_EMAIL,
    "cc" to Intent.EXTRA_CC,
    "bcc" to Intent.EXTRA_BCC,
)

private const val SCREEN = "screen"

/** The screen a `settings` handoff names, proved to be one of them by `handoffFor`. */
private fun screenOf(handoff: Handoff): SettingsScreen {
    val word = (handoff.extras[SCREEN] as? ExtraValue.Text)?.value
    return SettingsScreen.entries.first { it.word == word }
}

/**
 * The system screen itself.
 *
 * [SettingsScreen.AppDetails] is this app's own page rather than an arbitrary
 * one: the reason to open it is a permission the operator has to grant this app,
 * and naming another package would make the tool a way to walk the settings of
 * everything installed.
 */
private fun settingsIntent(context: Context, screen: SettingsScreen): Intent = when (screen) {
    SettingsScreen.Developer -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    SettingsScreen.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
    SettingsScreen.Battery -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

package apk.harness.intents

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a tool call's arguments as a [Handoff], or says in a sentence why it is not one.
 *
 * Every check happens here, before anything Android exists, so a request the app
 * cannot express comes back as prose the agent can act on instead of as an
 * exception thrown from inside the platform.
 *
 * [writable] is the directory prefixes a path in `files` may sit under -- the
 * caller passes the app's own `filesDir` and `cacheDir`, so this code never asks
 * Android where those are.
 */
fun handoffFor(arguments: JSONObject, writable: List<String>): Result<Handoff> = try {
    Result.success(readHandoff(arguments, writable))
} catch (refused: HandoffRefusalException) {
    Result.failure(refused)
}

private fun readHandoff(arguments: JSONObject, writable: List<String>): Handoff {
    // A bare uri is a view, which is what makes the one-field call this replaces
    // keep working unchanged.
    val word = arguments.optString("action").ifBlank { Action.View.word }
    val action = Action.entries.firstOrNull { it.word == word } ?: refuse(noSuchAction(word))
    val uri = arguments.optString("uri").ifBlank { null }
    val mimeType = arguments.optString("mime_type").ifBlank { null }
    val target = arguments.optString("package").ifBlank { null }
    val extras = extrasOf(arguments.optJSONObject("extras"))
    val content = contentOf(arguments.optJSONArray("files"), writable)

    when (action) {
        Action.View, Action.Compose, Action.Dial ->
            if (uri == null) refuse("${action.word} needs a uri.")

        Action.Launch -> if (target == null) refuse("launch needs a package.")

        Action.Share -> {
            if (mimeType == null) refuse("share needs a mime_type.")
            if (content.size > 1) refuse("share carries one file. share_many carries several.")
            val text = extras["text"]
            if (text == null && content.isEmpty()) {
                refuse("share needs something to share: extras.text, or one entry in files.")
            }
            if (text != null && content.isNotEmpty()) {
                refuse("share carries extras.text or one file, not both.")
            }
        }

        Action.ShareMany -> {
            if (mimeType == null) refuse("share_many needs a mime_type.")
            if (content.isEmpty()) refuse("share_many needs at least one entry in files.")
        }

        Action.Settings -> {
            val screen = (extras["screen"] as? ExtraValue.Text)?.value
                ?: refuse("settings needs extras.screen, one of: ${screens()}.")
            if (SettingsScreen.entries.none { it.word == screen }) {
                refuse("no such settings screen: $screen. This app can open: ${screens()}.")
            }
        }
    }

    return Handoff(action, uri, mimeType, extras, target, content)
}

/** The seven words, from the enum, so a refusal cannot fall behind the vocabulary. */
private fun vocabulary(): String = Action.entries.joinToString(", ") { it.word }

private fun screens(): String = SettingsScreen.entries.joinToString(", ") { it.word }

/**
 * Why an action is not one of the seven.
 *
 * `call` and `install` answer by name, because they are the two an agent reaches
 * for next -- after finding `dial`, and after being told the app will not
 * install -- and a bare list of words does not say why not. Every refusal ends
 * with the list, which is the reply that teaches the agent what it may ask for.
 */
private fun noSuchAction(word: String): String = when (word) {
    "call" -> "placing a call is refused: dial puts a number in front of a person and " +
        "they press the button, so a misread page cannot ring anyone. This app can: ${vocabulary()}."

    "install" -> "installing is refused: an install stays with adb, because the signature " +
        "match is what keeps a reinstall from destroying the data directory this app runs " +
        "out of. This app can: ${vocabulary()}."

    else -> "no such action: $word. This app can: ${vocabulary()}."
}

private fun extrasOf(extras: JSONObject?): Map<String, ExtraValue> {
    if (extras == null) return emptyMap()
    return extras.keys().asSequence().associateWith { name ->
        when (val value = extras.get(name)) {
            is String -> ExtraValue.Text(value)
            is Boolean -> ExtraValue.Flag(value)
            is kotlin.Number -> ExtraValue.Number(value.toDouble())
            is JSONArray -> ExtraValue.Series(seriesOf(name, value))
            else -> refuse("an extra may not nest: $name.")
        }
    }
}

private fun seriesOf(name: String, values: JSONArray): List<String> =
    (0 until values.length()).map { values.get(it) as? String ?: refuse("an extra may not nest: $name.") }

/**
 * The paths in `files`, each proved to sit inside the app's own directories.
 *
 * Both sides are resolved before they are compared: a prefix test on the text as
 * written lets `files/../../secrets` through, and on a device `filesDir` itself
 * arrives through a symlink, so the two only line up once both are canonical.
 */
private fun contentOf(files: JSONArray?, writable: List<String>): List<String> {
    if (files == null) return emptyList()
    val roots = writable.map { canonical(it) }
    return (0 until files.length()).map { index ->
        val path = files.get(index) as? String ?: refuse("a file must be a path: ${files.get(index)}")
        val resolved = canonical(path)
        if (roots.none { resolved == it || resolved.startsWith(it + File.separatorChar) }) {
            refuse("$path is outside this app's own directories: ${writable.joinToString(", ")}.")
        }
        path
    }
}

private fun canonical(path: String): String =
    runCatching { File(path).canonicalPath }.getOrElse { refuse("$path cannot be resolved: ${it.message}") }

private fun refuse(reason: String): Nothing = throw HandoffRefusalException(HandoffRefusal(reason))

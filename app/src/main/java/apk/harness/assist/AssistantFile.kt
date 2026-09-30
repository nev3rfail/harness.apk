package apk.harness.assist

import java.io.File
import org.json.JSONObject

enum class ScreenContext(val value: String) { Ask("ask"), Always("always") }

/** `noBackupFilesDir/assistant.json`. */
data class AssistantSettings(
    /** The screen-content disclosure was accepted and not withdrawn. */
    val consented: Boolean = false,
    val screenContext: ScreenContext = ScreenContext.Ask,
    /** The app has asked for the microphone before. */
    val micAsked: Boolean = false,
) {
    fun withdrawn() = copy(consented = false, screenContext = ScreenContext.Ask)
}

/**
 * Whether a microphone request's answer means denied for good: denied with no
 * rationale after an earlier request. A first dialog closed without a choice
 * also gives no rationale, and is not taken as final.
 */
fun micBlocked(granted: Boolean, rationale: Boolean, askedBefore: Boolean): Boolean =
    !granted && !rationale && askedBefore

// VoiceInteractionSession's values, spelled here so this file runs on the JVM.
const val SHOW_WITH_ASSIST = 1
const val SHOW_WITH_SCREENSHOT = 2

/** What `setDisabledShowContext` is given: both kinds, unless always with consent. */
fun disabledContext(setting: ScreenContext, consented: Boolean): Int =
    if (setting == ScreenContext.Always && consented) 0 else SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT

fun encodeAssistant(settings: AssistantSettings): String =
    JSONObject().put("consented", settings.consented).put("screenContext", settings.screenContext.value)
        .put("micAsked", settings.micAsked).toString()

fun decodeAssistant(text: String): AssistantSettings {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return AssistantSettings()
    return AssistantSettings(
        consented = json.opt("consented") == true,
        screenContext = ScreenContext.entries.firstOrNull { it.value == json.opt("screenContext") } ?: ScreenContext.Ask,
        micAsked = json.opt("micAsked") == true,
    )
}

fun readAssistant(file: File): AssistantSettings =
    runCatching { decodeAssistant(file.readText()) }.getOrDefault(AssistantSettings())

/** Written through a temporary file and a rename, so a reader sees the old file or the new one. */
fun writeAssistant(file: File, settings: AssistantSettings) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(encodeAssistant(settings))
    check(temporary.renameTo(file)) { "$temporary was not renamed over $file" }
}

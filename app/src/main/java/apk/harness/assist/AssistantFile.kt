package apk.harness.assist

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

enum class ScreenContext(val value: String) { Ask("ask"), Always("always") }

/** `noBackupFilesDir/assistant.json`. */
data class AssistantSettings(
    /** The screen-content disclosure was accepted and not withdrawn. */
    val consented: Boolean = false,
    val screenContext: ScreenContext = ScreenContext.Ask,
) {
    fun withdrawn() = AssistantSettings(consented = false, screenContext = ScreenContext.Ask)
}

// VoiceInteractionSession's values, spelled here so this file runs on the JVM.
const val SHOW_WITH_ASSIST = 1
const val SHOW_WITH_SCREENSHOT = 2

/** What `setDisabledShowContext` is given: both kinds, unless always with consent. */
fun disabledContext(setting: ScreenContext, consented: Boolean): Int =
    if (setting == ScreenContext.Always && consented) 0 else SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT

fun encodeAssistant(settings: AssistantSettings): String =
    JSONObject().put("consented", settings.consented).put("screenContext", settings.screenContext.value).toString()

fun decodeAssistant(text: String): AssistantSettings {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return AssistantSettings()
    return AssistantSettings(
        consented = json.opt("consented") == true,
        screenContext = ScreenContext.entries.firstOrNull { it.value == json.opt("screenContext") } ?: ScreenContext.Ask,
    )
}

fun readAssistant(file: File): AssistantSettings =
    runCatching { decodeAssistant(file.readText()) }.getOrDefault(AssistantSettings())

/** Written through a temporary file and a move, so a reader sees the old file or the new one. */
fun writeAssistant(file: File, settings: AssistantSettings) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(encodeAssistant(settings))
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

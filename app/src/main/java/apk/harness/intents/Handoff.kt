package apk.harness.intents

/**
 * What the phone is being asked to do, in this app's words rather than Android's.
 *
 * The vocabulary is the allowlist. A free-text action checked against a list
 * elsewhere is two lists that have to agree, and `Tools.kt` already shows what
 * becomes of those.
 */
enum class Action(val word: String) {
    View("view"),
    Share("share"),
    ShareMany("share_many"),
    Compose("compose"),
    Dial("dial"),
    Settings("settings"),
    Launch("launch"),
}

/** A value a receiving app reads by name. Nothing nests: no published receiver reads a tree. */
sealed interface ExtraValue {
    data class Text(val value: String) : ExtraValue
    data class Number(val value: Double) : ExtraValue
    data class Flag(val value: Boolean) : ExtraValue
    data class Series(val values: List<String>) : ExtraValue
}

/** A fire, decided and ready to become an `Intent`. */
data class Handoff(
    val action: Action,
    val uri: String? = null,
    val mimeType: String? = null,
    val extras: Map<String, ExtraValue> = emptyMap(),
    val target: String? = null,
    /** Absolute paths inside the app's own directories, checked before this exists. */
    val content: List<String> = emptyList(),
)

/** Why a request never became a fire. */
data class HandoffRefusal(val reason: String)

/**
 * A refusal on its way back to the caller.
 *
 * `handoffFor` answers a `Result`, so the refusal travels as a throwable and the
 * caller reads [refusal] rather than a message it would have to parse.
 */
class HandoffRefusalException(val refusal: HandoffRefusal) : Exception(refusal.reason)

/** What happened to a fire. */
sealed interface HandoffOutcome {
    /** Started, and this is what took it. */
    data class Started(val app: String) : HandoffOutcome
    data object NoHandler : HandoffOutcome
    data object Declined : HandoffOutcome
    data class Failed(val reason: String) : HandoffOutcome
}

/** The settings screens this app will open, which is the same closed-set argument as [Action]. */
enum class SettingsScreen(val word: String) {
    Developer("developer"),
    AppDetails("app_details"),
    Battery("battery"),
}

package apk.harness.assist

/** What a show of the panel is, and so what the panel does with it. */
enum class Show { Locked, NotStaged, Fresh, Listen, OwnReShow }

/**
 * [locked] is `KeyguardManager.isKeyguardLocked`; [shown] whether the panel is
 * on screen; [ownRequest] whether the show carries [Assistant.PANEL_REQUEST];
 * [staged] `Bootstrap.isInstalled()`.
 */
fun classifyShow(locked: Boolean, shown: Boolean, ownRequest: Boolean, staged: Boolean): Show = when {
    locked -> Show.Locked
    ownRequest -> Show.OwnReShow
    shown -> Show.Listen
    !staged -> Show.NotStaged
    else -> Show.Fresh
}

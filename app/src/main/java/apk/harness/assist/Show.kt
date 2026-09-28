package apk.harness.assist

/** What a show of the panel is, and so what the panel does with it. */
enum class Show { Locked, NotStaged, Fresh, Listen, OwnReShow }

/**
 * Whether a panel counts as shown for [classifyShow]: on screen, with its gate
 * open. A locked or unstaged panel still up after an unlock or a setup has
 * nothing to keep, so the next summon replaces it.
 */
fun panelShown(visible: Boolean, gate: PanelState.Gate?): Boolean = visible && gate == PanelState.Gate.Open

/**
 * [locked] is `KeyguardManager.isKeyguardLocked`; [shown] is [panelShown];
 * [ownRequest] whether the show carries [Assistant.PANEL_REQUEST];
 * [staged] `Bootstrap.isInstalled()`.
 */
fun classifyShow(locked: Boolean, shown: Boolean, ownRequest: Boolean, staged: Boolean): Show = when {
    locked -> Show.Locked
    ownRequest -> Show.OwnReShow
    shown -> Show.Listen
    !staged -> Show.NotStaged
    else -> Show.Fresh
}

package apk.harness.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService

/** The assistant role's service. Screen context is off unless the panel asks or the operator chose "Always". */
class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Assistant.service = this
        // The platform keeps this value in memory only, so every onReady sets it.
        setDisabledShowContext(Assistant.disabled(this))
    }

    /** API 34+: a re-show the panel asked for did not happen, which ends its handover as a dismiss. */
    override fun onShowSessionFailed(args: Bundle) {
        if (args.getBoolean(Assistant.PANEL_REQUEST)) Assistant.handoverEvent(HandoverEvent.ShowFailed)
    }

    override fun onShutdown() {
        if (Assistant.service === this) Assistant.service = null
        super.onShutdown()
    }
}

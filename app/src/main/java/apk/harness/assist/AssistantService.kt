package apk.harness.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** The assistant role's service. Screen context is off unless the panel asks. */
class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Assistant.service = this
        // The platform keeps this value in memory only, so every onReady sets it.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    override fun onShutdown() {
        if (Assistant.service === this) Assistant.service = null
        super.onShutdown()
    }
}

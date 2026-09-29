package apk.harness.assist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val both = SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT

    @Test
    fun `context is disabled unless the setting is always and consent is recorded`() {
        assertEquals(both, disabledContext(ScreenContext.Ask, consented = false))
        assertEquals(both, disabledContext(ScreenContext.Ask, consented = true))
        assertEquals(both, disabledContext(ScreenContext.Always, consented = false))
        assertEquals(0, disabledContext(ScreenContext.Always, consented = true))
    }

    @Test
    fun `a withdrawal disables context again and keeps the microphone record`() {
        val withdrawn = AssistantSettings(consented = true, screenContext = ScreenContext.Always, micAsked = true).withdrawn()
        assertEquals(AssistantSettings(consented = false, screenContext = ScreenContext.Ask, micAsked = true), withdrawn)
        assertEquals(both, disabledContext(withdrawn.screenContext, withdrawn.consented))
    }

    @Test
    fun `the settings are read back as they were written`() {
        val file = File(folder.root, "assistant.json")
        val settings = AssistantSettings(consented = true, screenContext = ScreenContext.Always, micAsked = true)
        writeAssistant(file, settings)
        assertEquals(settings, readAssistant(file))
    }

    @Test
    fun `an unreadable file reads as the defaults`() {
        assertEquals(AssistantSettings(), readAssistant(File(folder.root, "absent.json")))
        assertEquals(AssistantSettings(), decodeAssistant("half a fi"))
    }

    @Test
    fun `the microphone is blocked only when denied with no rationale after an earlier request`() {
        assertEquals(true, micBlocked(granted = false, rationale = false, askedBefore = true))
        assertEquals(false, micBlocked(granted = false, rationale = false, askedBefore = false))
        assertEquals(false, micBlocked(granted = false, rationale = true, askedBefore = true))
        assertEquals(false, micBlocked(granted = true, rationale = false, askedBefore = true))
    }

    @Test
    fun `an unknown value reads as ask and keeps the consent`() {
        assertEquals(AssistantSettings(consented = true, screenContext = ScreenContext.Ask), decodeAssistant("""{"consented":true,"screenContext":"sometimes","extra":1}"""))
    }
}

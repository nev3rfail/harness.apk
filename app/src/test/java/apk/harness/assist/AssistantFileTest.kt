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
    fun `a withdrawal disables context again`() {
        val withdrawn = AssistantSettings(consented = true, screenContext = ScreenContext.Always).withdrawn()
        assertEquals(AssistantSettings(consented = false, screenContext = ScreenContext.Ask), withdrawn)
        assertEquals(both, disabledContext(withdrawn.screenContext, withdrawn.consented))
    }

    @Test
    fun `the settings are read back as they were written`() {
        val file = File(folder.root, "assistant.json")
        val settings = AssistantSettings(consented = true, screenContext = ScreenContext.Always)
        writeAssistant(file, settings)
        assertEquals(settings, readAssistant(file))
    }

    @Test
    fun `an unreadable file reads as the defaults`() {
        assertEquals(AssistantSettings(), readAssistant(File(folder.root, "absent.json")))
        assertEquals(AssistantSettings(), decodeAssistant("half a fi"))
    }

    @Test
    fun `an unknown value reads as ask and keeps the consent`() {
        assertEquals(AssistantSettings(consented = true, screenContext = ScreenContext.Ask), decodeAssistant("""{"consented":true,"screenContext":"sometimes","extra":1}"""))
    }
}

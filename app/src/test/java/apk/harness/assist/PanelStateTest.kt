package apk.harness.assist

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PanelStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun shot(name: String) = Attachment.Screenshot(folder.newFile(name).apply { writeBytes(ByteArray(10)) }, 10)

    private var asked = 0
    private val host = object : PanelHost {
        override val inSession = true
        override fun dismiss() {}
        override fun start(intent: Intent) = false
        override fun askContext(text: Boolean, shot: Boolean) { asked++ }
        override fun requestMic() {}
        override fun keepScreenOn(on: Boolean) {}
    }

    @Test
    fun `a new screenshot replaces the pending one and deletes its file, and screen text replaces only screen text`() {
        val state = PanelState()
        val first = shot("1.jpg")
        state.screenshotCame(first)
        state.screenTextCame(Attachment.ScreenText("Chrome", "old"))
        val second = shot("2.jpg")
        state.screenshotCame(second)
        state.screenTextCame(Attachment.ScreenText("Maps", "new"))
        assertFalse(first.file.exists())
        assertEquals(listOf(second, Attachment.ScreenText("Maps", "new")), state.attachments)
        assertTrue(state.screenText && state.screenshot)
    }

    @Test
    fun `a delivery to a panel that is not open is dropped with its file`() {
        val state = PanelState.locked()
        val screenshot = shot("1.jpg")
        state.screenshotCame(screenshot)
        state.screenTextCame(Attachment.ScreenText("Chrome", "text"))
        assertFalse(screenshot.file.exists())
        assertEquals(emptyList<Attachment>(), state.attachments)
        assertFalse(state.screenText || state.screenshot)
    }

    @Test
    fun `a null screenshot drops the pending one with its file and says nothing came`() {
        val state = PanelState()
        val screenshot = shot("1.jpg")
        state.screenshotCame(screenshot)
        state.screenshotCame(null)
        assertFalse(screenshot.file.exists())
        assertEquals(emptyList<Attachment>(), state.attachments)
        assertTrue(state.nothingCame)
    }

    @Test
    fun `a tap on a selected chip turns it off and drops its attachment, asking nothing`() {
        val state = PanelState()
        val screenshot = shot("1.jpg")
        state.screenshotCame(screenshot)
        state.screenTextCame(Attachment.ScreenText("Chrome", "text"))
        state.chip(host, text = false, shot = true)
        assertFalse(state.screenshot)
        assertFalse(screenshot.file.exists())
        assertEquals(listOf<Attachment>(Attachment.ScreenText("Chrome", "text")), state.attachments)
        state.chip(host, text = true, shot = false)
        assertFalse(state.screenText)
        assertEquals(emptyList<Attachment>(), state.attachments)
        assertEquals(0, asked)
    }

    @Test
    fun `a request that ends with a chip unanswered turns that chip off and says nothing came`() {
        val state = PanelState()
        state.screenText = true
        state.screenshot = true
        val screenshot = shot("1.jpg")
        state.screenshotCame(screenshot)
        state.requestEnded()
        assertFalse(state.screenText)
        assertTrue(state.screenshot)
        assertTrue(state.nothingCame)
    }

    @Test
    fun `a request that ends with every chip answered says nothing more`() {
        val state = PanelState()
        state.screenTextCame(Attachment.ScreenText("Chrome", "text"))
        state.requestEnded()
        assertTrue(state.screenText)
        assertFalse(state.nothingCame)
    }

    @Test
    fun `under Always an open request alone decides, so a chip turned off takes nothing`() {
        val state = PanelState()
        // Screen text turned off, Screenshot turned off and on: the request is for the screenshot only.
        state.request = ContextRequest(text = false, shot = true)
        assertFalse(state.takeDelivery(Delivery.Structure, always = true).take)
        assertTrue(state.takeDelivery(Delivery.Screenshot, always = true).take)
    }

    @Test
    fun `under Always with no open request every delivery is taken`() {
        val state = PanelState()
        assertTrue(state.takeDelivery(Delivery.Structure, always = true).take)
        assertFalse(state.takeDelivery(Delivery.Structure, always = false).take)
    }

    @Test
    fun `a delivery that comes clears what said nothing came`() {
        val state = PanelState()
        state.screenshotCame(null)
        state.screenTextCame(Attachment.ScreenText("Chrome", "text"))
        assertFalse(state.nothingCame)
        state.screenTextCame(null)
        state.screenshotCame(shot("1.jpg"))
        assertFalse(state.nothingCame)
    }
}

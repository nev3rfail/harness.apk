package apk.harness.assist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentsTest {
    @Test
    fun `a delivery of one kind replaces only that kind`() {
        val pending = listOf(Attachment.ScreenText("Chrome", "old"), Attachment.Screenshot(File("/c/1.jpg"), 10))
        val text = Attachment.ScreenText("Maps", "new")
        assertEquals(listOf(Attachment.Screenshot(File("/c/1.jpg"), 10), text), replacing(pending, text))
    }

    @Test
    fun `each attachment names itself in the turn`() {
        assertEquals("Screen text from Maps", Attachment.ScreenText("Maps", "x").line)
        assertEquals("Screenshot", Attachment.Screenshot(File("/c/1.jpg"), 1).line)
    }
}

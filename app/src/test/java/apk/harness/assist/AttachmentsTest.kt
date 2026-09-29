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

    @Test
    fun `a shared image and a copied file name themselves by name`() {
        assertEquals("Image: IMG_1.jpg", Attachment.Image(File("/c/x.jpg"), "image/jpeg", 10, "IMG_1.jpg").line)
        assertEquals("File: a.pdf", Attachment.Document(File("/i/a.pdf")).line)
    }

    @Test
    fun `a summon's screen context does not replace a share's attachments`() {
        val image = Attachment.Image(File("/c/x.jpg"), "image/jpeg", 10, "IMG_1.jpg")
        val file = Attachment.Document(File("/i/a.pdf"))
        val text = Attachment.ScreenText("Maps", "t")
        assertEquals(listOf(image, file, text), replacing(listOf(image, file), text))
    }
}

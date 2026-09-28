package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBudgetTest {

    private val mb = 1024L * 1024

    @Test
    fun `base64 is four bytes for every three, padded`() {
        assertEquals(0, base64Size(0))
        assertEquals(4, base64Size(1))
        assertEquals(4, base64Size(3))
        assertEquals(8, base64Size(4))
    }

    @Test
    fun `images within the budget all go as blocks`() {
        assertEquals(ImageSplit(listOf(0, 1, 2), emptyList()), imageBudget(emptyList(), listOf(mb, mb, mb)))
    }

    @Test
    fun `the image crossing the budget and every one after it go as files`() {
        val split = imageBudget(listOf(15 * mb), listOf(2 * mb, 4 * mb, 1))
        assertEquals(listOf(0), split.blocks)
        assertEquals(listOf(1, 2), split.files)
    }

    @Test
    fun `past the twentieth image in a message the rest go as files`() {
        val split = imageBudget(emptyList(), List(25) { 1000L })
        assertEquals((0 until 20).toList(), split.blocks)
        assertEquals((20 until 25).toList(), split.files)
    }

    @Test
    fun `a screenshot on a conversation at the budget is refused`() {
        assertFalse(screenshotFits(listOf(IMAGE_BUDGET), 1))
    }

    @Test
    fun `a hundred and first image under the budget is refused`() {
        assertFalse(screenshotFits(List(100) { 10L }, 10))
        assertTrue(screenshotFits(List(99) { 10L }, 10))
    }

    @Test
    fun `an always screenshot on a later summon at the budget is refused`() {
        val sent = listOf(10 * mb, 10 * mb - 100)
        assertTrue(screenshotFits(sent, 100))
        assertFalse(screenshotFits(sent + 100, 1))
    }
}

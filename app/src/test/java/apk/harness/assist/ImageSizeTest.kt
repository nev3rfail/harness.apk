package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizeTest {
    @Test fun `a landscape image is scaled to 1568 on its long edge`() = assertEquals(1568 to 1176, targetSize(4000, 3000))
    @Test fun `a portrait image is scaled to 1568 on its long edge`() = assertEquals(1176 to 1568, targetSize(3000, 4000))
    @Test fun `a square image is scaled on both edges`() = assertEquals(1568 to 1568, targetSize(2000, 2000))
    @Test fun `an image already small is kept`() = assertEquals(800 to 600, targetSize(800, 600))
    @Test fun `a thin image keeps at least one pixel`() = assertEquals(1568 to 1, targetSize(10_000, 2))
}

package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePixelsTest {
    @Test fun `a transparent pixel comes out white`() = assertEquals(0xFFFFFFFF.toInt(), onWhite(0x00000000))
    @Test fun `an opaque pixel is kept`() = assertEquals(0xFFFF0000.toInt(), onWhite(0xFFFF0000.toInt()))
    @Test fun `a half transparent black pixel comes out grey`() = assertEquals(0xFF7F7F7F.toInt(), onWhite(0x80000000.toInt()))
}

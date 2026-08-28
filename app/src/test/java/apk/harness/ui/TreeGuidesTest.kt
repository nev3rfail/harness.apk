package apk.harness.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TreeGuidesTest {

    @Test
    fun `a root row clears half a step, the stub and the gap`() {
        assertEquals(13.dp, guideIndent(0))
    }

    @Test
    fun `each level below adds one full step`() {
        assertEquals(25.dp, guideIndent(1))
    }

    @Test
    fun `a deep row is its depth in steps past the root`() {
        assertEquals(49.dp, guideIndent(3))
    }
}

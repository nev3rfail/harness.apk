package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PidTest {

    @Test
    fun `the harness-pid line names the started process`() {
        assertEquals(4321, pidOf("harness-pid 4321"))
    }

    @Test
    fun `any other stderr line names none`() {
        assertNull(pidOf("warning: something went wrong"))
        assertNull(pidOf("harness-pid"))
        assertNull(pidOf("harness-pid x"))
    }
}

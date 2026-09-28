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

    @Test
    fun `every descendant of the started process is found, grandchildren too`() {
        // pid to ppid: 10 started 11 and 12; 11 started 13, which started 14; 20 is not ours.
        val parents = mapOf(11 to 10, 12 to 10, 13 to 11, 14 to 13, 20 to 1, 10 to 1)
        assertEquals(setOf(11, 12, 13, 14), descendants(10, parents).toSet())
        assertEquals(emptyList<Int>(), descendants(14, parents))
    }
}

package apk.harness.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {

    @Test
    fun `an absent home is linked`() {
        assertEquals(HomeAction.LINK, homeAction(exists = false, isLink = false, entries = 0))
    }

    @Test
    fun `a home that is already a link is left alone`() {
        assertEquals(HomeAction.KEEP, homeAction(exists = true, isLink = true, entries = 0))
    }

    @Test
    fun `an empty directory left by an earlier build is replaced`() {
        assertEquals(HomeAction.REPLACE, homeAction(exists = true, isLink = false, entries = 0))
    }

    @Test
    fun `a directory holding something is reported rather than removed`() {
        assertEquals(HomeAction.REPORT, homeAction(exists = true, isLink = false, entries = 3))
    }

    @Test
    fun `a link is kept whatever the entry count says`() {
        assertEquals(HomeAction.KEEP, homeAction(exists = true, isLink = true, entries = 7))
    }
}

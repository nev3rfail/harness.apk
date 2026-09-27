package apk.harness.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `each entry proot leaves names its pid`() {
        assertEquals(123, prootEntryPid("proot-123-AbCdEf"))
        assertEquals(45, prootEntryPid("proot-tmpfs--45-AbCdEf"))
        assertEquals(6, prootEntryPid("mountinfo-6-AbCdEf"))
        assertEquals(78, prootEntryPid("auxv-78-AbCdEf"))
        assertEquals(9, prootEntryPid("prootseq-9-AbCdEf"))
        assertEquals(10, prootEntryPid("prootshm-10-AbCdEf"))
    }

    @Test
    fun `other names in the temporary directory are not proot's`() {
        for (name in listOf(
            ".repack-deb-lock", "repack-deb.AbCdEf", "proot", "proot-", "proot-12",
            "proot-x-AbCdEf", "proot--AbCdEf", "prootx-1-AbCdEf", "auxv-1a-AbCdEf",
        )) {
            assertNull(name, prootEntryPid(name))
        }
    }
}

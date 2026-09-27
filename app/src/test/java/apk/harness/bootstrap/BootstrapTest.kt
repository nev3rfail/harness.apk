package apk.harness.bootstrap

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapTest {

    @get:Rule
    val folder = TemporaryFolder()

    // Windows without developer mode refuses to make a symlink.
    private fun assumeSymlinks() {
        val probe = File(folder.root, "probe").toPath()
        assumeTrue("needs symlinks", runCatching { Files.createSymbolicLink(probe, probe) }.isSuccess)
        Files.delete(probe)
    }

    @Test
    fun `a cache link becomes a directory, and the apt cache it pointed at goes`() {
        assumeSymlinks()
        val old = folder.newFolder("cache")
        File(old, "apt/archives").mkdirs()
        File(old, "apt/archives/x.deb").writeText("deb")
        File(old, "other").writeText("kept")
        // A link inside the old cache is removed, not followed.
        val sibling = folder.newFolder("sibling").apply { File(this, "f").writeText("kept") }
        Files.createSymbolicLink(File(old, "apt/archives/s").toPath(), sibling.toPath())
        val cach = File(folder.root, "cach")
        Files.createSymbolicLink(cach.toPath(), old.toPath())

        stageAptCache(cach, old)

        assertFalse(Files.isSymbolicLink(cach.toPath()))
        assertTrue(File(cach, "apt/archives/partial").isDirectory)
        assertFalse(File(old, "apt").exists())
        assertTrue(File(old, "other").isFile)
        assertTrue(File(sibling, "f").isFile)
    }

    @Test
    fun `a relative cache link to the cache directory is cleaned the same way`() {
        assumeSymlinks()
        val old = folder.newFolder("cache")
        File(old, "apt/archives").mkdirs()
        val cach = File(folder.root, "cach")
        Files.createSymbolicLink(cach.toPath(), java.nio.file.Paths.get("cache"))
        stageAptCache(cach, old)
        assertFalse(File(old, "apt").exists())
        assertTrue(File(cach, "apt/archives/partial").isDirectory)
    }

    @Test
    fun `a cache link to another directory leaves its apt alone`() {
        assumeSymlinks()
        val cache = folder.newFolder("cache")
        val foreign = folder.newFolder("project")
        File(foreign, "apt").mkdirs()
        File(foreign, "apt/work").writeText("mine")
        val cach = File(folder.root, "cach")
        Files.createSymbolicLink(cach.toPath(), foreign.toPath())

        stageAptCache(cach, cache)

        assertEquals("mine", File(foreign, "apt/work").readText())
        assertFalse(Files.isSymbolicLink(cach.toPath()))
        assertTrue(File(cach, "apt/archives/partial").isDirectory)
    }

    @Test
    fun `a cache directory keeps what is in it`() {
        val cach = folder.newFolder("cach")
        File(cach, "apt/archives").mkdirs()
        File(cach, "apt/archives/x.deb").writeText("deb")
        stageAptCache(cach, folder.newFolder("cache"))
        assertTrue(File(cach, "apt/archives/x.deb").isFile)
        assertTrue(File(cach, "apt/archives/partial").isDirectory)
    }

    @Test
    fun `glibc's hosts is linked to the agent's when the path is free`() {
        assumeSymlinks()
        val prefix = folder.newFolder("usr")
        File(prefix, "glibc/etc").mkdirs()
        val hosts = File(folder.root, "hosts").apply { writeText("127.0.0.1 localhost\n") }
        linkGlibcHosts(prefix, hosts)
        assertEquals(hosts.toPath(), Files.readSymbolicLink(File(prefix, "glibc/etc/hosts").toPath()))
    }

    @Test
    fun `glibc's own hosts is left alone`() {
        val prefix = folder.newFolder("usr")
        val own = File(prefix, "glibc/etc/hosts").apply { parentFile.mkdirs(); writeText("mine\n") }
        linkGlibcHosts(prefix, File(folder.root, "hosts"))
        assertEquals("mine\n", own.readText())
    }

    @Test
    fun `no glibc, no hosts`() {
        val prefix = folder.newFolder("usr")
        linkGlibcHosts(prefix, File(folder.root, "hosts"))
        assertFalse(File(prefix, "glibc").exists())
    }

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

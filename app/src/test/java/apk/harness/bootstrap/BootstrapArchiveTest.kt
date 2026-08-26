package apk.harness.bootstrap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a symlink line splits on the leftwards arrow`() {
        assertEquals(
            listOf(SymlinkEntry(target = "coreutils", linkPath = "./bin/comm")),
            parseSymlinks("coreutils←./bin/comm\n"),
        )
    }

    @Test
    fun `an absolute target is kept as written`() {
        assertEquals(
            listOf(
                SymlinkEntry(
                    target = "/data/data/com.termux/files/usr/share/x",
                    linkPath = "./etc/x",
                ),
            ),
            parseSymlinks("/data/data/com.termux/files/usr/share/x←./etc/x\n"),
        )
    }

    @Test
    fun `a line without an arrow is skipped rather than guessed at`() {
        assertEquals(emptyList<SymlinkEntry>(), parseSymlinks("bin/sh->bin/bash\n\n"))
    }

    @Test
    fun `extraction reports one step per entry and writes the bytes`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("bin/hello"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("etc/"))
            zip.closeEntry()
        }
        val into = folder.newFolder("usr")
        var steps = 0
        val count = extractArchive(ByteArrayInputStream(bytes.toByteArray()), into) { steps++ }
        assertEquals(2, count)
        assertEquals(2, steps)
        assertEquals("hi", File(into, "bin/hello").readText())
        assertTrue(File(into, "etc").isDirectory)
    }

    @Test
    fun `the symlink list describes the tree and is not written into it`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(SYMLINKS_NAME))
            zip.write("coreutils←./bin/comm\n".toByteArray())
            zip.closeEntry()
        }
        val into = folder.newFolder("usr")
        assertEquals(0, extractArchive(ByteArrayInputStream(bytes.toByteArray()), into) {})
        assertFalse(File(into, SYMLINKS_NAME).exists())
    }

    @Test
    fun `an entry escaping the destination is refused`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped"))
            zip.write("no".toByteArray())
            zip.closeEntry()
        }
        val into = folder.newFolder("usr")
        val thrown = try {
            extractArchive(ByteArrayInputStream(bytes.toByteArray()), into) {}
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(thrown != null)
    }
}

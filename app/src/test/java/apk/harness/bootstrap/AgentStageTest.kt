package apk.harness.bootstrap

import java.io.File
import java.nio.file.FileSystems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentStageTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the session runs the packaged loader and preloads the router`() {
        val libraries = File("/data/app/x/lib/x86_64")
        assertEquals(
            mapOf(
                "HARNESS_LOADER" to File(libraries, "libmuslloader.so").absolutePath,
                "HARNESS_TERMUX_EXEC" to File(libraries, "libtermuxexec.so").absolutePath,
                "LD_PRELOAD" to File(libraries, "libtermuxexec.so").absolutePath,
            ),
            libraryEnvironment(libraries),
        )
    }

    @Test
    fun `the session names no tracer, which the router adds`() {
        assertFalse(libraryEnvironment(File("/lib")).containsKey("HARNESS_SHIM"))
    }

    @Test
    fun `a start deletes the loader copies and keeps the agent`() {
        val stage = folder.newFolder("claude")
        val binary = File(stage, "claude").apply { writeText("agent") }
        val version = File(stage, "VERSION").apply { writeText("1\n") }
        val copies = listOf("ld-musl-aarch64.so.1", "ld-musl-x86_64.so.1")
            .map { File(stage, it).apply { writeText("loader") } }

        refreshStage(stage, binary)

        for (copy in copies) assertFalse(copy.name, copy.exists())
        assertEquals("agent", binary.readText())
        assertTrue(version.isFile)
    }

    @Test
    fun `a start makes the agent executable`() {
        // NTFS reports every readable file as executable, so this cannot fail there.
        assumeTrue(
            "needs a filesystem that records an execute bit",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val stage = folder.newFolder("claude")
        val binary = File(stage, "claude").apply { writeText("agent") }
        binary.setExecutable(false, false)

        refreshStage(stage, binary)

        assertTrue(binary.canExecute())
    }

    @Test
    fun `a start with nothing staged changes nothing`() {
        val stage = File(folder.root, "claude")
        refreshStage(stage, File(stage, "claude"))
        assertFalse(stage.exists())
    }

    @Test
    fun `a data spelling is answered with the user spelling beside it`() {
        assertEquals(
            listOf("/data/data/apk.harness/files", "/data/user/0/apk.harness/files"),
            bothSpellings("apk.harness", "/data/data/apk.harness/files"),
        )
    }

    @Test
    fun `a user spelling is answered with the data spelling beside it`() {
        assertEquals(
            listOf("/data/user/0/apk.harness/files", "/data/data/apk.harness/files"),
            bothSpellings("apk.harness", "/data/user/0/apk.harness/files"),
        )
    }

    @Test
    fun `a nested directory keeps everything under the package`() {
        assertEquals(
            listOf("/data/data/apk.harness/cache/x", "/data/user/0/apk.harness/cache/x"),
            bothSpellings("apk.harness", "/data/data/apk.harness/cache/x"),
        )
    }

    @Test
    fun `a directory that is neither is answered with itself alone`() {
        assertEquals(
            listOf("/storage/emulated/0/Download"),
            bothSpellings("apk.harness", "/storage/emulated/0/Download"),
        )
    }

    @Test
    fun `a sibling of the package directory is not mistaken for it`() {
        assertEquals(
            listOf("/data/data/apk.harnessing/files"),
            bothSpellings("apk.harness", "/data/data/apk.harnessing/files"),
        )
    }
}

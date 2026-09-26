package apk.harness.bootstrap

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.FileSystems
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomePayloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    // One nested tree standing in for an APK's assets. A directory answers with
    // its children and a file answers with nothing, which is what AssetManager
    // does and what the walk reads as the difference between them.
    private val files = mapOf(
        "home/.claude/CLAUDE.md" to "the environment",
        "home/.claude/skills/harness-userland/SKILL.md" to "the userland",
        "seed/.claude/settings.local.json" to "{}",
        "agent.sh" to "the invocation",
    )

    private fun list(path: String): Array<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .toTypedArray()
    }

    private fun open(path: String): InputStream =
        ByteArrayInputStream(files.getValue(path).toByteArray())

    @Test
    fun `entries are found through a nested tree`() {
        assertEquals(
            listOf(".claude/CLAUDE.md", ".claude/skills/harness-userland/SKILL.md"),
            assetEntries(::list, "home").sorted(),
        )
    }

    @Test
    fun `copying makes the directories the entries name`() {
        val into = folder.newFolder()
        val written = copyAssets(assetEntries(::list, "home"), ::open, "home", into, overwrite = true)

        assertEquals(2, written)
        assertEquals("the environment", File(into, ".claude/CLAUDE.md").readText())
        assertTrue(File(into, ".claude/skills/harness-userland/SKILL.md").isFile)
    }

    @Test
    fun `overwrite replaces what is there and the other rule does not`() {
        val into = folder.newFolder()
        val target = File(into, ".claude/CLAUDE.md").apply {
            parentFile!!.mkdirs()
            writeText("edited on the device")
        }

        assertEquals(
            0,
            copyAssets(listOf(".claude/CLAUDE.md"), ::open, "home", into, overwrite = false),
        )
        assertEquals("edited on the device", target.readText())

        assertEquals(
            1,
            copyAssets(listOf(".claude/CLAUDE.md"), ::open, "home", into, overwrite = true),
        )
        assertEquals("the environment", target.readText())
    }

    private fun stage(into: File): List<File> {
        val backups = mutableListOf<File>()
        stageScripts(listOf("agent.sh"), ::open, into) { backups += it }
        return backups
    }

    @Test
    fun `a missing script is written and made executable`() {
        val into = folder.newFolder()

        assertEquals(emptyList<File>(), stage(into))

        val script = File(into, "agent.sh")
        assertEquals("the invocation", script.readText())
        assertTrue(script.canExecute())
        assertFalse(File(into, "agent.sh.tmp").exists())
    }

    @Test
    fun `the temporary copy is executable before it is renamed into place`() {
        // A file named by a directory pins the rename to fail, which leaves the
        // temporary copy behind to be inspected.
        assumeTrue(
            "needs a filesystem that records an execute bit",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val into = folder.newFolder()
        File(into, "agent.sh/occupied").mkdirs()

        runCatching { stage(into) }

        assertTrue(File(into, "agent.sh.tmp").canExecute())
    }

    @Test
    fun `a script matching the shipped one is rewritten with no backup`() {
        val into = folder.newFolder()
        val script = File(into, "agent.sh").apply { writeText("the invocation") }
        script.setExecutable(false, false)

        assertEquals(emptyList<File>(), stage(into))

        assertEquals("the invocation", script.readText())
        assertTrue(script.canExecute())
        assertFalse(File(into, "agent.sh.bak").exists())
    }

    @Test
    fun `an edited script is replaced and kept as the backup`() {
        val into = folder.newFolder()
        File(into, "agent.sh").writeText("edited on the device")
        File(into, "agent.sh.bak").writeText("an older edit")

        assertEquals(listOf(File(into, "agent.sh.bak")), stage(into))

        assertEquals("the invocation", File(into, "agent.sh").readText())
        assertEquals("edited on the device", File(into, "agent.sh.bak").readText())
    }

    @Test
    fun `an edit already kept as the backup is not reported again`() {
        val into = folder.newFolder()
        File(into, "agent.sh").writeText("edited on the device")
        val backup = File(into, "agent.sh.bak").apply { writeText("edited on the device") }
        val bytes = backup.readBytes()

        assertEquals(emptyList<File>(), stage(into))

        assertEquals("the invocation", File(into, "agent.sh").readText())
        assertArrayEquals(bytes, backup.readBytes())
    }

    @Test
    fun `a matching stamp is the only thing that skips the copy`() {
        val stamp = File(folder.newFolder(), "PAYLOAD")

        assertTrue(payloadDue(stamp, "1700000000000"))

        stamp.writeText("1700000000000\n")
        assertFalse(payloadDue(stamp, "1700000000000"))
        assertTrue(payloadDue(stamp, "1800000000000"))
    }
}

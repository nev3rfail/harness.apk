package apk.harness.bootstrap

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a matching stamp is the only thing that skips the copy`() {
        val stamp = File(folder.newFolder(), "PAYLOAD")

        assertTrue(payloadDue(stamp, "1700000000000"))

        stamp.writeText("1700000000000\n")
        assertFalse(payloadDue(stamp, "1700000000000"))
        assertTrue(payloadDue(stamp, "1800000000000"))
    }
}

package apk.harness

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrustTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun accepted(config: String, path: String): Boolean =
        JSONObject(config)
            .getJSONObject("projects")
            .getJSONObject(path)
            .getBoolean("hasTrustDialogAccepted")

    @Test
    fun `a project the config has never heard of is added`() {
        val updated = withTrustedProject("""{"hasCompletedOnboarding":true}""", "/work")

        assertTrue(accepted(updated!!, "/work"))
    }

    @Test
    fun `what the config already says is kept`() {
        val updated = withTrustedProject(
            """{"hasCompletedOnboarding":true,"projects":{"/other":{"allowedTools":["Bash"]}}}""",
            "/work",
        )

        val root = JSONObject(updated!!)
        assertTrue(root.getBoolean("hasCompletedOnboarding"))
        assertEquals(
            "Bash",
            root.getJSONObject("projects").getJSONObject("/other")
                .getJSONArray("allowedTools").getString(0),
        )
        assertTrue(accepted(updated, "/work"))
    }

    @Test
    fun `a project that is already trusted needs no write`() {
        val config = """{"projects":{"/work":{"hasTrustDialogAccepted":true}}}"""

        assertNull(withTrustedProject(config, "/work"))
    }

    @Test
    fun `a project the config knows for other reasons keeps them`() {
        val updated = withTrustedProject(
            """{"projects":{"/work":{"numStartups":7}}}""",
            "/work",
        )

        val project = JSONObject(updated!!).getJSONObject("projects").getJSONObject("/work")
        assertEquals(7, project.getInt("numStartups"))
        assertTrue(project.getBoolean("hasTrustDialogAccepted"))
    }

    @Test
    fun `the file is written and read back`() {
        val config = folder.newFile("claude.json")
        config.writeText("""{"hasCompletedOnboarding":true}""")

        trustProject(config, "/work")

        assertTrue(accepted(config.readText(), "/work"))
    }

    @Test
    fun `nothing is written when the answer is already there`() {
        val config = folder.newFile("claude.json")
        config.writeText("""{"projects":{"/work":{"hasTrustDialogAccepted":true}}}""")
        val before = config.lastModified()

        trustProject(config, "/work")

        assertEquals(before, config.lastModified())
    }

    @Test
    fun `a home with no config gets one`() {
        val config = File(folder.root, "absent.json")

        trustProject(config, "/work")

        assertTrue(accepted(config.readText(), "/work"))
    }

    @Test
    fun `text that does not parse is left alone`() {
        assertNull(withTrustedProject("""{"hasCompletedOnboarding":tr""", "/work"))
    }

    @Test
    fun `an empty config is not something to write over`() {
        assertNull(withTrustedProject("", "/work"))
    }

    @Test
    fun `a file this cannot read is not rewritten`() {
        val config = folder.newFile("config.json")
        config.writeText("""{"oauthAccount":{"emailAddress":"a@b.c"},"projec""")

        trustProject(config, "/work")

        assertEquals("""{"oauthAccount":{"emailAddress":"a@b.c"},"projec""", config.readText())
    }

    @Test
    fun `a config holding nothing gains the section and the path`() {
        assertEquals(
            "[safe]\n\tdirectory = \"/storage/emulated/0/notes\"\n",
            withSafeDirectory("", "/storage/emulated/0/notes"),
        )
    }

    @Test
    fun `a config holding other settings gains an entry after them`() {
        val config = "[user]\n\tname = someone\n"

        assertEquals(
            config + "[safe]\n\tdirectory = \"/work\"\n",
            withSafeDirectory(config, "/work"),
        )
    }

    @Test
    fun `a config whose last line has no break gains one`() {
        assertEquals(
            "[user]\n\tname = someone\n[safe]\n\tdirectory = \"/work\"\n",
            withSafeDirectory("[user]\n\tname = someone", "/work"),
        )
    }

    @Test
    fun `a path already named safe is not named twice`() {
        assertNull(withSafeDirectory("[safe]\n\tdirectory = \"/work\"\n", "/work"))
        assertNull(withSafeDirectory("[safe]\n\tdirectory = /work\n", "/work"))
        assertNull(withSafeDirectory("[safe]\ndirectory=/work\n", "/work"))
    }

    @Test
    fun `a path that is the prefix of one already named is named itself`() {
        assertEquals(
            "[safe]\n\tdirectory = \"/work/inner\"\n[safe]\n\tdirectory = \"/work\"\n",
            withSafeDirectory("[safe]\n\tdirectory = \"/work/inner\"\n", "/work"),
        )
    }

    @Test
    fun `a directory belonging to another section is not this one`() {
        assertEquals(
            "[gui]\n\tdirectory = /work\n[safe]\n\tdirectory = \"/work\"\n",
            withSafeDirectory("[gui]\n\tdirectory = /work\n", "/work"),
        )
    }

    @Test
    fun `a section after this one ends it`() {
        assertEquals(
            "[safe]\n\tdirectory = \"/a\"\n[gui]\n\tdirectory = /work\n" +
                "[safe]\n\tdirectory = \"/work\"\n",
            withSafeDirectory("[safe]\n\tdirectory = \"/a\"\n[gui]\n\tdirectory = /work\n", "/work"),
        )
    }

    @Test
    fun `a header carrying a comment is still the section it names`() {
        assertNull(withSafeDirectory("[safe] # picked folders\n\tdirectory = \"/work\"\n", "/work"))
    }

    @Test
    fun `an entry that is commented out names nothing`() {
        assertEquals(
            "[safe]\n#\tdirectory = /work\n[safe]\n\tdirectory = \"/work\"\n",
            withSafeDirectory("[safe]\n#\tdirectory = /work\n", "/work"),
        )
    }

    @Test
    fun `a path holding a comment character is written so that it reads back whole`() {
        val written = withSafeDirectory("", "/storage/emulated/0/Notes #1")

        assertEquals("[safe]\n\tdirectory = \"/storage/emulated/0/Notes #1\"\n", written)
        // Which is the entry this reads back as already there, so a second
        // session in that folder appends nothing.
        assertNull(withSafeDirectory(written!!, "/storage/emulated/0/Notes #1"))
    }

    @Test
    fun `a bare value ends where its comment begins`() {
        assertNull(withSafeDirectory("[safe]\n\tdirectory = /work ; picked\n", "/work"))
    }

    @Test
    fun `a quoted value ends at its quote, whatever follows`() {
        assertNull(withSafeDirectory("[safe]\n\tdirectory = \"/work\" # picked\n", "/work"))
        assertNull(withSafeDirectory("[safe]\n\tdirectory = \"/work\" ; picked\n", "/work"))
    }

    @Test
    fun `an entry on the header's own line belongs to that section`() {
        assertNull(withSafeDirectory("[safe] directory = /work\n", "/work"))
    }

    @Test
    fun `a path holding what this format escapes reads back as itself`() {
        val awkward = """/storage/emulated/0/a"b\c"""

        val written = withSafeDirectory("", awkward)

        assertEquals("[safe]\n\tdirectory = \"/storage/emulated/0/a\\\"b\\\\c\"\n", written)
        assertNull(withSafeDirectory(written!!, awkward))
    }

    @Test
    fun `a path holding a line break is written as one line`() {
        val broken = "/storage/emulated/0/we\nird"

        val written = withSafeDirectory("", broken)

        // One entry, two lines: the section and the value. A raw break inside
        // the value would be a third, which this format reads as an error.
        assertEquals(2, written!!.trim().lines().size)
        assertNull(withSafeDirectory(written, broken))
    }

    @Test
    fun `a space inside a quoted value is part of the path`() {
        val spaced = "/storage/emulated/0/notes "

        val written = withSafeDirectory("", spaced)

        assertNull(withSafeDirectory(written!!, spaced))
        assertEquals("[safe]\n\tdirectory = \"/storage/emulated/0/notes \"\n", written)
    }

    @Test
    fun `the git config is written and read back`() {
        val config = File(folder.root, ".gitconfig")

        markSafeDirectory(config, "/work")

        assertEquals("[safe]\n\tdirectory = \"/work\"\n", config.readText())
    }

    @Test
    fun `naming a path safe a second time leaves the config alone`() {
        val config = File(folder.root, ".gitconfig")
        markSafeDirectory(config, "/work")

        markSafeDirectory(config, "/work")

        assertEquals("[safe]\n\tdirectory = \"/work\"\n", config.readText())
    }
}

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
}

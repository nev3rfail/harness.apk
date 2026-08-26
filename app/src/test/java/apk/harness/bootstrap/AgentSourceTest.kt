package apk.harness.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSourceTest {

    // Trimmed to the two platforms the app installs plus one it does not, which
    // is what makes "picks the right entry" a claim rather than a tautology.
    private val manifest = """
        {
          "version": "9.9.9",
          "platforms": {
            "linux-x64": { "checksum": "${"a".repeat(64)}", "size": 1 },
            "linux-arm64-musl": { "checksum": "${"b".repeat(64)}", "size": 240072248 },
            "linux-x64-musl": { "checksum": "${"c".repeat(64)}", "size": 241667960 }
          }
        }
    """.trimIndent()

    @Test
    fun `each shipped abi names a musl platform and a loader`() {
        assertEquals("linux-arm64-musl", platformFor("arm64-v8a"))
        assertEquals("linux-x64-musl", platformFor("x86_64"))
        assertEquals("ld-musl-aarch64.so.1", loaderFor("arm64-v8a"))
        assertEquals("ld-musl-x86_64.so.1", loaderFor("x86_64"))
    }

    @Test
    fun `an abi with no renderer has no agent`() {
        assertEquals(null, platformFor("armeabi-v7a"))
        assertEquals(null, loaderFor("armeabi-v7a"))
    }

    @Test
    fun `the manifest entry read is the one asked for`() {
        val release = parseManifest(manifest, "9.9.9", "linux-arm64-musl")
        assertEquals("b".repeat(64), release.sha256)
        assertEquals(240072248L, release.bytes)
        assertEquals("9.9.9", release.version)
        assertEquals("linux-arm64-musl", release.platform)
    }

    @Test
    fun `a manifest for another version is refused`() {
        val thrown = try {
            parseManifest(manifest, "9.9.8", "linux-x64-musl")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("$thrown", thrown?.message?.contains("9.9.9") == true)
    }

    @Test
    fun `a platform the release does not carry is refused`() {
        val thrown = try {
            parseManifest(manifest, "9.9.9", "linux-riscv64-musl")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("$thrown", thrown?.message?.contains("linux-riscv64-musl") == true)
    }

    @Test
    fun `the urls name the pinned version`() {
        assertTrue(manifestUrl(AGENT_VERSION).endsWith("/$AGENT_VERSION/manifest.json"))
        assertEquals(
            "https://downloads.claude.ai/claude-code-releases/1.2.3/linux-x64-musl/claude",
            agentUrl("1.2.3", "linux-x64-musl"),
        )
    }
}

package apk.harness.bootstrap

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hi = "hi".toByteArray()
    private val hiSha = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"

    @Test
    fun `each shipped abi names an asset`() {
        assertEquals("bootstrap-aarch64.zip", releaseFor("arm64-v8a")?.asset)
        assertEquals("bootstrap-x86_64.zip", releaseFor("x86_64")?.asset)
    }

    @Test
    fun `an abi with no renderer has no bootstrap`() {
        assertEquals(null, releaseFor("armeabi-v7a"))
    }

    @Test
    fun `the plus in the release tag is escaped in the url`() {
        val url = downloadUrl(releaseFor("arm64-v8a")!!)
        assertTrue(url, url.contains("%2Bapt.android-7/bootstrap-aarch64.zip"))
        assertFalse(url, url.contains("+"))
    }

    @Test
    fun `a checksum is lowercase hex of the whole file`() {
        val file = folder.newFile("hi.bin").apply { writeBytes(hi) }
        assertEquals(hiSha, sha256(file))
    }

    @Test
    fun `a verified download is renamed into place and progress is reported`() {
        val into = folder.newFolder("cache")
        var last = 0L
        val got = fetchVerified({ ByteArrayInputStream(hi) }, "t.zip", hiSha, into) { last = it }
        assertEquals(File(into, "t.zip"), got)
        assertEquals("hi", got.readText())
        assertEquals(hi.size.toLong(), last)
        assertFalse(File(into, "t.zip.tmp").exists())
    }

    @Test
    fun `a mismatched checksum leaves nothing behind`() {
        val into = folder.newFolder("cache")
        val thrown = try {
            fetchVerified({ ByteArrayInputStream(hi) }, "t.zip", "0".repeat(64), into) {}
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(thrown != null)
        assertFalse(File(into, "t.zip").exists())
        assertFalse(File(into, "t.zip.tmp").exists())
    }

    @Test
    fun `an archive already present and correct is not fetched again`() {
        val into = folder.newFolder("cache")
        File(into, "t.zip").writeBytes(hi)
        val got = fetchVerified({ error("must not be fetched") }, "t.zip", hiSha, into) {}
        assertEquals("hi", got.readText())
    }
}

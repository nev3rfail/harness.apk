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
    fun `the play bootstrap is pinned per abi`() {
        assertEquals(
            BootstrapRelease(
                asset = "bootstrap-aarch64.zip",
                sha256 = "8dce2a667b1d1e1de0b87f8228538e9035f286553cb7dbd4a35af191783d0a92",
                bytes = 24564124L,
                entries = 2863,
            ),
            releaseFor("arm64-v8a"),
        )
        assertEquals(
            BootstrapRelease(
                asset = "bootstrap-x86_64.zip",
                sha256 = "115b21acb995c2da36a66c3c76fa07e9b4c69cac851a80b4876f48468eec5b73",
                bytes = 24218072L,
                entries = 2864,
            ),
            releaseFor("x86_64"),
        )
    }

    @Test
    fun `the archive comes from the play release and then from the mirror`() {
        val release = releaseFor("x86_64")!!
        assertEquals(
            "https://github.com/termux-play-store/termux-packages/releases/download/" +
                "bootstrap-2026.09.20-r1/bootstrap-x86_64.zip",
            downloadUrl(release),
        )
        assertEquals(
            "https://github.com/nev3rfail/harness.apk/releases/download/" +
                "bootstrap-2026.09.20-r1/bootstrap-x86_64.zip",
            mirrorUrl(release),
        )
    }

    private val tiny = BootstrapRelease("t.zip", hiSha, 2L, 1)

    @Test
    fun `a failed upstream fetch falls back to the mirror`() {
        val into = folder.newFolder("cache")
        val asked = mutableListOf<String>()
        val got = fetchBootstrap(tiny, into, { url ->
            asked += url
            if (url == downloadUrl(tiny)) error("pruned") else ByteArrayInputStream(hi)
        }) {}
        assertEquals(listOf(downloadUrl(tiny), mirrorUrl(tiny)), asked)
        assertEquals("hi", got.readText())
    }

    @Test
    fun `a mismatched upstream archive falls back to the mirror`() {
        val into = folder.newFolder("cache")
        val got = fetchBootstrap(tiny, into, { url ->
            ByteArrayInputStream(if (url == downloadUrl(tiny)) "no".toByteArray() else hi)
        }) {}
        assertEquals("hi", got.readText())
    }

    @Test
    fun `a working upstream leaves the mirror alone`() {
        val into = folder.newFolder("cache")
        val asked = mutableListOf<String>()
        fetchBootstrap(tiny, into, { asked += it; ByteArrayInputStream(hi) }) {}
        assertEquals(listOf(downloadUrl(tiny)), asked)
    }

    @Test
    fun `both failing reports the mirror and carries the upstream failure`() {
        val into = folder.newFolder("cache")
        val thrown = try {
            fetchBootstrap(tiny, into, { url -> error(url) }) {}
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertEquals(mirrorUrl(tiny), thrown?.message)
        assertEquals(downloadUrl(tiny), thrown?.suppressed?.single()?.message)
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

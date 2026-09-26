package apk.harness.bootstrap

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapRelocationTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `both pairs are the same length as what they replace`() {
        for (pair in relocationPairs("apk.harness")) {
            assertEquals(pair.old.length, pair.new.length)
        }
    }

    @Test
    fun `the length both sides have to hit is 27 bytes`() {
        // The measurement the whole scheme rests on: an eleven-character id spends
        // one byte more than `com.termux` on itself and wins it back on the
        // directory below. Asserted as a number so a pair that stops fitting fails
        // here rather than in a stripped binary.
        for (pair in relocationPairs("apk.harness")) {
            assertEquals(27, pair.old.length)
            assertEquals(27, pair.new.length)
        }
    }

    @Test
    fun `the pairs name the tree and the cache`() {
        assertEquals(
            listOf(
                RelocationPair("/data/data/com.termux/files", "/data/data/apk.harness/root"),
                RelocationPair("/data/data/com.termux/cache", "/data/data/apk.harness/cach"),
            ),
            relocationPairs("apk.harness"),
        )
    }

    @Test
    fun `an id of the wrong length is refused rather than padded`() {
        for (id in listOf("short", "dev.harness.too.long", "")) {
            assertThrows(id, IllegalArgumentException::class.java) { relocationPairs(id) }
        }
    }

    @Test
    fun `the iterating channel fits too`() {
        assertEquals(
            RelocationPair("/data/data/com.termux/files", "/data/data/dev.harness/root"),
            relocationPairs("dev.harness").first(),
        )
    }

    @Test
    fun `a manifest loses the bare data directory and keeps the rest`() {
        val info = folder.newFolder("info")
        File(info, "bash.list").writeText(
            """
            /data/data
            /data/data/com.termux
            /data/data/apk.harness/root/usr/bin/bash
            """.trimIndent() + "\n"
        )
        assertEquals(1, rewriteManifests(info, "apk.harness"))
        assertEquals(
            """
            /data/data
            /data/data/apk.harness
            /data/data/apk.harness/root/usr/bin/bash
            """.trimIndent() + "\n",
            File(info, "bash.list").readText(),
        )
    }

    @Test
    fun `a manifest with nothing to fix is left alone and not counted`() {
        val info = folder.newFolder("info")
        val untouched = File(info, "sed.list")
        val text = "/data/data/apk.harness/root/usr/bin/sed\n"
        untouched.writeText(text)
        // Backdated by a day, so a rewrite -- which stamps the file with now --
        // separates from this by far more than any filesystem's timestamp
        // granularity can round away.
        untouched.setLastModified(System.currentTimeMillis() - 86_400_000L)
        val before = untouched.lastModified()
        assertEquals(0, rewriteManifests(info, "apk.harness"))
        assertEquals(before, untouched.lastModified())
        assertEquals(text, untouched.readText())
    }

    @Test
    fun `only list files are considered`() {
        val info = folder.newFolder("info")
        File(info, "bash.md5sums").writeText("abc  /data/data/com.termux\n")
        assertEquals(0, rewriteManifests(info, "apk.harness"))
    }
}

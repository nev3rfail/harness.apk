package apk.harness.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    private val mb = 1024L * 1024

    @Test
    fun `a path in the name keeps its last part`() {
        assertEquals("c.txt", safeName("a/b/c.txt"))
        assertEquals("b.txt", safeName("a\\b.txt"))
        assertEquals("x", safeName("../../x"))
    }

    @Test
    fun `a leading dot is replaced`() {
        assertEquals("_bashrc", safeName(".bashrc"))
        assertEquals("_.", safeName(".."))
        assertEquals("_", safeName("."))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("abc.txt", safeName("a\u0000b\u0007c\u007f.txt"))
    }

    @Test
    fun `a long name is cut to 128 bytes without breaking a character`() {
        val name = safeName("é".repeat(200) + ".txt")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= NAME_BYTES)
        assertEquals("é".repeat(64), name)
    }

    @Test
    fun `nothing left is shared`() {
        assertEquals("shared", safeName(""))
        assertEquals("shared", safeName(null))
        assertEquals("shared", safeName("a/"))
        assertEquals("shared", safeName("\u0001"))
    }

    @Test
    fun `a name taken in the directory gets a number before its extension`() {
        assertEquals("a.txt", uniqueName("a.txt", emptySet()))
        assertEquals("a-2.txt", uniqueName("a.txt", setOf("a.txt")))
        assertEquals("a-3.txt", uniqueName("a.txt", setOf("a.txt", "a-2.txt")))
        assertEquals("README-2", uniqueName("README", setOf("README")))
        assertEquals("_x-2", uniqueName("_x", setOf("_x")))
    }

    @Test
    fun `a file over 100 MB is refused before the copy`() {
        assertEquals(CopyPlan(listOf(0, 2), listOf(1)), planCopies(listOf(mb, 101 * mb, mb)))
    }

    @Test
    fun `the file crossing 500 MB and every one after it are refused`() {
        // Five of 90 MB are 450 MB; the sixth crosses 500 MB, and the small one after it is refused too.
        assertEquals(CopyPlan((0 until 5).toList(), listOf(5, 6)), planCopies(List(6) { 90 * mb } + mb))
    }

    @Test
    fun `an unknown size is copied and checked while copying`() {
        assertEquals(CopyPlan(listOf(0), emptyList()), planCopies(listOf(null)))
        // A negative size is unknown and takes nothing off the share's total.
        assertEquals(CopyPlan(listOf(0, 1), listOf(2)), planCopies(listOf(-SHARE_LIMIT, 90 * mb, SHARE_LIMIT)))
        assertNull(limitCrossed(FILE_LIMIT, FILE_LIMIT))
        assertEquals(Limit.File, limitCrossed(FILE_LIMIT + 1, FILE_LIMIT + 1))
        assertEquals(Limit.Share, limitCrossed(mb, SHARE_LIMIT + 1))
    }
}

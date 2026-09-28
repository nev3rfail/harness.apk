package apk.harness.agents

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun environ(vararg entries: String) =
        entries.joinToString("") { "$it\u0000" }.toByteArray()

    @Test
    fun `a canonical UUID is a session id`() {
        assertTrue(isSessionId("da24d711-40d4-44c3-85b4-9512de5a54c5"))
        assertTrue(isSessionId("DA24D711-40D4-44C3-85B4-9512DE5A54C5"))
    }

    @Test
    fun `anything else is not a session id`() {
        assertFalse(isSessionId(""))
        assertFalse(isSessionId("s"))
        assertFalse(isSessionId("1-1-1-1-1"))
        assertFalse(isSessionId("da24d711-40d4-44c3-85b4-9512de5a54c5 --settings x"))
        assertFalse(isSessionId("--dangerously-skip-permissions"))
        assertFalse(isSessionId("da24d711-40d4-44c3-85b4-9512de5a54c5\n"))
    }

    @Test
    fun `the tab is read out of an environment among other entries`() {
        assertEquals("t1", tagIn(environ("PATH=/bin", "HARNESS_TAB=t1", "HOME=/h")))
    }

    @Test
    fun `an environment naming no tab has none`() {
        assertNull(tagIn(environ("PATH=/bin", "HARNESS_TABS=x", "XHARNESS_TAB=y")))
        assertNull(tagIn(ByteArray(0)))
        assertNull(tagIn(environ("HARNESS_TAB=")))
    }

    @Test
    fun `an environment with no final NUL still ends its last entry`() {
        assertEquals("t1", tagIn("A=b\u0000HARNESS_TAB=t1".toByteArray()))
    }

    @Test
    fun `an environment that cannot be read is told apart from one naming no tab`() {
        val root = folder.newFolder("proc")
        File(root, "42").mkdirs()
        File(root, "42/environ").writeBytes(environ("PATH=/bin"))
        File(root, "43").mkdirs()
        File(root, "43/environ").writeBytes(environ("HARNESS_TAB=t"))
        val proc = ProcTable(root)

        assertEquals(TagRead.Unreadable, proc.environTag(41))
        assertEquals(TagRead.Absent, proc.environTag(42))
        assertEquals(TagRead.Tag("t"), proc.environTag(43))
    }

    @Test
    fun `a stat line is read from after its command name`() {
        val line = "41 (a (b) c) S 1 40 39 34816 44 4194560 0 0 0 0 7 3 5 2 20 0 1 0 6577951 0 0\n"
        assertEquals(
            ProcStat(state = 'S', ppid = 1, pgrp = 40, session = 39, tpgid = 44, cpu = 17, procStart = 6577951),
            parseStat(line),
        )
    }

    @Test
    fun `a stat line names its parent`() {
        assertEquals(777, parseStat("900 (ld-musl-x86_64.so.1) S 777 900 900 0 -1 0 0 0 0 0 1 1 0 0 20 0 1 0 12345 0 0")?.ppid)
    }

    @Test
    fun `a stat line cut short is nothing`() {
        assertNull(parseStat("41 (sh) S 1 40"))
        assertNull(parseStat(""))
    }

    @Test
    fun `the table lists pids and reads their stat`() {
        val root = folder.newFolder("proc")
        File(root, "41").mkdirs()
        File(root, "41/stat").writeText("41 (sh) T 1 41 41 0 41 0 0 0 0 0 1 1 0 0 20 0 1 0 900 0 0\n")
        File(root, "self").mkdirs()
        val proc = ProcTable(root)

        assertEquals(listOf(41), proc.pids())
        assertEquals('T', proc.stat(41)!!.state)
        assertNull(proc.stat(42))
    }
}

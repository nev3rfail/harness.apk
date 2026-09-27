package apk.harness.agents

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    /**
     * A `stat` line for [pid]: its state, group, session, terminal group, CPU
     * time split across the four fields, and a start time in clock ticks.
     */
    private fun process(
        root: File,
        pid: Int,
        state: Char = 'S',
        pgrp: Int = pid,
        session: Int = pid,
        tpgid: Int = pid,
        cpu: Long = 0,
        startTicks: Long = 0,
    ) {
        File(root, "$pid").mkdirs()
        val fields = listOf(
            "$state", "1", "$pgrp", "$session", "34816", "$tpgid", "0", "0", "0", "0", "0",
            "$cpu", "0", "0", "0", "20", "0", "1", "0", "$startTicks", "0", "0",
        )
        File(root, "$pid/stat").writeText("$pid (p) ${fields.joinToString(" ")}\n")
    }

    private fun record(pid: Int, status: String?, tag: String? = "t") =
        RunningSession(pid = pid, sessionId = id, status = status, tag = tag)

    private val tab = TabIdentity(tag = "t", leader = 10)

    private fun count(
        proc: File,
        roster: List<RunningSession>,
        samples: Map<String, Sample> = emptyMap(),
        now: Long = 1_000_000,
        tabs: List<TabIdentity> = listOf(tab),
    ) = workingTabs(tabs, roster, ProcTable(proc), samples, now)

    private fun working(result: WorkCount) = result.working.map { it.tag }

    @Test
    fun `a busy agent works and an idle or waiting one rests`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)
        process(proc, 20, pgrp = 10, session = 10, tpgid = 10)

        assertEquals(listOf("t"), working(count(proc, listOf(record(20, "busy")))))
        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, "idle")))))
        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, "waiting")))))
    }

    @Test
    fun `a record with no status works while its process is young, and not after`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)
        // Started at 990 s on the boot clock, in ticks of a hundredth of a second.
        process(proc, 20, pgrp = 10, session = 10, tpgid = 10, startTicks = 99_000)

        assertEquals(listOf("t"), working(count(proc, listOf(record(20, null)), now = 1_000_000)))
        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, null)), now = 1_060_000)))
    }

    @Test
    fun `a stopped agent rests whatever its record says`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)
        process(proc, 20, state = 'T', pgrp = 10, session = 10, tpgid = 10)

        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, "busy")))))
    }

    @Test
    fun `a zombie or dead agent rests whatever its record says`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)
        process(proc, 20, state = 'Z', pgrp = 10, session = 10, tpgid = 10)
        process(proc, 21, state = 'X', pgrp = 10, session = 10, tpgid = 10)

        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, "busy"), record(21, "busy")))))
    }

    @Test
    fun `a record carrying no current tab's tag is not counted`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)
        process(proc, 20)

        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, "busy", tag = "gone")))))
        assertEquals(emptyList<String>(), working(count(proc, listOf(record(20, "busy", tag = null)))))
    }

    @Test
    fun `a nested busy agent of the tab counts`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)
        process(proc, 20, pgrp = 10, session = 10, tpgid = 10)
        process(proc, 30, pgrp = 30, session = 10, tpgid = 10)

        val roster = listOf(record(20, "idle"), record(30, "busy"))
        assertEquals(listOf("t"), working(count(proc, roster)))
    }

    @Test
    fun `a dead process counts for nothing`() {
        val proc = folder.newFolder("proc")
        process(proc, 10)

        assertEquals(emptyList<String>(), working(count(proc, listOf(record(99, "busy")))))
    }

    @Test
    fun `a shell with no foreground job rests`() {
        val proc = folder.newFolder("proc")
        process(proc, 10, tpgid = 10)

        assertEquals(emptyList<String>(), working(count(proc, emptyList())))
    }

    @Test
    fun `a foreground job works until its second sample and then while its time rises`() {
        val proc = folder.newFolder("proc")
        process(proc, 10, tpgid = 40)
        process(proc, 40, pgrp = 40, session = 10, tpgid = 40, cpu = 100)

        val first = count(proc, emptyList(), now = 0)
        assertEquals(listOf("t"), working(first))

        process(proc, 40, pgrp = 40, session = 10, tpgid = 40, cpu = 300)
        val rising = count(proc, emptyList(), samples = first.samples, now = SAMPLE_GAP_MS)
        assertEquals(listOf("t"), working(rising))

        val still = count(proc, emptyList(), samples = rising.samples, now = 2 * SAMPLE_GAP_MS)
        assertEquals(emptyList<String>(), working(still))
    }

    @Test
    fun `a job whose children come and go works while its parent stands still`() {
        val proc = folder.newFolder("proc")
        process(proc, 10, tpgid = 40)
        process(proc, 40, pgrp = 40, session = 10, tpgid = 40, cpu = 5)
        process(proc, 41, pgrp = 40, session = 10, tpgid = 40, cpu = 50)
        val first = count(proc, emptyList(), now = 0)

        // The first child was reaped, its time now in the parent's reaped
        // children's; a second child has started.
        File(proc, "41").deleteRecursively()
        process(proc, 40, pgrp = 40, session = 10, tpgid = 40, cpu = 55)
        process(proc, 42, pgrp = 40, session = 10, tpgid = 40, cpu = 1)
        val next = count(proc, emptyList(), samples = first.samples, now = SAMPLE_GAP_MS)

        assertEquals(listOf("t"), working(next))
    }

    @Test
    fun `samples closer together than the gap keep the last decision`() {
        val proc = folder.newFolder("proc")
        process(proc, 10, tpgid = 40)
        process(proc, 40, pgrp = 40, session = 10, tpgid = 40, cpu = 100)
        val first = count(proc, emptyList(), now = 0)

        val soon = count(proc, emptyList(), samples = first.samples, now = SAMPLE_GAP_MS / 2)

        assertEquals(listOf("t"), working(soon))
        assertEquals(first.samples, soon.samples)
    }

    @Test
    fun `work holds the service and forgets when it rested`() {
        assertEquals(Hold.Awake to null, holdDecision(working = true, restingSince = 5, now = 100))
    }

    @Test
    fun `rest shorter than the grace waits out the rest of it`() {
        assertEquals(Hold.Wait(GRACE_MS) to 100L, holdDecision(working = false, restingSince = null, now = 100))
        assertEquals(Hold.Wait(GRACE_MS - 4_000) to 100L, holdDecision(false, restingSince = 100, now = 4_100))
    }

    @Test
    fun `rest as long as the grace stops the service`() {
        assertEquals(Hold.Stop to 100L, holdDecision(false, restingSince = 100, now = 100 + GRACE_MS))
    }

    @Test
    fun `work in the grace starts the wait over`() {
        val (_, since) = holdDecision(false, restingSince = null, now = 0)
        val (awake, cleared) = holdDecision(true, restingSince = since, now = 5_000)
        val (wait, _) = holdDecision(false, restingSince = cleared, now = 6_000)

        assertEquals(Hold.Awake, awake)
        assertEquals(Hold.Wait(GRACE_MS), wait)
    }

    @Test
    fun `an agent holding the terminal is decided by its record`() {
        val proc = folder.newFolder("proc")
        process(proc, 10, tpgid = 40)
        process(proc, 40, pgrp = 40, session = 10, tpgid = 40, cpu = 100)

        assertEquals(emptyList<String>(), working(count(proc, listOf(record(40, "idle")))))
    }
}

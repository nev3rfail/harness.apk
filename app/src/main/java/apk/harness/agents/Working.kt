package apk.harness.agents

/**
 * What the app's tabs are doing that needs the CPU: an agent on a turn, or a job
 * in a tab's shell that is getting somewhere.
 *
 * An agent at its prompt, a question waiting for the operator, an editor left
 * open in the shell -- none of these needs anything until the operator comes
 * back, and the app may be frozen through them.
 */

/** How long a record with no status yet counts as busy, after its process started. */
const val UNSTATED_MS = 60_000L

/** The shortest gap between two samples a shell job's progress is judged across. */
const val SAMPLE_GAP_MS = 10_000L

/**
 * The kernel's clock ticks per second, which `stat` counts start times in.
 * Android's is 100 on every ABI it ships.
 */
private const val TICKS_PER_SECOND = 100L

/** How long nothing works before the service stops. */
const val GRACE_MS = 10_000L

/** What the service does after a count. */
sealed interface Hold {
    /** Something works: stay, and hold the wake lock. */
    data object Awake : Hold

    /** Nothing works yet not for long enough: count again after [ms]. */
    data class Wait(val ms: Long) : Hold

    /** Nothing has worked for [GRACE_MS]: stop. */
    data object Stop : Hold
}

/**
 * The service's next step, from whether anything [working] and since when
 * nothing has ([restingSince], null while something works), at [now]. Answers
 * the step and the resting time to keep.
 *
 * A turn busy for a moment, a record read mid-rewrite, and the gap between one
 * turn and the next are shorter than the grace, so none of them stops and
 * restarts the service.
 */
fun holdDecision(working: Boolean, restingSince: Long?, now: Long): Pair<Hold, Long?> {
    if (working) return Hold.Awake to null
    val since = restingSince ?: now
    val rested = now - since
    return (if (rested >= GRACE_MS) Hold.Stop else Hold.Wait(GRACE_MS - rested)) to since
}

/**
 * One foreground job, as last sampled: its CPU time, its processes, when, and
 * what that sample decided.
 */
data class Sample(val cpu: Long, val pids: Set<Int>, val at: Long, val working: Boolean)

/** The tabs found working, and the job samples to carry into the next count. */
data class WorkCount(val working: List<TabIdentity>, val samples: Map<String, Sample>)

/**
 * The tabs of [tabs] that are working, by [roster], the process table [proc],
 * the job samples of the previous count, and the time [now] on the clock `stat`
 * start times are measured against (the time since boot).
 *
 * A tab works when a live record carrying its tag says `busy` -- any record, not
 * only its own agent's: an agent it left running in the background is work too
 * -- or says nothing yet within [UNSTATED_MS] of its process starting, and its
 * process is not stopped. `idle` and `waiting` are at rest.
 *
 * A tab also works while its shell is running a job that makes progress. The
 * job is the process group holding the terminal in the foreground, when that is
 * not the leader's own group. A group holding a process with a live record is an
 * agent, and its record has already decided. Any other group works while its CPU
 * time -- the processes' own and their reaped children's -- rose, or a process
 * joined it, between two samples at least [SAMPLE_GAP_MS] apart. A group seen
 * for the first time works until its second sample.
 */
fun workingTabs(
    tabs: List<TabIdentity>,
    roster: List<RunningSession>,
    proc: ProcTable,
    samples: Map<String, Sample>,
    now: Long,
): WorkCount {
    val next = HashMap<String, Sample>()
    val recorded = roster.mapTo(HashSet()) { it.pid }
    // Read once per count, and only when a shell has a job to judge.
    val table by lazy { proc.pids().mapNotNull { pid -> proc.stat(pid)?.let { pid to it } } }

    val working = tabs.filter { tab ->
        val agentBusy = roster.any { record -> record.tag == tab.tag && busy(record, proc, now) }
        val jobBusy = tab.leader?.let { leader ->
            shellJobWorking(tab.tag, leader, proc, { table }, recorded, samples, now, next)
        } == true
        agentBusy || jobBusy
    }
    return WorkCount(working, next)
}

/** States a process in which does nothing: stopped, traced-stopped, a zombie, dead. */
private val RESTING = setOf('T', 't', 'Z', 'X', 'x')

private fun busy(record: RunningSession, proc: ProcTable, now: Long): Boolean {
    val stat = proc.stat(record.pid) ?: return false
    if (stat.state in RESTING) return false
    return when (record.status) {
        "busy" -> true
        null -> now - stat.procStart * 1000 / TICKS_PER_SECOND < UNSTATED_MS
        else -> false
    }
}

private fun shellJobWorking(
    tag: String,
    leader: Int,
    proc: ProcTable,
    table: () -> List<Pair<Int, ProcStat>>,
    recorded: Set<Int>,
    samples: Map<String, Sample>,
    now: Long,
    next: MutableMap<String, Sample>,
): Boolean {
    val leaderStat = proc.stat(leader) ?: return false
    val group = leaderStat.tpgid
    if (group <= 0 || group == leaderStat.pgrp) return false

    val members = table().filter { (_, stat) -> stat.pgrp == group }
    if (members.isEmpty() || members.any { (pid, _) -> pid in recorded }) return false

    val key = "$tag:$group"
    val cpu = members.sumOf { (_, stat) -> stat.cpu }
    val pids = members.mapTo(HashSet()) { (pid, _) -> pid }
    val previous = samples[key]
    val sample = when {
        previous == null -> Sample(cpu, pids, now, working = true)
        now - previous.at < SAMPLE_GAP_MS -> previous
        else -> Sample(cpu, pids, now, working = cpu > previous.cpu || !previous.pids.containsAll(pids))
    }
    next[key] = sample
    return sample.working
}

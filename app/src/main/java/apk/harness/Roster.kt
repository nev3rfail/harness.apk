package apk.harness

import android.os.FileObserver
import android.util.Log
import apk.harness.agents.BACKENDS
import apk.harness.agents.ProcTable
import apk.harness.agents.RecordRead
import apk.harness.agents.RunningSession
import apk.harness.agents.TabIdentity
import apk.harness.agents.readRoster
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The running agents, as their own records describe them, for the whole
 * process.
 *
 * One per process rather than one per screen, because the service that keeps
 * the app awake reads it with no activity running. An observer on each
 * backend's roster directory re-reads the directory on every write, move and
 * delete in it, so a status change reaches every reader in the time a file
 * takes to close.
 *
 * A process that is killed leaves its record behind and makes no event, and the
 * kernel does not report to the observer an event it dropped. So the roster is
 * also read at the moments a death shows: a tab's terminal ending, a tab being
 * closed, the screen coming back, and the drawer opening.
 *
 * The tabs the current screen holds are named here too, so a reader with no
 * reference to the screen can tell this app's tabs from anything else.
 */
object Roster {
    private val _roster = MutableStateFlow<List<RunningSession>>(emptyList())
    val roster: StateFlow<List<RunningSession>> = _roster.asStateFlow()

    // A function rather than a list, because a tab's leader has a pid only once
    // its terminal has started, and the screen starts it when it draws it.
    @Volatile
    private var tabSource: () -> List<TabIdentity> = { emptyList() }

    private val proc = ProcTable()
    private val lock = Any()
    private var home: File? = null

    // Strong references: an observer nothing holds is collected, and a
    // collected observer stops watching without saying so.
    private val observers = mutableMapOf<File, FileObserver>()
    private val lastGood = mutableMapOf<File, MutableMap<String, RecordRead.Entry>>()

    // A process whose environment cannot be read belongs to no tab, and a
    // device that refuses the read would otherwise follow nothing in silence.
    private val unreadable = HashSet<Int>()

    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roster").apply { isDaemon = true }
    }

    /**
     * Starts watching [agentHome]'s rosters, and reads them once off the calling
     * thread. A second call changes nothing.
     */
    fun start(agentHome: File) {
        synchronized(lock) {
            if (home != null) return
            home = agentHome
        }
        background.execute {
            synchronized(lock) {
                for (backend in BACKENDS) backend.rosterDirectory(agentHome)?.let(::watch)
            }
            readNow()
        }
    }

    /** The tabs the current screen holds, as their processes know them. */
    fun tabs(): List<TabIdentity> = tabSource()

    private val _tabsChanged = MutableStateFlow(0L)

    /**
     * Counts every change the screen makes to its tabs -- one added, closed,
     * moved to another conversation, relabelled -- so a reader of [tabs] hears
     * of it without waiting for the next record.
     */
    val tabsChanged: StateFlow<Long> = _tabsChanged.asStateFlow()

    /** Says the tabs [tabs] reads have changed. */
    fun tabsChanged() {
        _tabsChanged.update { it + 1 }
    }

    /** Names where the tabs the screen holds are read from. */
    fun publishTabs(source: () -> List<TabIdentity>) {
        tabSource = source
    }

    /**
     * Takes back [source] when it is still the one named, so a screen that has
     * gone is neither read nor kept.
     */
    fun withdrawTabs(source: () -> List<TabIdentity>) {
        if (tabSource === source) tabSource = { emptyList() }
    }

    /**
     * Reads every roster on the calling thread, publishes what it read, and
     * answers it.
     *
     * For a decision that cannot act on a roster not yet published: a restore,
     * and the check before starting a conversation. The read and the publish
     * are one step under the lock, so a read never lands after a newer one.
     */
    fun readNow(): List<RunningSession> = synchronized(lock) {
        val agentHome = home ?: return emptyList()
        val read = BACKENDS.flatMap { backend ->
            val directory = backend.rosterDirectory(agentHome) ?: return@flatMap emptyList()
            readRoster(
                directory,
                backend::parseRecord,
                proc,
                lastGood.getOrPut(directory) { mutableMapOf() },
            )
        }
        reportUnreadable(read)
        _roster.value = read
        read
    }

    /** Reads and publishes on a thread of the roster's own. */
    fun refresh() {
        background.execute { readNow() }
    }

    private fun watch(directory: File) {
        if (!directory.mkdirs() && !directory.isDirectory) {
            Log.w(TAG, "$directory cannot be made, so its records are not heard")
        }
        observers.remove(directory)?.stopWatching()
        val observer = object : FileObserver(directory, MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                    // The directory itself went, and the watch went with it.
                    synchronized(lock) { watch(directory) }
                }
                readNow()
            }
        }
        observers[directory] = observer
        observer.startWatching()
    }

    private fun reportUnreadable(sessions: List<RunningSession>) {
        unreadable.retainAll(sessions.mapTo(HashSet()) { it.pid })
        for (session in sessions) {
            if (!session.environUnreadable || !unreadable.add(session.pid)) continue
            Log.w(TAG, "the environment of ${session.pid} cannot be read; it belongs to no tab")
        }
    }

    private const val TAG = "Roster"

    private const val MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
        FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF or
        FileObserver.MOVE_SELF
}

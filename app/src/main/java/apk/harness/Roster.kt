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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The running agents, as their own records describe them, for the whole
 * process.
 *
 * One per process rather than one per screen, because the service that keeps
 * the app awake reads it with no activity running. The records are heard rather
 * than polled: an observer on each backend's roster directory re-reads the
 * directory on every write, move and delete in it, so nothing reads on a timer
 * and a status change reaches every reader in the time a file takes to close.
 *
 * The tabs the current screen holds are published here too, so a reader with no
 * reference to the screen can tell this app's tabs from anything else.
 */
object Roster {
    private val _roster = MutableStateFlow<List<RunningSession>>(emptyList())
    val roster: StateFlow<List<RunningSession>> = _roster.asStateFlow()

    // A function rather than a list, because a tab's leader has a pid only once
    // its terminal has started, and the screen starts it when it draws it.
    @Volatile
    private var tabSource: () -> List<TabIdentity> = { emptyList() }

    /** The tabs the current screen holds, as their processes know them. */
    fun tabs(): List<TabIdentity> = tabSource()

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

    /** Starts watching [agentHome]'s rosters. A second call changes nothing. */
    fun start(agentHome: File) {
        synchronized(lock) {
            if (home != null) return
            home = agentHome
            for (backend in BACKENDS) {
                backend.rosterDirectory(agentHome)?.let(::watch)
            }
        }
        refresh()
    }

    /** Names where the tabs the screen holds are read from. */
    fun publishTabs(source: () -> List<TabIdentity>) {
        tabSource = source
    }

    /**
     * Reads every roster on the calling thread and publishes what it read.
     *
     * For a decision that cannot act on a roster not yet published: a restore,
     * and the check before starting a conversation.
     */
    fun readNow(): List<RunningSession> {
        val read = synchronized(lock) {
            val agentHome = home ?: return emptyList()
            BACKENDS.flatMap { backend ->
                val directory = backend.rosterDirectory(agentHome) ?: return@flatMap emptyList()
                readRoster(
                    directory,
                    backend::parseRecord,
                    proc,
                    lastGood.getOrPut(directory) { mutableMapOf() },
                )
            }.also(::reportUnreadable)
        }
        _roster.value = read
        return read
    }

    /** Reads and publishes, for a caller that does not need the answer. */
    fun refresh() {
        readNow()
    }

    private fun watch(directory: File) {
        directory.mkdirs()
        observers.remove(directory)?.stopWatching()
        val observer = object : FileObserver(directory, MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                    // The directory itself went, and the watch went with it.
                    synchronized(lock) { watch(directory) }
                }
                refresh()
            }
        }
        observers[directory] = observer
        observer.startWatching()
    }

    private fun reportUnreadable(sessions: List<RunningSession>) {
        for (session in sessions) {
            if (session.tag != null || session.pid in unreadable) continue
            if (!proc.environReadable(session.pid)) {
                unreadable += session.pid
                Log.w(TAG, "the environment of ${session.pid} cannot be read; it belongs to no tab")
            }
        }
    }

    private const val TAG = "Roster"

    private const val MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
        FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF or
        FileObserver.MOVE_SELF
}

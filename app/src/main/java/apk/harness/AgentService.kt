package apk.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import apk.harness.agents.ProcTable
import apk.harness.agents.Sample
import apk.harness.agents.TabIdentity
import apk.harness.agents.workingTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the app running while a tab is working, and lets it rest when none is.
 *
 * An app the system considers cached is frozen, and a frozen app stops reading
 * the terminal it owns, which stops the agent behind it. While a tab is working
 * -- an agent on a turn, a job in a shell getting somewhere -- this runs in the
 * foreground, which is what tells the system the process is doing something a
 * person asked for, and holds a partial wake lock, which keeps the CPU running
 * with the screen off so a long answer finishes in a pocket. When nothing is
 * working it stops itself, and the app is an ordinary cached app: an agent at
 * its prompt may be frozen, or ended under memory pressure, and comes back as a
 * tab on its conversation.
 *
 * The service decides its own end, from the roster, so a start that arrives
 * while it is about to stop cancels the stop rather than landing on a service
 * that is going.
 */
class AgentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The latest start, which a stop names so that a start arriving after the
    // stop was decided keeps the service.
    private var latestStart = 0

    // Since when nothing has been working, on the clock that does not step.
    private var restingSince: Long? = null

    private var shown = emptySet<Int>()

    private val reread = object : Runnable {
        override fun run() {
            // A killed agent makes no event, so the roster is read again while
            // anything could be holding the service. The count is taken as well:
            // a shell job that stopped getting anywhere changes no record, and a
            // roster that reads the same publishes nothing.
            Roster.refresh()
            recount()
            main.postDelayed(this, REREAD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Roster.start(Agent(this).home)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
        }
        scope.launch { Roster.roster.collect { recount() } }
        main.postDelayed(reread, REREAD_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start, not only the first: a start the platform delivers must be
        // answered with a foreground notification, whether or not the service
        // was already running.
        startForeground(NOTIFICATION_ID, summary(Work.working.value))
        latestStart = startId
        restingSince = null
        recount()
        // A service the system restarts has no agents to keep: they were
        // children of the process that went.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        cancelTabs(shown)
        Work.publish(emptyList())
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Counts what is working and acts on it. On the main thread. */
    private fun recount() {
        val working = Work.count()
        if (working.isNotEmpty()) {
            restingSince = null
            // Renewed on every count: the timeout guards against a release this
            // misses while the process lives, and a turn longer than it is kept.
            wakeLock?.acquire(WAKE_TIMEOUT_MS)
            notifyWorking(working)
            return
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        notifyWorking(emptyList())
        val since = restingSince ?: SystemClock.elapsedRealtime().also { restingSince = it }
        val rested = SystemClock.elapsedRealtime() - since
        if (rested >= GRACE_MS) {
            stopSelfResult(latestStart)
        } else {
            main.removeCallbacks(graceEnds)
            main.postDelayed(graceEnds, GRACE_MS - rested)
        }
    }

    private val graceEnds = Runnable { recount() }

    /** The summary and one notification per working tab. */
    private fun notifyWorking(working: List<TabIdentity>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, summary(working))
        val ids = working.mapTo(HashSet()) { tabId(it) }
        for (tab in working) manager.notify(tabId(tab), tabNotification(tab))
        cancelTabs(shown - ids)
        shown = ids
    }

    private fun cancelTabs(ids: Set<Int>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (id in ids) manager.cancel(id)
    }

    private fun summary(working: List<TabIdentity>): Notification {
        channels(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (working.size) {
            0 -> getString(R.string.service_running)
            1 -> working.single().label.ifEmpty { getString(R.string.service_running) }
            else -> "${working.size} tabs working"
        }
        return builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setGroup(WORKING_GROUP)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
    }

    /** One working tab: its label, and a tap that brings it forward. */
    private fun tabNotification(tab: TabIdentity): Notification {
        val id = tabId(tab)
        val tap = PendingIntent.getActivity(
            this,
            id,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_SESSION, tab.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(this, CHANNEL_ID)
            .setContentTitle(tab.label.lineSequence().firstOrNull().orEmpty().ifEmpty { "working" })
            .setContentText("working")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tap)
            .setGroup(WORKING_GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        /**
         * Starts the service, which then decides from the roster how long it
         * runs. Refused while the app is in the background; the refusal is
         * logged and the next chance tries again.
         */
        fun request(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            } catch (refused: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException, from API 31, is one
                // of these.
                Log.w(TAG, "the service was not started: ${refused.javaClass.simpleName}")
            }
        }

        /**
         * Raises the ask for the chat [key], titled [title] and reading [message].
         *
         * Its own channel at high importance, beside the service's silent one: the
         * point of it is reaching someone in another app, and a heads-up is what
         * does that. Two channels so the operator can silence this one in Android's
         * own settings without silencing the service, which cannot be silenced
         * without losing the process.
         *
         * One id per chat, so a second ask from the same chat replaces the first
         * and asks from different chats stand side by side: a chat is where a tap
         * lands, so two rows for one chat are two taps to the same place.
         * `setOnlyAlertOnce` is the rate limit that comes with that, with no clock
         * and no bookkeeping -- a live id updates its text in silence and alerts
         * again only once the person has dismissed or tapped it.
         *
         * [sessionId] is the conversation a tap lands on. Null is an ask with no
         * chat behind it, which opens the app on whatever it was showing.
         */
        fun ask(context: Context, key: Long, title: String, message: String, sessionId: String?) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            channels(context)

            val id = askId(key)
            // The id is the request code as well. Two PendingIntents matching on
            // everything but their extras are one PendingIntent, and the extra is
            // the whole of what tells one chat's tap from another's.
            val tap = PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_SESSION, sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = builder(context, ASK_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                // Distinct from the service's stat_notify_sync, so the status bar
                // says which of the two is there.
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // What a heads-up is made of before a channel carried importance.
                @Suppress("DEPRECATION")
                builder.setPriority(Notification.PRIORITY_HIGH)
            }

            manager.notify(id, builder.build())
        }

        /** Takes away the ask for one chat, whether or not one is standing. */
        fun clearAsk(context: Context, key: Long) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(askId(key))
        }

        /**
         * Takes away every ask this app has standing.
         *
         * Every agent is a child process of the app, so an ask that outlives the
         * app points at a conversation that is not there. The service's own
         * notifications are left, because they describe what is working rather
         * than a request.
         */
        fun clearAsks(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.activeNotifications
                .map { it.id }
                .filter { it in NOTIFY_BASE until TAB_BASE }
                .forEach { manager.cancel(it) }
        }

        /**
         * Makes the service's channel and the ask channel, on Android 8 and later,
         * where a notification needs one. Made when the activity starts: at target
         * 28 on Android 13 and later, the platform asks for the notification
         * permission once a channel exists and an activity is started.
         *
         * The ask channel is at high importance, beside the service's silent one,
         * so the operator can silence asks in Android's own settings without
         * silencing the service.
         */
        fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ASK_CHANNEL_ID,
                    context.getString(R.string.ask_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setShowBadge(true) },
            )
        }

        /**
         * The builder every notification of the app is made with.
         *
         * A channel is where importance lives from Android 8 on, and the builder
         * that names one does not exist below it.
         */
        private fun builder(context: Context, channelId: String): Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

        /**
         * A slot in the shade, one per chat.
         *
         * Clear of the service's own id and of `NO_CHAT`, and derived from the tab
         * key rather than the session id because a slot is a place in a shade the
         * process clears on its way out, not a name for a conversation.
         */
        private fun askId(key: Long): Int = NOTIFY_BASE + key.toInt()

        /** The slot of one working tab, above every ask's. */
        private fun tabId(tab: TabIdentity): Int = TAB_BASE + (tab.tag.hashCode() and 0xFFFF)

        /** The conversation a tap lands on; absent for an ask with no chat. */
        const val EXTRA_SESSION = "apk.harness.session"

        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_BASE = 100
        private const val TAB_BASE = 10_000
        private const val CHANNEL_ID = "agent"
        private const val CHANNEL_NAME = "Running agent"
        private const val ASK_CHANNEL_ID = "operator"
        private const val WORKING_GROUP = "working"
        private const val WAKE_TAG = "harness:agent"

        /** How long nothing works before the service stops. */
        private const val GRACE_MS = 10_000L

        /** How often the roster is read again while the service runs. */
        private const val REREAD_MS = 30_000L

        /** The wake lock's backstop, renewed on every count above zero. */
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
    }
}

/**
 * What is working, for the whole process: the service acts on it, and the screen
 * keeps itself on by it.
 */
object Work {
    private val _working = MutableStateFlow<List<TabIdentity>>(emptyList())
    val working: StateFlow<List<TabIdentity>> = _working.asStateFlow()

    private val proc = ProcTable()
    private var samples: Map<String, Sample> = emptyMap()

    /** Counts the working tabs from the roster as last read, and publishes them. */
    @Synchronized
    fun count(): List<TabIdentity> {
        val result = workingTabs(
            Roster.tabs(),
            Roster.roster.value,
            proc,
            samples,
            SystemClock.elapsedRealtime(),
        )
        samples = result.samples
        _working.value = result.working
        return result.working
    }

    /** True when a tab has an agent alive, working or not. */
    fun anyAgent(): Boolean {
        val tags = Roster.tabs().mapTo(HashSet()) { it.tag }
        return Roster.roster.value.any { it.tag in tags }
    }

    internal fun publish(working: List<TabIdentity>) {
        _working.value = working
    }
}

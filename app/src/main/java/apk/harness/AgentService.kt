package apk.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the agent alive while the app is not on screen.
 *
 * An app the system considers cached is frozen, and a frozen app stops reading
 * the terminal it owns, which stops the agent behind it. Coming back can find a
 * session that has been still for minutes and reads as dead. A foreground
 * service is what tells the system this process is doing something a person
 * asked for, and the notification is the price of saying so.
 *
 * The wake lock is separate and deliberate: it keeps the CPU running while the
 * screen is off, which is what lets a long answer finish in a pocket. It costs
 * battery, so it is held only while the service is.
 */
class AgentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ASK_CHANNEL_ID,
                        context.getString(R.string.ask_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { setShowBadge(true) },
                )
            }

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
         * notification is left, because it describes the process rather than a
         * request.
         */
        fun clearAsks(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.activeNotifications
                .map { it.id }
                .filter { it != NOTIFICATION_ID }
                .forEach { manager.cancel(it) }
        }

        /**
         * The builder both notifications are made with.
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

        /** The conversation a tap lands on; absent for an ask with no chat. */
        const val EXTRA_SESSION = "apk.harness.session"

        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_BASE = 100
        private const val CHANNEL_ID = "agent"
        private const val CHANNEL_NAME = "Running agent"
        private const val ASK_CHANNEL_ID = "operator"
        private const val WAKE_TAG = "harness:agent"
    }
}

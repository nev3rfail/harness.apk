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

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
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

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "agent"
        private const val CHANNEL_NAME = "Running agent"
        private const val WAKE_TAG = "harness:agent"
    }
}

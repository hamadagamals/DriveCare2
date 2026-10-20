package com.drivecare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that keeps the DriveCare process (and therefore the OBD telemetry loop and the
 * safety-alert evaluation that run inside [MainActivity]) alive while the screen is off or the driver is
 * using another app (maps, browser...). Without it Android may freeze or kill a backgrounded process.
 *
 * It owns no OBD logic: it only provides process priority, a CPU wake lock while the link is up, and a
 * persistent notification with a "stop" action.
 */
class ObdForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Let the activity close its sockets and update its UI, then shut ourselves down.
            try { onStopRequested?.invoke() } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        ensureChannel(this)
        val notification = buildNotification(this, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // The OS refused foreground promotion (missing permission / restricted start). Do not linger.
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        acquireWakeLock()
        // START_NOT_STICKY: if the process is killed there is no OBD loop left to host, so do not auto-restart.
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            // Timed acquire so a bug can never leave the CPU awake forever; refreshed on every start/update.
            lock.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) lock.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.drivecare.app.action.OBD_START"
        const val ACTION_STOP = "com.drivecare.app.action.OBD_STOP"
        private const val EXTRA_TEXT = "extra_text"
        private const val DEFAULT_TEXT = "DriveCare يراقب سيارتك والإنذارات تعمل بالخلفية"
        private const val CHANNEL_ID = "drivecare_obd_service_channel"
        private const val NOTIFICATION_ID = 9990
        private const val WAKELOCK_TAG = "DriveCare:ObdLink"
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Set by the activity; invoked on the main thread when the user taps "stop" in the notification. */
        @Volatile
        var onStopRequested: (() -> Unit)? = null

        /**
         * Starts the service (or just refreshes its text if it is already running).
         * Returns false when Android refuses a background start; callers must treat this as non-fatal.
         */
        fun start(context: Context, text: String): Boolean {
            if (isRunning) {
                updateText(context, text)
                return true
            }
            return try {
                val intent = Intent(context, ObdForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TEXT, text)
                }
                context.startForegroundService(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        /** Updates the persistent notification text without restarting the service. */
        fun updateText(context: Context, text: String) {
            if (!isRunning) return
            try {
                ensureChannel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(context, text))
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ObdForegroundService::class.java))
            } catch (_: Exception) {}
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "مراقبة السيارة بالخلفية",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعار دائم يوضح أن DriveCare يراقب قطعة OBD ويشغّل الإنذارات بالخلفية"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(context: Context, text: String): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context, 1, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stopIntent = Intent(context, ObdForegroundService::class.java).apply { action = ACTION_STOP }
            val stopPending = PendingIntent.getService(
                context, 2, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("DriveCare")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            @Suppress("DEPRECATION")
            builder.addAction(android.R.drawable.ic_delete, "إيقاف المراقبة", stopPending)
            return builder.build()
        }
    }
}

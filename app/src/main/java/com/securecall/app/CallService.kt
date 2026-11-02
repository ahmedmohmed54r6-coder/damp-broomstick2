package com.securecall.app

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
 * Foreground service: keeps the call alive (camera, microphone, network) when the user switches to
 * another app or locks the screen. The call itself still runs inside MainActivity; this service only
 * tells Android that the app is doing an ongoing call so it must not be frozen or have its camera/mic cut.
 * A clearly visible, ongoing notification is required by Android for this and is never hidden.
 */
class CallService : Service() {
    companion object {
        const val ACTION_STOP = "com.securecall.app.STOP"
        private const val CH = "securecall_call"
        private const val ID = 77
        /** Set by MainActivity: what the notification's "End" button does. */
        @Volatile var onHangup: (() -> Unit)? = null
    }

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onHangup?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(ID, n, type)
        } else {
            startForeground(ID, n)
        }
        if (wl == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "securecall:call").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "مكالمة نشطة", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("SecureCall")
            .setContentText("مكالمة مشفّرة نشطة — اضغط للعودة")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إنهاء", stop)
        return b.build()
    }

    override fun onDestroy() {
        try { wl?.release() } catch (_: Exception) {}
        wl = null
        super.onDestroy()
    }

    /** If the task is swiped away from recents while a call is active, keep it going instead of
     *  silently dropping the call; the ongoing notification stays visible either way. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restart = Intent(applicationContext, CallService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) applicationContext.startForegroundService(restart)
            else applicationContext.startService(restart)
        } catch (_: Exception) {}
    }
}

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
 */
class CallService : Service() {
    companion object {
        const val ACTION_STOP = "com.securecall.app.STOP"
        const val EXTRA_CHILD = "child"
        private const val CH = "securecall_call"
        private const val ID = 77
        /** Set by MainActivity: what the notification's "End" button does. */
        @Volatile var onHangup: (() -> Unit)? = null
    }

    private var wl: PowerManager.WakeLock? = null
    /** Remembers whether this run is the child phone's service, so onTaskRemoved knows how to restart it. */
    private var isChild = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onHangup?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val child = intent?.getBooleanExtra(EXTRA_CHILD, false) ?: false
        isChild = child
        val n = buildNotification(child)
        if (Build.VERSION.SDK_INT >= 30) {
            // Parent phone only uses the microphone; the child phone uses camera + microphone.
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (child) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            // location type only when the permission is granted (Android 14 rejects it otherwise)
            if (child && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(ID, n, type)
        } else {
            startForeground(ID, n)
        }
        if (wl == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "securecall:call").apply {
                setReferenceCounted(false)
                if (child) acquire() else acquire(3 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(child: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "SecureCall", NotificationManager.IMPORTANCE_LOW))
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
            .setContentText(
                if (child) "وضع الطفل: الهاتف جاهز لنداء الأهل (مشفّر)"
                else "مكالمة مشفّرة نشطة — اضغط للعودة"
            )
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
        // The child phone can only be stopped with the parent's PIN inside the app, so no End button there.
        if (!child) b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إنهاء", stop)
        return b.build()
    }

    override fun onDestroy() {
        try { wl?.release() } catch (_: Exception) {}
        wl = null
        super.onDestroy()
    }

    /**
     * Called when the task is swiped away from the recent-apps list. On the child phone this is an
     * emergency line, so the service restarts itself right away instead of staying down; on the
     * parent phone there is no such need, so it's left to stop normally.
     * Note: this only survives being swiped from recents. A manual "Force stop" from Android's own
     * Settings > Apps screen, or uninstalling the app, always stops it — no app can prevent that.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isChild) return
        val restart = Intent(applicationContext, CallService::class.java)
            .putExtra(EXTRA_CHILD, true)
        try {
            if (Build.VERSION.SDK_INT >= 26) applicationContext.startForegroundService(restart)
            else applicationContext.startService(restart)
        } catch (_: Exception) {}
    }
}

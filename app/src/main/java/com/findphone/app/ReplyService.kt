package com.findphone.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class ReplyService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var located = false
    private var started = false
    private var anyProvider = false
    private var player: MediaPlayer? = null
    private var lm: LocationManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var job: SmsJob? = null

    private var to = ""
    private var ring = false
    private var sub = -1

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val acc = if (l.hasAccuracy()) l.accuracy else 9999f
            val b = best
            if (b == null || acc < (if (b.hasAccuracy()) b.accuracy else 9999f)) best = l
            if (acc <= 30f) finish()
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        // اسم القناة ونصها عامّان ومنخفضا الأولوية عمدًا حتى لا يلفتا نظر من بحوزته الهاتف.
        // هذا لا يُخفي الإشعار (أندرويد لا يسمح بذلك لخدمة موقع تعمل في الخلفية)، لكنه يجعله غير لافت.
        // الخدمة تواصل العمل حتى لو عطّل المستخدم إشعارات التطبيق من الإعدادات.
        val ch = NotificationChannel("svc", "خدمات النظام", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        try { goForeground() } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY      // طلب سابق ما زال قيد التنفيذ
        started = true

        to = i.getStringExtra("to") ?: run { stopSelf(); return START_NOT_STICKY }
        ring = i.getBooleanExtra("ring", false)
        sub = i.getIntExtra("sub", -1)

        // يبقي المعالج مستيقظًا أثناء انتظار GPS (مهم عند الشاشة المقفلة وبدون إنترنت)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:reply").apply { acquire(12 * 60_000L) }

        if (ring) startRing()

        val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = mgr
        try {
            // GPS يعمل بدون إنترنت؛ مزوّد الشبكة يساعد فقط عند توفر الإنترنت
            for (pr in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (mgr.isProviderEnabled(pr)) { mgr.requestLocationUpdates(pr, 0L, 0f, listener, Looper.getMainLooper()); anyProvider = true }
        } catch (e: SecurityException) { }

        // GPS البارد بدون إنترنت قد يحتاج دقيقة أو أكثر: ننتظر حتى دقيقتين ونقبل نتيجة أقل دقة مع الوقت
        h.postDelayed({ accept(100f) }, 20_000L)
        h.postDelayed({ accept(300f) }, 45_000L)
        h.postDelayed({ accept(Float.MAX_VALUE) }, 90_000L)
        h.postDelayed({ finish() }, 120_000L)
        return START_NOT_STICKY
    }

    private fun accept(maxAcc: Float) {
        val b = best ?: return
        if ((if (b.hasAccuracy()) b.accuracy else 9999f) <= maxAcc) finish()
    }

    private fun baseNotif(text: String) =
        Notification.Builder(this, "svc").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("خدمات النظام").setContentText(text)
            .setPriority(Notification.PRIORITY_MIN).setOngoing(true).build()

    private fun goForeground() {
        androidx.core.app.ServiceCompat.startForeground(this, 1, baseNotif("مزامنة"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    // تحديث النص لا يفشل العملية إن كانت الإشعارات معطّلة من المستخدم؛ الخدمة تواصل العمل دائمًا
    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(1, baseNotif("مزامنة")) } catch (e: Exception) { }
    }

    private fun finish() {
        if (located) return
        located = true
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        var l = best
        var stale = false
        if (l == null) {
            val mgr = lm
            l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { mgr?.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            stale = l != null
        }
        val bat = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val sb = StringBuilder("FindPhone: ")
        if (l != null) {
            sb.append("https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, l.latitude, l.longitude))
            if (l.hasAccuracy()) sb.append(" acc ${l.accuracy.toInt()}m")
            val ageMin = (System.currentTimeMillis() - l.time) / 60_000
            if (stale && l.time > 0 && ageMin >= 1) sb.append(" old ${ageMin}m")
            sb.append(" bat $bat%")
        } else {
            sb.append(if (anyProvider) "no GPS fix" else "location is OFF").append(", bat $bat%")
        }

        job = SmsJob(this, to, sb.toString(), sub,
            onStatus = { updateNotification(it) },
            onFinished = { ok ->
                if (!ok) updateNotification("تعذّر الإرسال (لا تغطية)")
                h.postDelayed({ stopSelf() }, if (ring) 60_000L else 3_000L)
            }).also { it.start() }
    }

    private fun startRing() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(this@ReplyService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare(); start()
            }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        job?.cancel()
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}

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
    private var started = false
    private var anyProvider = false
    private var sentOnce = false   // أول رسالة: تُرسل بسرعة بأفضل ما هو متاح، حتى لو غير دقيق
    private var sentUpdate = false // تحديث واحد فقط إن تحسّن الموقع لاحقًا
    private var player: MediaPlayer? = null
    private var lm: LocationManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var job: SmsJob? = null
    private var updateJob: SmsJob? = null

    private var to = ""
    private var ring = false
    private var sub = -1

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val acc = if (l.hasAccuracy()) l.accuracy else 9999f
            val b = best
            val bAcc = if (b != null && b.hasAccuracy()) b.accuracy else Float.MAX_VALUE
            if (b == null || acc < bAcc) best = l
            if (!sentOnce && acc <= 30f) sendNow(false)
            else if (sentOnce && !sentUpdate && acc <= 30f && (b == null || acc < bAcc - 20f)) sendUpdate(l)
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
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:reply").apply { acquire(5 * 60_000L) }

        if (ring) startRing()

        val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = mgr
        try {
            // GPS يعمل بدون إنترنت؛ مزوّد الشبكة يساعد فقط عند توفر الإنترنت
            for (pr in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (mgr.isProviderEnabled(pr)) { mgr.requestLocationUpdates(pr, 0L, 0f, listener, Looper.getMainLooper()); anyProvider = true }
        } catch (e: SecurityException) { }

        // لا ننتظر دقيقتين قبل أول رد: نرسل بسرعة بأفضل موقع متاح (حتى لو قديم)،
        // ثم نرسل تحديثًا واحدًا فقط إن وصل موقع أدق خلال الدقيقة التالية.
        h.postDelayed({ if (!sentOnce) sendNow(true) }, 7_000L)
        h.postDelayed({ stopIfIdle() }, 65_000L)
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        if (job == null && updateJob == null) stopSelf()
        else h.postDelayed({ stopSelf() }, 10_000L)
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

    private fun lastKnown(): Location? {
        val mgr = lm ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun format(l: Location?, stale: Boolean, bat: Int): String {
        val sb = StringBuilder("FindPhone: ")
        if (l != null) {
            sb.append("https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, l.latitude, l.longitude))
            if (l.hasAccuracy()) sb.append(" acc ${l.accuracy.toInt()}m")
            val ageMin = (System.currentTimeMillis() - l.time) / 60_000
            if (stale && l.time > 0 && ageMin >= 1) sb.append(" old ${ageMin}m")
            sb.append(" bat $bat%")
        } else {
            sb.append(if (anyProvider) "no GPS fix yet" else "location is OFF").append(", bat $bat%")
        }
        return sb.toString()
    }

    private fun battery() = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    // أول إرسال: فورًا بأفضل ما هو متاح، لا ننتظر دقة عالية
    private fun sendNow(useFallback: Boolean) {
        if (sentOnce) return
        sentOnce = true
        var l = best
        var stale = false
        if (l == null && useFallback) { l = lastKnown(); stale = l != null }
        val text = format(l, stale, battery())
        job = SmsJob(this, to, text, sub,
            onStatus = { updateNotification(it) },
            onFinished = { ok ->
                if (!ok) updateNotification("تعذّر الإرسال (لا تغطية)")
                job = null
                h.postDelayed({ stopIfIdle() }, if (ring) 55_000L else 2_000L)
            }).also { it.start() }
    }

    // تحديث واحد فقط إن تحسّن الموقع كثيرًا بعد الإرسال الأول
    private fun sendUpdate(l: Location) {
        if (sentUpdate) return
        sentUpdate = true
        val text = "Update " + format(l, false, battery())
        updateJob = SmsJob(this, to, text, sub,
            backoffsMs = longArrayOf(5_000, 15_000),
            onFinished = { updateJob = null })
            .also { it.start() }
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
        updateJob?.cancel()
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}

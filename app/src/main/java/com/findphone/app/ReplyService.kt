package com.findphone.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager

class ReplyService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var located = false
    private var player: MediaPlayer? = null
    private lateinit var lm: LocationManager

    private var to = ""
    private var ring = false
    private var plain = false

    // --- إعادة المحاولة عند ضعف التغطية ---
    private var pendingText = ""
    private var attempt = 0
    private val backoffsMs = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 180_000) // تغطي نحو 6.5 دقيقة
    private var receiverRegistered = false
    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == Activity.RESULT_OK) {
                h.postDelayed({ stopSelf() }, if (ring) 60_000L else 3_000L)
            } else {
                scheduleRetry()
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (best == null || l.accuracy < best!!.accuracy) best = l
            if (l.accuracy <= 50f) finish()
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(SENT_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(sentReceiver, filter)
        receiverRegistered = true
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        to = i?.getStringExtra("to") ?: run { stopSelf(); return START_NOT_STICKY }
        ring = i.getBooleanExtra("ring", false)
        plain = i.getBooleanExtra("plain", false)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("svc", "خدمة", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompatStart()
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (ring) startRing()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            for (pr in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm.isProviderEnabled(pr)) lm.requestLocationUpdates(pr, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) { }
        // نافذة أطول لانتظار تحديد الموقع (تغطية ضعيفة قد تُبطئ GPS/الشبكة)
        h.postDelayed({ if (!located) finish() }, 60_000L)
        return START_NOT_STICKY
    }

    private fun ServiceCompatStart() {
        val n = Notification.Builder(this, "svc").setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Find Phone").setContentText("جارٍ تحديد الموقع...").build()
        androidx.core.app.ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(this, "svc").setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Find Phone").setContentText(text).build()
        nm.notify(1, n)
    }

    private fun finish() {
        if (located) return
        located = true
        try { lm.removeUpdates(listener) } catch (e: Exception) { }
        var l = best
        if (l == null) try {
            l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) { }
        val bat = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val ts = System.currentTimeMillis() / 1000
        pendingText = if (plain) {
            if (l != null) "FindPhone: https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, l.latitude)},${"%.6f".format(java.util.Locale.US, l.longitude)} acc ${l.accuracy.toInt()}m bat $bat%"
            else "FindPhone: no location fix, bat $bat%"
        } else {
            val msg = if (l != null) "R|$ts|${"%.6f".format(java.util.Locale.US, l.latitude)}|${"%.6f".format(java.util.Locale.US, l.longitude)}|${l.accuracy.toInt()}|$bat"
                      else "R|$ts|0|0|-1|$bat"
            Crypto.enc(Store(this).group, msg)
        }
        attempt = 0
        attemptSend()
    }

    // يحاول الإرسال، وعند فشله (لا تغطية، راديو مغلق...) يعيد المحاولة تلقائيًا بفواصل متزايدة
    private fun attemptSend() {
        updateNotification(if (attempt == 0) "جارٍ إرسال الرد..." else "لا توجد تغطية، إعادة المحاولة (${attempt + 1}/${backoffsMs.size + 1})...")
        try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(pendingText)
            val sentIntents = ArrayList<PendingIntent>()
            for (idx in parts.indices) {
                val intent = Intent(SENT_ACTION).setPackage(packageName)
                sentIntents.add(PendingIntent.getBroadcast(this, idx, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            sm.sendMultipartTextMessage(to, null, parts, sentIntents, null)
        } catch (e: Exception) {
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (attempt >= backoffsMs.size) { updateNotification("تعذّر الإرسال (لا تغطية)"); h.postDelayed({ stopSelf() }, 3_000L); return }
        val delay = backoffsMs[attempt]
        attempt++
        h.postDelayed({ attemptSend() }, delay)
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
        try { lm.removeUpdates(listener) } catch (e: Exception) { }
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        if (receiverRegistered) try { unregisterReceiver(sentReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object { const val SENT_ACTION = "com.findphone.app.SMS_SENT" }
}

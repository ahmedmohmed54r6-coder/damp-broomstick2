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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.app.ServiceCompat

class ReplyService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var sent = false
    private var player: MediaPlayer? = null
    private lateinit var lm: LocationManager

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

    private var to = ""
    private var ring = false
    private var plain = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        to = i?.getStringExtra("to") ?: run { stopSelf(); return START_NOT_STICKY }
        ring = i.getBooleanExtra("ring", false)
        plain = i.getBooleanExtra("plain", false)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("svc", "خدمة", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "svc").setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Find Phone").setContentText("جارٍ تحديد الموقع...").build()
        try {
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (ring) startRing()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            for (pr in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm.isProviderEnabled(pr)) lm.requestLocationUpdates(pr, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) { }
        h.postDelayed({ finish() }, 30000)
        return START_NOT_STICKY
    }

    private fun finish() {
        if (sent) return
        sent = true
        try { lm.removeUpdates(listener) } catch (e: Exception) { }
        var l = best
        if (l == null) try {
            l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) { }
        val bat = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val ts = System.currentTimeMillis() / 1000
        val text = if (plain) {
            if (l != null) "FindPhone: https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, l.latitude)},${"%.6f".format(java.util.Locale.US, l.longitude)} acc ${l.accuracy.toInt()}m bat $bat%"
            else "FindPhone: no location fix, bat $bat%"
        } else {
            val msg = if (l != null) "R|$ts|${"%.6f".format(java.util.Locale.US, l.latitude)}|${"%.6f".format(java.util.Locale.US, l.longitude)}|${l.accuracy.toInt()}|$bat"
                      else "R|$ts|0|0|-1|$bat"
            Crypto.enc(Store(this).group, msg)
        }
        try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null)
        } catch (e: Exception) { }
        h.postDelayed({ stopSelf() }, if (ring) 60000L else 3000L)
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
        super.onDestroy()
    }
}

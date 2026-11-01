package com.securecall.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Child phone, always on in child mode: keeps listening to the encrypted mailbox even when the app
 * screen is closed. Android requires a notification for this; it is the quietest one allowed
 * (no sound, no status-bar icon on most phones). What it does:
 *  - parent call request  -> brings the app to the front so it can answer (needs "display over other
 *    apps", or a full-screen call notification, or the FCM wake-up)
 *  - parent alarm         -> schedules it in AlarmManager (works offline afterwards)
 *  - location request     -> answers from here, no need to open the app
 *  - presence ping        -> answers "I'm here" at once, so the parent sees the real state
 *  - text message         -> shown as a notification when the app isn't on screen
 */
class ChildListenService : Service() {
    companion object {
        @Volatile var running = false
        private const val ID = 78
        private const val CH = "securecall_standby_v18"
        private val RX = Regex("SKC[12]:[A-Za-z0-9_-]+")

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, ChildListenService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ChildListenService::class.java)) } catch (_: Throwable) {}
            running = false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var rv: Rendezvous? = null
    private var code = ""
    private val seen = LinkedHashSet<String>()
    private var lastLaunch = 0L
    private var liveId = ""
    private var liveUntil = 0L
    private var locating = false
    private var lastNotificationState = true
    private var lastNotificationAlert = 0L
    private val healthTick = object : Runnable {
        override fun run() {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelEnabled = if (Build.VERSION.SDK_INT >= 26) {
                    nm.getNotificationChannel(CH)?.importance != NotificationManager.IMPORTANCE_NONE
                } else true
                val enabled = nm.areNotificationsEnabled() && channelEnabled
                val now = System.currentTimeMillis()
                if (!enabled && (lastNotificationState || now - lastNotificationAlert > 15 * 60 * 1000L)) {
                    reply("msg", "A|notif_off|${now / 1000}")
                    lastNotificationAlert = now
                }
                lastNotificationState = enabled
            } catch (_: Exception) {}
            main.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Store.prefs(this)
        val c = p.getString("ccode", "") ?: ""
        val paired = p.getBoolean("cpaired", false) || c.isNotBlank()
        if (!paired || c.isBlank()) { stopForeground(true); stopSelf(); return START_NOT_STICKY }
        // V17 migration: an active child-mode pairing becomes a persistent background pairing.
        if (!p.getBoolean("cpaired", false)) p.edit().putBoolean("cpaired", true).apply()
        Relay.load(p)
        goForeground()
        if (c != code || rv == null) {
            rv?.stop()
            code = c
            val r = Rendezvous(CallCrypto.roomId(code), { m -> try { handle(m) } catch (_: Throwable) {} }, { })
            rv = r
            r.subscribe(System.currentTimeMillis() / 1000 - 6 * 3600)
        }
        running = true
        main.removeCallbacks(healthTick)
        main.post(healthTick)
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "استقبال مكالمات الأهل", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null); ch.enableVibration(false); ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 30, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH)
        else Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        b.setSmallIcon(android.R.drawable.stat_sys_phone_call)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        b.setContentTitle("SecureCall")
            .setContentText("استقبال مكالمات الأهل يعمل في الخلفية")
            .setContentIntent(open).setOngoing(true)
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            val data = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            try {
                startForeground(ID, n, if (loc) data or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else data)
            } catch (_: Exception) {
                try { startForeground(ID, n, data) } catch (_: Exception) { startForeground(ID, n) }
            }
        } else startForeground(ID, n)
    }

    override fun onDestroy() {
        running = false
        rv?.stop(); rv = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- incoming ----------
    private fun handle(raw: String) {
        val t = RX.find(CallCrypto.clean(raw))?.value ?: return
        synchronized(seen) { if (t in seen) return }
        val c = code
        // 1) alarm request (valid 12 h)
        val alm = try { CallCrypto.open(c, "alm", null, t, 12 * 3600 * 1000L) } catch (_: Exception) { null }
        if (alm != null) { mark(t); onAlarm(alm); return }
        // 2) location request
        val lq = try { CallCrypto.open(c, "lreq", null, t, 90 * 1000L) } catch (_: Exception) { null }
        if (lq != null) { mark(t); onLocReq(lq); return }
        // 3) parent call request
        val hello = try { CallCrypto.open(c, "hello", null, t, 60 * 1000L) } catch (_: Exception) { null }
        if (hello != null) { mark(t); onHello(hello); return }
        // 4) ping / text message
        val msg = try { CallCrypto.open(c, "msg", null, t, 90 * 1000L) } catch (_: Exception) { null }
        if (msg != null) { mark(t); onMsg(msg) }
    }

    private fun mark(t: String) {
        synchronized(seen) {
            seen.add(t)
            if (seen.size > 400) { val iter = seen.iterator(); var n = 0; while (iter.hasNext() && n < 100) { iter.next(); iter.remove(); n++ } }
        }
    }

    private fun reply(kind: String, payload: String) {
        Rendezvous(CallCrypto.roomId(code), { }, { }).publish(CallCrypto.seal(code, kind, null, payload)) { }
    }

    private fun onAlarm(p: String) {
        val f = p.split("|", limit = 6)
        val ok: Boolean
        val id: String
        when {
            f[0] == "AL" && f.size >= 6 -> {
                id = f[1]
                ok = Alarms.apply(this, id, f[2].toLongOrNull() ?: 0L, f[3].toLongOrNull() ?: 0L, f[4] == "1", f[5])
            }
            f[0] == "AC" && f.size >= 3 -> { id = f[1]; ok = Alarms.cancel(this, id, f[2].toLongOrNull() ?: 0L) }
            else -> return
        }
        if (ok) reply("msg", "A|alm|$id|${System.currentTimeMillis() / 1000}")
    }

    private fun onHello(p: String) {
        val f = p.split("|")
        if (f.size < 4 || f[0] != "P") return
        if (MainActivity.childVisible) return                       // the open app answers by itself
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 20000) return
        lastLaunch = now
        main.post { ChildWake.onWake(applicationContext, f[3] == "q") }
    }

    private fun onMsg(p: String) {
        when {
            p.startsWith("PG|") -> {
                val ts = p.removePrefix("PG|").toLongOrNull() ?: return
                if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 45) return
                reply("msg", "A|pong|${System.currentTimeMillis() / 1000}|${battery()}")
            }
            p.startsWith("M|") -> {
                if (MainActivity.childVisible) return
                notifyText("رسالة من الأهل", p.removePrefix("M|"), false)
            }
        }
    }

    // ---------- location ----------
    private fun onLocReq(p: String) {
        val f = p.split("|")
        if (f.size < 3 || f[0] != "Q") return
        when (f[2]) {
            "stop" -> { liveId = ""; main.removeCallbacks(liveTick); notifyText("📍 تتبع الموقع", "توقف تتبع الموقع", true) }
            "live" -> {
                liveId = f[1]; liveUntil = System.currentTimeMillis() + 10 * 60 * 1000L
                notifyText("📍 تتبع الموقع", "الأهل يتتبعون موقعك لمدة 10 دقائق", true)
                main.removeCallbacks(liveTick); liveTick.run()
            }
            else -> { notifyText("📍 موقعك", "تم إرسال موقعك إلى الأهل", true); locate(f[1], "once") }
        }
    }

    private val liveTick = object : Runnable {
        override fun run() {
            if (liveId.isEmpty() || System.currentTimeMillis() > liveUntil) { liveId = ""; return }
            locate(liveId, "live")
            main.postDelayed(this, 30000)
        }
    }

    private fun battery(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun sendLoc(rid: String, l: Location, kind: String) {
        reply("lres", "L|$rid|${l.latitude}|${l.longitude}|${Math.round(l.accuracy)}|${l.time}|${battery()}|$kind")
    }

    private fun locate(rid: String, kind: String) {
        if (locating) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            reply("lres", "X|$rid|perm"); return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        var best: Location? = null
        for (pr in providers) try {
            val l = lm.getLastKnownLocation(pr)
            if (l != null && (best == null || l.time > best!!.time)) best = l
        } catch (_: Exception) {}
        // a fix younger than 30 s is good enough: answer at once
        val b0 = best
        if (b0 != null && System.currentTimeMillis() - b0.time < 30000 && b0.accuracy <= 80f) { sendLoc(rid, b0, kind); return }
        if (providers.isEmpty()) {
            if (b0 == null) reply("lres", "X|$rid|off") else sendLoc(rid, b0, kind)
            return
        }
        locating = true
        var finished = false
        lateinit var listener: LocationListener
        fun finish() {
            if (finished) return
            finished = true
            locating = false
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            val b = best
            if (b != null) sendLoc(rid, b, kind) else reply("lres", "X|$rid|off")
        }
        listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val cur = best
                if (cur == null || l.accuracy < cur.accuracy) best = l
                if (l.accuracy <= 40f) finish()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            for (pr in providers) lm.requestLocationUpdates(pr, 0L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) { finish(); return }
        main.postDelayed({ finish() }, 20000)
    }

    // ---------- notifications ----------
    private fun notifyText(title: String, text: String, quiet: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = if (quiet) "securecall_info" else "securecall_msg"
            if (Build.VERSION.SDK_INT >= 26) {
                val c = NotificationChannel(ch, if (quiet) "إشعارات هادئة" else "رسائل الأهل",
                    if (quiet) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(c)
            }
            val open = PendingIntent.getActivity(
                this, 31, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
            b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            nm.notify(("cl_" + title).hashCode(), b.build())
        } catch (_: Exception) {}
    }
}

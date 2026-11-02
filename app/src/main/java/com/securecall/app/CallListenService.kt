package com.securecall.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps listening for an incoming call while the app isn't on screen, the same way any call app
 * (WhatsApp, Signal...) needs to. Android requires a visible notification for this; it is shown
 * plainly and explains exactly what it does — there is nothing hidden here.
 *
 * It never answers a call by itself: when a call request arrives it only rings and shows the
 * incoming-call screen (Accept / Decline). The person on this phone always decides.
 */
class CallListenService : Service() {
    companion object {
        @Volatile var running = false
        private const val ID = 78
        private const val CH = "securecall_standby"
        private val RX = Regex("SKC[12]:[A-Za-z0-9_-]+")

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, CallListenService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, CallListenService::class.java)) } catch (_: Throwable) {}
            running = false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var rv: Rendezvous? = null
    private var code = ""
    private val seen = LinkedHashSet<String>()
    private var ringingFor = ""
    private var ringingSince = 0L
    private var myId = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Store.prefs(this)
        val c = p.getString("pcode", "") ?: ""
        if (c.isBlank()) { stopForeground(true); stopSelf(); return START_NOT_STICKY }
        myId = p.getString("myDeviceId", "") ?: ""
        Relay.load(p)
        goForeground()
        if (c != code || rv == null) {
            rv?.stop()
            code = c
            val r = Rendezvous(CallCrypto.roomId(code), { m -> try { handle(m) } catch (_: Throwable) {} }, { })
            rv = r
            r.subscribe(System.currentTimeMillis() / 1000 - 10)
        }
        running = true
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "جاهز لاستقبال المكالمات", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("SecureCall")
            .setContentText("جاهز لاستقبال المكالمات في الخلفية")
            .setContentIntent(open).setOngoing(true)
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            try { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            catch (_: Exception) { startForeground(ID, n) }
        } else startForeground(ID, n)
    }

    override fun onDestroy() {
        running = false
        rv?.stop(); rv = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun handle(raw: String) {
        val t = RX.find(CallCrypto.clean(raw))?.value ?: return
        synchronized(seen) { if (t in seen) return }
        val hello = try { CallCrypto.open(code, "hello", null, t, 60 * 1000L) } catch (_: Exception) { null }
        if (hello == null) return
        mark(t)
        val f = hello.split("|")
        if (f.size < 4 || f[0] != "P") return
        val callerId = f[1]; val callerName = f[2]; val type = f[3]
        if (myId.isNotBlank() && callerId == myId) return   // our own outgoing call request, echoed back
        val now = System.currentTimeMillis()
        if (ringingFor == callerId && now - ringingSince < 60_000L) return   // already ringing for this attempt
        ringingFor = callerId; ringingSince = now
        main.post { ring(callerId, callerName, type) }
    }

    private fun mark(t: String) {
        synchronized(seen) {
            seen.add(t)
            if (seen.size > 300) { val it = seen.iterator(); var n = 0; while (it.hasNext() && n < 100) { it.next(); it.remove(); n++ } }
        }
    }

    private fun ring(callerId: String, callerName: String, type: String) {
        val i = Intent(this, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("callerId", callerId)
            .putExtra("callerName", callerName)
            .putExtra("type", type)
        startActivity(i)
    }
}

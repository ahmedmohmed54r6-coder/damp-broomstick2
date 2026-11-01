package com.securecall.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * FCM is only a "wake-up bell": the push carries no secret and no call data. The child phone
 * subscribes to a topic named after its mailbox id (a hash of the pairing code); the parent asks a
 * tiny Cloud Function to ring that topic. The real (encrypted) call request still travels through
 * the existing mailbox. If Firebase isn't configured (no google-services.json), all of this is a no-op.
 */
object Fcm {
    fun available(ctx: Context): Boolean = try {
        FirebaseApp.getApps(ctx.applicationContext).isNotEmpty()
    } catch (_: Throwable) { false }

    fun subscribe(ctx: Context, code: String) {
        if (code.isEmpty() || !available(ctx)) return
        try { FirebaseMessaging.getInstance().subscribeToTopic(CallCrypto.roomId(code)) } catch (_: Throwable) {}
    }

    fun unsubscribe(ctx: Context, code: String) {
        if (code.isEmpty() || !available(ctx)) return
        try { FirebaseMessaging.getInstance().unsubscribeFromTopic(CallCrypto.roomId(code)) } catch (_: Throwable) {}
    }

    /** Parent side: asks the Cloud Function to ring the child's topic. [done] gets (ok, text). */
    fun wakeRoom(url: String, room: String, silent: Boolean = false, done: ((Boolean, String) -> Unit)? = null) {
        val u = url.trim()
        if (!u.startsWith("https://")) { done?.invoke(false, "الرابط يجب أن يبدأ بـ https://"); return }
        Thread {
            var ok = false
            var msg = ""
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write("{\"room\":\"$room\",\"silent\":$silent}".toByteArray()) }
                val code = c.responseCode
                ok = code in 200..299
                msg = "HTTP $code"
                c.disconnect()
            } catch (e: Exception) { msg = e.message ?: "تعذّر الاتصال" }
            done?.invoke(ok, msg)
        }.start()
    }

    fun wake(url: String, code: String, silent: Boolean = false) {
        if (url.isBlank() || code.isEmpty()) return
        wakeRoom(url, CallCrypto.roomId(code), silent)
    }
}

class ChildFcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        ChildWake.onWake(applicationContext, message.data["q"] == "1")
    }
    override fun onNewToken(token: String) {
        // topic subscriptions survive token refresh; nothing to store (no token is ever sent anywhere)
    }
}

object ChildWake {
    private const val CH = "securecall_wake"

    /** Child phone: a parent is calling. Brings the app up (full-screen call-style notification; the
     *  notification is always visible, nothing here is silent). The activity then answers the
     *  parent's repeated, encrypted call request on its own. */
    fun onWake(ctx: Context, quiet: Boolean = false) {
        val p = Store.prefs(ctx)
        if (!p.getBoolean("cactive", false)) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "securecall:wake").acquire(30 * 1000L)
        } catch (_: Exception) {}
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("wake", true).putExtra("quiet", quiet)
        val pi = PendingIntent.getActivity(ctx, 9, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = if (quiet) CH + "_q" else CH
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = if (quiet) NotificationChannel(chId, "نظرة هادئة", NotificationManager.IMPORTANCE_DEFAULT)
                else NotificationChannel(chId, "نداء الأهل", NotificationManager.IMPORTANCE_HIGH)
                if (quiet) { ch.setSound(null, null); ch.enableVibration(false) }
                nm.createNotificationChannel(ch)
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, chId)
            else Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
            b.setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("SecureCall")
                .setContentText("نداء من الأهل — جارٍ الفتح")
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(pi)
                .setAutoCancel(true)
            if (!quiet) b.setFullScreenIntent(pi, true)   // quiet look: no screen-wake, no sound
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60 * 1000L)
            nm.notify(4242, b.build())
        } catch (_: Exception) {}
        try { ctx.startActivity(intent) } catch (_: Exception) {}
    }
}

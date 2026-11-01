package com.securecall.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parent-set wake-up alarms. The parent's request (encrypted, in the mailbox) only has to reach the
 * child phone once; after that the alarm lives in Android's AlarmManager (setAlarmClock), so it rings
 * at the set time even with no internet, in Doze, and after a reboot (re-armed once the phone is unlocked).
 * Android shows its normal alarm icon on the child phone while an alarm is pending.
 */
object Alarms {
    private const val CH = "securecall_alarm"
    const val NOTIF_ID = 5151
    private const val DAY = 24L * 60 * 60 * 1000

    private fun load(ctx: Context): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val a = JSONArray(SecurePrefs.get(ctx).getString("alarms", "[]"))
            for (i in 0 until a.length()) out.add(a.getJSONObject(i))
        } catch (_: Exception) {}
        return out
    }

    private fun save(ctx: Context, l: List<JSONObject>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        SecurePrefs.get(ctx).edit().putString("alarms", a.toString()).apply()
    }

    private fun opPi(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setAction("com.securecall.app.ALARM").putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun schedule(ctx: Context, id: String, whenMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try { am.setAlarmClock(AlarmManager.AlarmClockInfo(whenMs, show), opPi(ctx, id)) } catch (_: Exception) {}
    }

    private fun unschedule(ctx: Context, id: String) {
        try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(opPi(ctx, id)) } catch (_: Exception) {}
    }

    /** Returns true if the request was new (older/duplicate requests are ignored). */
    fun apply(ctx: Context, id: String, seq: Long, whenMs: Long, repeat: Boolean, label: String): Boolean {
        val p = SecurePrefs.get(ctx)
        if (seq <= p.getLong("alm_seq_$id", 0L)) return false
        p.edit().putLong("alm_seq_$id", seq).apply()
        var w = whenMs
        val now = System.currentTimeMillis()
        if (w <= now) {
            if (!repeat) return false
            while (w <= now) w += DAY
        }
        val l = load(ctx).filter { it.optString("id") != id }.toMutableList()
        l.add(JSONObject().put("id", id).put("w", w).put("r", repeat).put("l", label))
        save(ctx, l)
        schedule(ctx, id, w)
        return true
    }

    fun cancel(ctx: Context, id: String, seq: Long): Boolean {
        val p = SecurePrefs.get(ctx)
        if (seq <= p.getLong("alm_seq_$id", 0L)) return false
        p.edit().putLong("alm_seq_$id", seq).apply()
        val l = load(ctx)
        val keep = mutableListOf<JSONObject>()
        l.forEach { if (id == "*" || it.optString("id") == id) unschedule(ctx, it.optString("id")) else keep.add(it) }
        save(ctx, keep)
        return true
    }

    fun scheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val keep = mutableListOf<JSONObject>()
        load(ctx).forEach {
            var w = it.optLong("w")
            val rep = it.optBoolean("r")
            if (w <= now) { if (!rep) return@forEach; while (w <= now) w += DAY; it.put("w", w) }
            keep.add(it)
            schedule(ctx, it.optString("id"), w)
        }
        save(ctx, keep)
    }

    fun fire(ctx: Context, id: String) {
        val l = load(ctx)
        val item = l.firstOrNull { it.optString("id") == id } ?: return
        val label = item.optString("l").ifBlank { "منبه" }
        if (item.optBoolean("r")) {
            var w = item.optLong("w")
            val now = System.currentTimeMillis()
            while (w <= now) w += DAY
            item.put("w", w)
            schedule(ctx, id, w)
            save(ctx, l)
        } else save(ctx, l.filter { it.optString("id") != id })
        ring(ctx, label)
    }

    private fun ring(ctx: Context, label: String) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "securecall:alarm").acquire(60 * 1000L)
        } catch (_: Exception) {}
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max * 7 / 10)
                am.setStreamVolume(AudioManager.STREAM_ALARM, max * 7 / 10, 0)
        } catch (_: Exception) {}
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("alarm", label)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(ctx, 21, intent, flags)
        val stop = PendingIntent.getBroadcast(ctx, 22, Intent(ctx, AlarmStopReceiver::class.java), flags)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CH, "منبه الأهل", NotificationManager.IMPORTANCE_HIGH)
                ch.setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 600, 400, 600)
                nm.createNotificationChannel(ch)
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH)
            else Notification.Builder(ctx).setPriority(Notification.PRIORITY_MAX)
                .setSound(uri, AudioManager.STREAM_ALARM).setVibrate(longArrayOf(0, 600, 400, 600))
            b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ منبه من الأهل")
                .setContentText(label)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stop)
                .setOngoing(true)
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(2 * 60 * 1000L)
            val n = b.build()
            n.flags = n.flags or Notification.FLAG_INSISTENT
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {}
        try { ctx.startActivity(intent) } catch (_: Exception) {}
    }

    fun stopRinging(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Alarms.fire(context.applicationContext, id)
    }
}

class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.stopRinging(context.applicationContext)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.scheduleAll(context.applicationContext)
    }
}

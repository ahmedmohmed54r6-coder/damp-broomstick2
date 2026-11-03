package com.findphone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)
        if (msgs.isNullOrEmpty()) return
        val from = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val s = Store(c)
        val kw = s.keyword
        if (kw.length >= 6) {
            val t = body.trim()
            val isRing = t.equals("$kw ring", true)
            if (t == kw || isRing) {
                val now0 = System.currentTimeMillis()
                if (now0 - s.lastPlain() < 20000) return   // cooldown against abuse
                s.setLastPlain(now0)
                ContextCompat.startForegroundService(c, Intent(c, ReplyService::class.java)
                    .putExtra("to", from).putExtra("ring", isRing).putExtra("plain", true))
                return
            }
        }
        if (!body.startsWith(Crypto.PREFIX)) return
        if (s.group.isEmpty()) return
        val m = s.memberByNumber(from) ?: return
        val p = Crypto.dec(s.group, body)?.split("|") ?: return
        val ts = p.getOrNull(1)?.toLongOrNull() ?: return
        val now = System.currentTimeMillis() / 1000
        if (Math.abs(now - ts) > 600) return            // too old / replay
        val type = p[0]
        if (ts <= s.lastTs(from, type)) return           // already seen
        s.setLastTs(from, type, ts)
        when (type) {
            "L", "B" -> ContextCompat.startForegroundService(
                c, Intent(c, ReplyService::class.java).putExtra("to", from).putExtra("ring", type == "B"))
            "R" -> if (p.size >= 6) {
                val o = JSONObject().put("name", m.first).put("lat", p[2]).put("lon", p[3])
                    .put("acc", p[4]).put("bat", p[5]).put("ts", ts)
                s.saveResult(o)
                notifyResult(c, o)
            }
        }
    }

    private fun notifyResult(c: Context, o: JSONObject) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("res", "النتائج", NotificationManager.IMPORTANCE_HIGH))
        val ok = o.getString("acc").toFloatOrNull()?.let { it >= 0 } == true
        val name = o.getString("name")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${o.getString("lat")},${o.getString("lon")}(${Uri.encode(name)})"))
        val pi = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2, Notification.Builder(c, "res")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("موقع $name")
            .setContentText(if (ok) "اضغط لفتح الخريطة (البطارية ${o.getString("bat")}%)" else "رد الهاتف لكنه لم يحصل على موقع")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}

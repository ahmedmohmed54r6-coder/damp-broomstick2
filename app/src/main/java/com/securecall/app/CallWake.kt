package com.securecall.app

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs once, right after an FCM "ring" signal arrives (see ChildFcmService). Does a single
 * non-blocking read of the mailbox (ntfy.sh's "poll=1" mode: return what's already there, don't
 * wait) looking for a parent call request from the last 90 seconds. If one decrypts with this
 * phone's own pairing code, it starts the call service immediately (so the OS can't freeze the
 * process again mid-setup) and hands MainActivity just the two non-secret fields it needs
 * (who's calling, which call type) to carry on exactly as if it had seen the message itself.
 * Finds nothing → does nothing; this is best-effort on top of the normal mailbox listener, never
 * a replacement for its encryption or its checks.
 */
object CallWake {
    fun kick(context: Context) {
        Thread {
            try {
                val prefs = SecurePrefs.get(context)
                if (prefs.getInt("mode", 0) != 2 || !prefs.getBoolean("cactive", false)) return@Thread
                val code = (prefs.getString("ccode", "") ?: "").let { if (it.isEmpty()) return@Thread else it }
                val room = CallCrypto.roomId(code)
                val since = System.currentTimeMillis() / 1000 - 90
                val c = URL("https://ntfy.sh/$room/json?since=$since&poll=1").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val j = try { JSONObject(line) } catch (_: Exception) { continue }
                        if (j.optString("event") != "message") continue
                        val raw = j.optString("message")
                        val t = Regex("SKC1:[A-Za-z0-9_-]+").find(CallCrypto.clean(raw))?.value ?: continue
                        val p = try { CallCrypto.open(code, "hello", null, t, 90_000L) } catch (_: Exception) { null } ?: continue
                        val f = p.split("|")
                        if (f.size < 4 || f[0] != "P") continue
                        val svc = Intent(context, CallService::class.java).putExtra(CallService.EXTRA_CHILD, true)
                        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
                        val open = Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("wake_parentId", f[1]).putExtra("wake_type", f[3])
                        context.startActivity(open)
                        break
                    }
                }
                c.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}

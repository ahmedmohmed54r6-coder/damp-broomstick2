package com.securecall.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mailbox on the public ntfy.sh service. It only carries the already-encrypted setup codes
 * (offer/answer); it never sees audio, video, or the shared code. The room name is a hash of the
 * shared code, so only the two people who know the code can find it.
 */
class Rendezvous(
    private val room: String,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var stopped = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private val base = "https://ntfy.sh/"

    fun publish(blob: String, done: (Boolean) -> Unit) {
        Thread {
            var ok = false
            try {
                val c = URL(base + room).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.outputStream.use { it.write(blob.toByteArray()) }
                ok = c.responseCode in 200..299
                c.disconnect()
            } catch (_: Exception) {
            }
            done(ok)
        }.start()
    }

    /** Streams messages published since [sinceEpochSec]; reconnects until stop() is called. */
    fun subscribe(sinceEpochSec: Long) {
        thread = Thread {
            var since = sinceEpochSec
            var failures = 0
            while (!stopped) {
                try {
                    val c = URL("$base$room/json?since=$since").openConnection() as HttpURLConnection
                    conn = c
                    c.connectTimeout = 10000
                    c.readTimeout = 90000
                    if (c.responseCode !in 200..299) throw RuntimeException("HTTP " + c.responseCode)
                    failures = 0
                    c.inputStream.bufferedReader().use { r ->
                        while (!stopped) {
                            val line = r.readLine() ?: break
                            if (line.isBlank()) continue
                            val j = JSONObject(line)
                            if (j.optString("event") == "message") {
                                since = maxOf(since, j.optLong("time", since))
                                onMessage(j.optString("message"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (stopped) break
                    failures++
                    if (failures >= 3) onError("تعذّر الاتصال بخدمة الوسيط. تحقق من الإنترنت.")
                }
                if (!stopped) {
                    // normal reconnect after 2 s; while the network is down back off up to 60 s (saves battery)
                    val wait = if (failures == 0) 2000L else minOf(2000L shl minOf(failures, 5), 60000L)
                    try { Thread.sleep(wait) } catch (_: InterruptedException) {}
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        stopped = true
        try { conn?.disconnect() } catch (_: Exception) {}
        thread?.interrupt()
    }
}

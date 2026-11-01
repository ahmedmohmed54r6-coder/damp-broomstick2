package com.securecall.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TURN helpers: config parsing, Cloudflare auto-renewal, and a real relay test. */
object Turn {
    private const val CF_BASE = "https://rtc.live.cloudflare.com/v1/turn/keys/"
    private const val CF_TTL = 86400

    // ---------- parsing ----------
    /** Accepts the iceServers JSON that Metered / Cloudflare show (array or object, with or without
     *  an "iceServers" wrapper) or text lines "url,user,pass" / "url user pass".
     *  Returns one "url,user,pass" line per chosen TURN url ("" if none). JSON input is trimmed to
     *  at most three urls per credential (UDP, TLS 443, TCP) so the setup code stays short. */
    fun normalize(raw: String): String {
        val src = raw.trim()
        if (src.isEmpty()) return ""
        if (src.startsWith("[") || src.startsWith("{")) {
            try {
                val items = mutableListOf<JSONObject>()
                collect(if (src.startsWith("[")) JSONArray(src) else JSONObject(src), items)
                val triples = mutableListOf<Triple<String, String, String>>()
                for (o in items) {
                    val urls = o.opt("urls") ?: o.opt("url") ?: continue
                    val u = o.optString("username"); val c = o.optString("credential", o.optString("password"))
                    val list = mutableListOf<String>()
                    if (urls is JSONArray) {
                        for (j in 0 until urls.length()) list.add(urls.optString(j))
                    } else {
                        list.add(urls.toString())
                    }
                    list.map { it.trim() }
                        .filter { (it.startsWith("turn:", true) || it.startsWith("turns:", true)) && !it.contains(":53?") && !it.endsWith(":53") }
                        .forEach { triples.add(Triple(it, u, c)) }
                }
                val out = LinkedHashSet<String>()
                triples.groupBy { it.second to it.third }.forEach { (cred, g) ->
                    val urls = g.map { it.first }
                    val udp = urls.firstOrNull { it.startsWith("turn:", true) && !it.contains("transport=tcp", true) }
                    val tls = urls.firstOrNull { it.startsWith("turns:", true) && it.contains(":443") }
                        ?: urls.firstOrNull { it.startsWith("turns:", true) }
                    val tcp = urls.firstOrNull { it.startsWith("turn:", true) && it.contains("transport=tcp", true) && it.contains(":80") }
                        ?: urls.firstOrNull { it.startsWith("turn:", true) && it.contains("transport=tcp", true) }
                    listOfNotNull(udp, tls, tcp).distinct().forEach { x ->
                        out.add(if (cred.first.isNotBlank() && cred.second.isNotBlank()) "$x,${cred.first.trim()},${cred.second.trim()}" else x)
                    }
                }
                if (out.isNotEmpty()) return out.joinToString("\n")
            } catch (_: Exception) {}
        }
        val out = LinkedHashSet<String>()
        src.lines().forEach { line ->
            val p = line.trim().split(Regex("[,\\s|]+")).filter { it.isNotEmpty() }
            if (p.isNotEmpty() && (p[0].startsWith("turn:", true) || p[0].startsWith("turns:", true))) {
                out.add(if (p.size >= 3) "${p[0]},${p[1]},${p[2]}" else p[0])
            }
        }
        return out.joinToString("\n")
    }

    private fun collect(n: Any?, out: MutableList<JSONObject>) {
        when (n) {
            is JSONArray -> for (i in 0 until n.length()) collect(n.opt(i), out)
            is JSONObject -> if (n.has("iceServers")) collect(n.opt("iceServers"), out) else out.add(n)
        }
    }

    /** STUN (optional) + every TURN line from the manual text and the Cloudflare cache. */
    fun build(manual: String, cf: String, withStun: Boolean = true): List<PeerConnection.IceServer> {
        val list = mutableListOf<PeerConnection.IceServer>()
        if (withStun) {
            list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            list.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        }
        (normalize(manual) + "\n" + cf).lines().filter { it.isNotBlank() }.forEach { ln ->
            val p = ln.split(",").map { it.trim() }
            val b = PeerConnection.IceServer.builder(p[0])
            if (p.size >= 3) { b.setUsername(p[1]); b.setPassword(p[2]) }
            list.add(b.createIceServer())
        }
        return list
    }

    fun iceServers(p: SharedPreferences): List<PeerConnection.IceServer> =
        build(p.getString("turn", "") ?: "", cfCached(p), true)

    // ---------- Cloudflare short-lived credentials ----------
    fun cfConfigured(p: SharedPreferences) =
        !p.getString("cf_id", "").isNullOrBlank() && !p.getString("cf_tok", "").isNullOrBlank()

    fun cfCached(p: SharedPreferences): String =
        if (System.currentTimeMillis() < p.getLong("cf_exp", 0L)) p.getString("cf_lines", "") ?: "" else ""

    /** True when Cloudflare is set up and the cached credentials are missing or expire within an hour. */
    fun cfNeedsRefresh(p: SharedPreferences) =
        cfConfigured(p) && p.getLong("cf_exp", 0L) - System.currentTimeMillis() < 60 * 60 * 1000L

    fun cfRefresh(p: SharedPreferences, done: (Boolean, String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            var ok = false
            var msg = ""
            try {
                val id = (p.getString("cf_id", "") ?: "").trim()
                val tok = (p.getString("cf_tok", "") ?: "").trim()
                var lines = ""
                for (path in listOf("generate-ice-servers", "generate")) {
                    val c = URL("$CF_BASE$id/credentials/$path").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 10000; c.readTimeout = 10000; c.doOutput = true
                    c.setRequestProperty("Authorization", "Bearer $tok")
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write("{\"ttl\":$CF_TTL}".toByteArray()) }
                    val code = c.responseCode
                    if (code in 200..299) {
                        lines = normalize(c.inputStream.bufferedReader().readText())
                    } else {
                        msg = "Cloudflare رد بالخطأ $code"
                    }
                    c.disconnect()
                    if (lines.isNotEmpty()) break
                }
                if (lines.isNotEmpty()) {
                    p.edit().putString("cf_lines", lines)
                        .putLong("cf_exp", System.currentTimeMillis() + CF_TTL * 1000L - 5 * 60 * 1000L).apply()
                    ok = true
                } else if (msg.isEmpty()) msg = "لم تصل بيانات TURN"
            } catch (e: Exception) {
                msg = "تعذّر الوصول إلى Cloudflare: ${e.message ?: ""}"
            }
            main.post { done(ok, msg) }
        }.start()
    }

    // ---------- relay test ----------
    /** Asks the given servers for a relay candidate. A TURN server that works returns one within seconds. */
    fun test(ctx: Context, servers: List<PeerConnection.IceServer>, done: (Boolean, String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val latch = CountDownLatch(1)
            val relay = java.util.concurrent.atomic.AtomicBoolean(false)
            var factory: PeerConnectionFactory? = null
            var pc: PeerConnection? = null
            var err = ""
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
                )
                val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
                factory = f
                val cfg = PeerConnection.RTCConfiguration(servers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                    iceTransportsType = PeerConnection.IceTransportsType.RELAY
                }
                val obs = object : PeerConnection.Observer {
                    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                    override fun onIceConnectionReceivingChange(b: Boolean) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                        if (s == PeerConnection.IceGatheringState.COMPLETE) latch.countDown()
                    }
                    override fun onIceCandidate(c: IceCandidate?) {
                        if (c != null && c.sdp.contains(" typ relay")) { relay.set(true); latch.countDown() }
                    }
                    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                    override fun onAddStream(s: MediaStream?) {}
                    override fun onRemoveStream(s: MediaStream?) {}
                    override fun onDataChannel(d: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onTrack(t: RtpTransceiver?) {}
                }
                val p = f.createPeerConnection(cfg, obs)
                pc = p
                if (p == null) err = "تعذّر إنشاء الاتصال التجريبي"
                else {
                    p.createDataChannel("t", DataChannel.Init())
                    p.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            p.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(x: SessionDescription?) {}
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(e: String?) {}
                                override fun onSetFailure(e: String?) {}
                            }, d)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(e: String?) { latch.countDown() }
                        override fun onSetFailure(e: String?) {}
                    }, MediaConstraints())
                    latch.await(12, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) {
                err = t.message ?: "خطأ غير معروف"
            } finally {
                try { pc?.close(); pc?.dispose() } catch (_: Throwable) {}
                try { factory?.dispose() } catch (_: Throwable) {}
            }
            val ok = relay.get()
            val msg = when {
                ok -> "✅ الخادم يعمل: تم الحصول على مسار relay."
                err.isNotEmpty() -> "❌ فشل الاختبار: $err"
                else -> "❌ لم يُرجع الخادم مسار relay. غالباً اسم المستخدم/كلمة المرور خاطئة أو منتهية (بيانات Cloudflare مؤقتة)، أو الخادم محجوب على هذه الشبكة."
            }
            main.post { done(ok, msg) }
        }.start()
    }
}

package com.securecall.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MainActivity : Activity() {
    private lateinit var egl: EglBase
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var setup: ScrollView
    private lateinit var callLayout: FrameLayout
    private lateinit var codeField: EditText
    private lateinit var turnField: EditText
    private lateinit var pasteField: EditText
    private lateinit var outView: TextView
    private lateinit var statusView: TextView
    private lateinit var applyBtn: Button
    private lateinit var copyOutBtn: Button
    private lateinit var shareOutBtn: Button

    private var engine: CallEngine? = null
    private var role = 0            // 1 = caller, 2 = callee
    private var offerBlob = ""      // caller: the offer it created
    private var outBlob = ""
    private var pendingAction: (() -> Unit)? = null
    private var muted = false
    private var camOn = true
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        egl = EglBase.create()
        buildUi()
    }

    private fun btn(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#101418"))

        // ---------- setup screen ----------
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun tv(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(tv("مكالمة فيديو مشفّرة طرفياً", 22f, true))
        col.addView(tv("الصوت والفيديو يذهبان مباشرة بين الهاتفين. لا يوجد خادم وسيط يرى المحتوى.", 13f))

        codeField = EditText(this).apply {
            hint = "الرمز المشترك (تعرفه أنت وصديقك فقط)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            typeface = Typeface.MONOSPACE
        }
        col.addView(codeField)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("توليد رمز عشوائي") {
            codeField.setText(CallCrypto.newCode())
            toast("انسخ الرمز وأرسله لصديقك مرة واحدة بطريقة آمنة (مثلاً مشفّراً بالكيبورد الآمن)")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(btn("نسخ الرمز") { copy(codeField.text.toString()) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row1)

        turnField = EditText(this).apply {
            hint = "خادم TURN (اختياري): turn:host:3478,user,pass"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 12f
        }
        col.addView(turnField)

        col.addView(btn("١) أنا أبدأ المكالمة") { ensurePerms { begin(1) } })
        col.addView(btn("٢) أنضم لمكالمة صديقي") { ensurePerms { begin(2) } })

        pasteField = EditText(this).apply {
            hint = "الصق هنا رمز الطرف الآخر"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3; maxLines = 6
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 11f
        }
        col.addView(pasteField)
        applyBtn = btn("تطبيق") { apply() }.apply { visibility = View.GONE }
        col.addView(applyBtn)

        outView = TextView(this).apply {
            setTextColor(Color.parseColor("#9be29b")); textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(outView)
        copyOutBtn = btn("نسخ رمز المكالمة") { copy(outBlob) }.apply { visibility = View.GONE }
        shareOutBtn = btn("مشاركة الرمز") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, outBlob)
            }, "مشاركة"))
        }.apply { visibility = View.GONE }
        col.addView(copyOutBtn); col.addView(shareOutBtn)

        statusView = tv("", 14f)
        col.addView(statusView)
        setup = ScrollView(this).apply { addView(col) }
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))

        // ---------- call screen ----------
        callLayout = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        remoteView = SurfaceViewRenderer(this)
        localView = SurfaceViewRenderer(this)
        remoteView.init(egl.eglBaseContext, null)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.init(egl.eglBaseContext, null)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.setMirror(true)
        localView.setZOrderMediaOverlay(true)
        callLayout.addView(remoteView, FrameLayout.LayoutParams(-1, -1))
        callLayout.addView(localView, FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(12), dp(36), dp(12), 0)
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val micBtn = btn("🎤") {}
        micBtn.setOnClickListener {
            muted = !muted; engine?.setMuted(muted); micBtn.text = if (muted) "🔇" else "🎤"
        }
        bar.addView(micBtn)
        val camBtn = btn("📷") {}
        camBtn.setOnClickListener {
            camOn = !camOn; engine?.setCameraOn(camOn); camBtn.text = if (camOn) "📷" else "🚫"
        }
        bar.addView(camBtn)
        bar.addView(btn("🔄") { engine?.flipCamera() })
        bar.addView(btn("إنهاء") { hangup() })
        callLayout.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(0, 0, 0, dp(24)) })
        root.addView(callLayout, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    // ---------- flow ----------
    private fun ensurePerms(action: () -> Unit) {
        if (!CallCrypto.isValid(codeField.text.toString())) {
            toast("الرمز المشترك غير صالح. ولّد رمزاً عشوائياً أو الصق الرمز الذي أرسله صديقك.")
            return
        }
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) action() else {
            pendingAction = action
            requestPermissions(need.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) pendingAction?.invoke()
        else toast("يلزم السماح بالكاميرا والميكروفون")
        pendingAction = null
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val t = turnField.text.toString().trim()
        if (t.isNotEmpty()) {
            val p = t.split(",").map { it.trim() }
            val b = PeerConnection.IceServer.builder(p[0])
            if (p.size >= 3) { b.setUsername(p[1]); b.setPassword(p[2]) }
            list.add(b.createIceServer())
        }
        return list
    }

    private fun begin(r: Int) {
        resetCall()
        role = r
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode; savedSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true

        val e = CallEngine(applicationContext, egl, localView, remoteView,
            { s -> statusView.text = s }, { showCall() })
        engine = e
        try { e.start(iceServers()) } catch (ex: Exception) {
            status("تعذّر تشغيل الاتصال: ${ex.message}"); return
        }
        val code = codeField.text.toString()
        applyBtn.visibility = View.VISIBLE
        if (r == 1) {
            status("جارٍ إنشاء رمز المكالمة...")
            applyBtn.text = "٣) اتصال (بعد لصق رد صديقك)"
            e.createOffer({ sdp ->
                offerBlob = CallCrypto.seal(code, "offer", null, sdp)
                showOut(offerBlob)
                status("أرسل رمز المكالمة لصديقك. عندما يرد عليك برمز، الصقه في الأعلى واضغط اتصال.")
            }, { m -> status("خطأ: $m") })
        } else {
            applyBtn.text = "٣) إنشاء الرد"
            status("الصق رمز المكالمة الذي أرسله صديقك ثم اضغط إنشاء الرد.")
        }
    }

    private fun apply() {
        val e = engine ?: return
        val code = codeField.text.toString()
        val text = pasteField.text.toString()
        try {
            if (role == 1) {
                val sdp = CallCrypto.open(code, "answer", CallCrypto.digest(offerBlob), text)
                status("جارٍ الاتصال...")
                e.acceptAnswer(sdp) { m -> status("خطأ: $m") }
            } else {
                val sdp = CallCrypto.open(code, "offer", null, text)
                val bind = CallCrypto.digest(text)
                status("جارٍ إنشاء الرد...")
                e.acceptOffer(sdp, { ans ->
                    val blob = CallCrypto.seal(code, "answer", bind, ans)
                    showOut(blob)
                    status("أرسل هذا الرد لصديقك. الاتصال يبدأ تلقائياً عندما يطبّقه.")
                }, { m -> status("خطأ: $m") })
            }
        } catch (ex: Exception) {
            status("الرمز غير صحيح، أو الرمز المشترك مختلف، أو انتهت صلاحيته (30 دقيقة). لم يتم الاتصال.")
        }
    }

    private fun showOut(blob: String) {
        outBlob = blob
        outView.text = "تم إنشاء الرمز (${blob.length} حرف). انسخه أو شاركه."
        copyOutBtn.visibility = View.VISIBLE
        shareOutBtn.visibility = View.VISIBLE
    }

    private fun showCall() {
        setup.visibility = View.GONE
        callLayout.visibility = View.VISIBLE
    }

    private fun hangup() {
        resetCall()
        callLayout.visibility = View.GONE
        setup.visibility = View.VISIBLE
        status("انتهت المكالمة.")
    }

    private fun resetCall() {
        engine?.close(); engine = null
        remoteView.clearImage(); localView.clearImage()
        role = 0; offerBlob = ""; outBlob = ""
        muted = false; camOn = true
        pasteField.setText("")
        outView.text = ""
        applyBtn.visibility = View.GONE
        copyOutBtn.visibility = View.GONE
        shareOutBtn.visibility = View.GONE
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            am.mode = savedAudioMode; am.isSpeakerphoneOn = savedSpeaker
        }
    }

    private fun status(s: String) { statusView.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun copy(t: String) {
        if (t.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("code", t))
        toast("تم النسخ")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (callLayout.visibility == View.VISIBLE) hangup() else super.onBackPressed()
    }

    override fun onDestroy() {
        engine?.close(); engine = null
        try { remoteView.release(); localView.release() } catch (_: Exception) {}
        try { egl.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

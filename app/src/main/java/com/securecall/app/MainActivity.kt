package com.securecall.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * SecureCall: a plain, two-person encrypted video/voice call app. Both phones are equal — whoever
 * calls, the other side always sees a ringing incoming-call screen and chooses Accept or Decline.
 * Nothing here reads location, sends hidden alerts, or answers on its own.
 * Setup codes (offer/answer/hello) are AES-GCM encrypted with the shared pairing code (CallCrypto);
 * the call media itself is DTLS-SRTP end to end (WebRTC).
 */
class MainActivity : Activity() {
    companion object {
        const val ACTION_ANSWER = "com.securecall.app.ANSWER"
    }

    private lateinit var egl: EglBase
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var setupScreen: ScrollView
    private lateinit var homeScreen: ScrollView
    private lateinit var callLayout: FrameLayout
    private lateinit var myNameField: EditText
    private lateinit var pasteCodeField: EditText
    private lateinit var peerLabel: TextView
    private lateinit var statusHome: TextView
    private lateinit var turnF: EditText
    private lateinit var relayF: EditText
    private lateinit var diagView: TextView
    private lateinit var titleView: TextView
    private lateinit var bar: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var camBtn: TextView
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatScroll: ScrollView

    private val prefs by lazy { Store.prefs(this) }
    private val ui = Handler(Looper.getMainLooper())

    private var engine: CallEngine? = null
    private var rv: Rendezvous? = null
    private var role = 0                 // 1 = we called, 2 = we accepted
    private var myId = ""
    private var peerId = ""
    private var peerName = ""
    private var wantVideo = false
    private var callType = "a"           // "a" voice, "v" video
    private var waitingPeer = false      // we sent hello, waiting for the other side to accept
    private var waitingOffer = false     // we accepted, waiting for the caller's offer
    private var auto = false
    private var offerBlob = ""
    private var lastHandled = ""
    private val seen = mutableSetOf<String>()
    private var muted = false
    private var cameraOn = true
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        egl = EglBase.create()
        buildUi()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        Relay.load(prefs)
        myId = prefs.getString("myDeviceId", "") ?: ""
        if (myId.isBlank()) {
            myId = CallCrypto.newCode().filter { it != '-' }.take(10)
            prefs.edit().putString("myDeviceId", myId).apply()
        }
        if (paired()) {
            CallListenService.start(this)
            refreshHome()
            show(homeScreen)
        } else show(setupScreen)

        handleAnswerIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnswerIntent(intent)
    }

    private fun handleAnswerIntent(i: Intent?) {
        if (i?.action != ACTION_ANSWER) return
        val code = prefs.getString("pcode", "") ?: ""
        if (code.isBlank()) return
        peerId = i.getStringExtra("peerId") ?: ""
        peerName = i.getStringExtra("peerName") ?: peerName
        callType = i.getStringExtra("type") ?: "a"
        wantVideo = callType == "v"
        resetCall()
        waitingOffer = true
        role = 2
        val r = Rendezvous(CallCrypto.roomId(code), { msg -> runOnUiThread { onRemote(msg) } }, { m -> runOnUiThread { status(m) } })
        rv = r
        r.subscribe(System.currentTimeMillis() / 1000 - 10)
        ui.postDelayed(pairTimeout, 45000)
        status("جارٍ إعداد المكالمة مع $peerName ...")
    }

    private fun paired() = (prefs.getString("pcode", "") ?: "").isNotBlank()

    // ---------- design ----------
    private val BG = Color.parseColor("#0B1220")
    private val CARD = Color.parseColor("#141E33")
    private val FIELD = Color.parseColor("#0E1626")
    private val STROKE = Color.parseColor("#233250")
    private val ACCENT = Color.parseColor("#2DD4BF")
    private val ACCENT2 = Color.parseColor("#3B82F6")
    private val MUTED = Color.parseColor("#8A97B0")
    private val DANGER = Color.parseColor("#EF4444")
    private val SOFT = Color.parseColor("#22304A")

    private fun rounded(color: Int, r: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(r).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun ripple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    private fun label(t: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            Fonts.get(context)?.let { typeface = it }
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pill(t: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            Fonts.get(context)?.let { typeface = it }
            setTextColor(if (primary) Color.parseColor("#04141A") else Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val bgd: Drawable = if (primary)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2))
                    .apply { cornerRadius = dp(16).toFloat() }
            else rounded(SOFT, 16)
            background = ripple(bgd)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun circle(t: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) })
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun field(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText; setHintTextColor(MUTED); setTextColor(Color.WHITE)
            Fonts.get(context)?.let { typeface = it }
            textSize = 14f
            background = rounded(FIELD, 14, STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }

    private fun card(title: String, vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20, STROKE)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(label(title, 13f, MUTED, true))
            views.forEach { v -> addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        }

    private fun cardLp(top: Int = 14) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun screenCol(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(40), dp(18), dp(28))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun header(title: String, sub: String): LinearLayout {
        val badge = TextView(this).apply {
            text = "🔒"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2))
                .apply { shape = GradientDrawable.OVAL }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 24f, Color.WHITE, true))
            addView(label(sub, 12.5f, MUTED, false))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        }
    }

    private fun turnField(): EditText = field("turn:host:3478,user,pass").apply {
        textSize = 12f; minLines = 2; maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR
        setText(prefs.getString("turn", "") ?: "")
    }

    private fun chip(t: String, onClick: () -> Unit): TextView = pill(t, false, onClick).apply {
        textSize = 12f; setPadding(dp(6), dp(11), dp(6), dp(11))
    }

    private fun chipRow(vararg chips: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) }) }
    }

    private fun clipText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    }

    private fun turnBar(target: EditText): LinearLayout {
        val paste = chip("📋 لصق") {
            val norm = Turn.normalize(clipText())
            if (norm.isEmpty()) toast("الحافظة لا تحتوي إعداد TURN. انسخه من لوحة المزوّد ثم اضغط لصق.")
            else { target.setText(norm); toast("تم ضبط خادم TURN ✓ — اضغط اختبار للتأكد") }
        }
        val copyB = chip("📤 نسخ") {
            val t = Turn.normalize(target.text.toString())
            if (t.isEmpty()) toast("لا يوجد إعداد لنسخه") else copy(t)
        }
        val clear = chip("🗑 مسح") { target.setText("") }
        val metered = chip("☁ Metered مجاني") { openUrl("https://dashboard.metered.ca/signup") }
        val cf = chip("☁ Cloudflare تلقائي") { cloudflareDialog() }
        val test = chip("🧪 اختبار") { testTurn(target) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chipRow(paste, copyB, clear))
            addView(chipRow(metered, cf, test), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(label("Metered: أنشئ حساباً مجانياً، انسخ iceServers، ثم «لصق». Cloudflare: أدخل المعرّف والتوكن مرة واحدة " +
                "فيجدّد التطبيق البيانات تلقائياً. «اختبار» يتأكد أن الخادم يعطي مسار relay فعلاً.", 11f, MUTED, false),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (Turn.cfConfigured(prefs)) addView(label("☁ Cloudflare التلقائي مفعّل ✓", 11f, ACCENT, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun cloudflareDialog() {
        fun mono(hint: String) = field(hint).apply { textSize = 12f; typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }
        val idF = mono("Turn Token ID").apply { setText(prefs.getString("cf_id", "") ?: "") }
        val tkF = mono("API Token").apply {
            setText(prefs.getString("cf_tok", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(label("أنشئ «TURN key» في لوحة Cloudflare (Realtime ← TURN) والصق المعرّف والتوكن هنا. يجدّد التطبيق بيانات TURN القصيرة العمر تلقائياً كل يوم.", 12f, MUTED, false))
            addView(idF, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(tkF, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this).setTitle("Cloudflare TURN (تلقائي)").setView(box)
            .setPositiveButton("حفظ وجلب") { _, _ ->
                prefs.edit().putString("cf_id", idF.text.toString().trim())
                    .putString("cf_tok", tkF.text.toString().trim()).putLong("cf_exp", 0L).apply()
                if (Turn.cfConfigured(prefs)) {
                    toast("جارٍ جلب بيانات TURN...")
                    Turn.cfRefresh(prefs) { ok, m -> toast(if (ok) "تم جلب بيانات TURN ✓ وستتجدد تلقائياً" else "فشل: $m") }
                }
            }
            .setNeutralButton("صفحة الشرح") { _, _ -> openUrl("https://developers.cloudflare.com/realtime/turn/generate-credentials/") }
            .setNegativeButton("إزالة") { _, _ ->
                prefs.edit().remove("cf_id").remove("cf_tok").remove("cf_lines").remove("cf_exp").apply()
                toast("أُزيل Cloudflare")
            }.showStyled()
    }

    private fun testTurn(target: EditText) {
        if (engine != null) { toast("أنهِ المكالمة الحالية أولاً"); return }
        if (Turn.cfConfigured(prefs) && Turn.cfCached(prefs).isEmpty()) {
            toast("جارٍ جلب بيانات Cloudflare...")
            Turn.cfRefresh(prefs) { ok, _ -> if (ok) testTurn(target) }
            return
        }
        val servers = Turn.build(target.text.toString(), Turn.cfCached(prefs), false)
        if (servers.isEmpty()) { toast("أضف خادم TURN أولاً (لصق أو Cloudflare)"); return }
        toast("جارٍ الاختبار... (حتى ١٢ ثانية)")
        Turn.test(this, servers) { _, m -> AlertDialog.Builder(this).setMessage(m).setPositiveButton("حسناً", null).showStyled() }
    }

    private fun relayField(): EditText = field("https://ntfy.example.com").apply {
        textSize = 12f; typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR
        setText(prefs.getString("relay_url", "") ?: "")
    }

    private fun relayCard(f: EditText): LinearLayout = card("🛰 خادم الوسيط (اختياري)", f,
        chipRow(
            chip("📋 لصق") { val t = clipText().trim(); if (t.startsWith("https://")) f.setText(t) else toast("انسخ رابط الخادم (https://...) أولاً") },
            chip("↩ الافتراضي") { f.setText("") }),
        label("الخادم المجاني ntfy.sh يحدّ كل جهاز بـ ٢٥٠ رسالة يومياً. خادم خاص بك يلغي الحد ويزيد الخصوصية. اضبط نفس الرابط على الهاتفين.", 11.5f, MUTED, false))

    private fun saveRelay(f: EditText) { val u = f.text.toString().trim(); prefs.edit().putString("relay_url", u).apply(); Relay.set(u) }
    private fun openUrl(u: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { copy(u) } }

    private fun statusBox(): TextView = label("", 13f, Color.WHITE, false).apply {
        background = rounded(Color.parseColor("#12304A"), 14, Color.parseColor("#1D4E7A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        visibility = View.GONE
    }

    private fun AlertDialog.Builder.showStyled(): AlertDialog {
        val d = show(); Fonts.apply(d.window?.decorView, this@MainActivity); return d
    }

    // ---------- UI ----------
    private fun buildUi() {
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        // setup (not paired yet)
        val sc = screenCol()
        sc.addView(header("SecureCall", "مكالمات فيديو وصوت مشفّرة بين شخصين"))
        myNameField = field("اسمك كما سيظهر عند الطرف الآخر").apply { setText(prefs.getString("pname", "") ?: "") }
        sc.addView(card("اسمك", myNameField), cardLp(26))
        sc.addView(pill("🔗 إنشاء رمز اقتران جديد", true) { createPairing() }, cardLp(18))
        sc.addView(pill("📷 مسح رمز QR من الطرف الآخر", false) { startQrScan() }, cardLp(10))
        pasteCodeField = field("أو الصق رمز الاقتران هنا").apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }
        sc.addView(card("لديّ رمز", pasteCodeField, pill("✅ اقتران", false) { completePairing(pasteCodeField.text.toString().trim(), "") }), cardLp(14))
        sc.addView(label("الاقتران يتم مرة واحدة برمز سري مشترك. لا يستطيع أي طرف ثالث الاتصال بدون هذا الرمز.", 11.5f, MUTED, false).apply { gravity = Gravity.CENTER }, cardLp(18))
        setupScreen = ScrollView(this).apply { addView(sc); isFillViewport = true }

        // home (paired)
        val hc = screenCol()
        hc.addView(header("SecureCall", "اتصال مشفّر"))
        peerLabel = label("", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        hc.addView(card("متصل بـ", peerLabel), cardLp(22))
        hc.addView(pill("📹 اتصال فيديو", true) { startCall(true) }, cardLp(18))
        hc.addView(pill("📞 اتصال صوتي", false) { startCall(false) }, cardLp(10))
        statusHome = statusBox()
        hc.addView(statusHome, cardLp(14))
        turnF = turnField()
        hc.addView(card("خادم TURN (اختياري، موصى به)", turnF, turnBar(turnF),
            label("يجعل الاتصال يعمل على بيانات الجوال وبين الشبكات المختلفة. اضبطه على الهاتفين.", 11.5f, MUTED, false)), cardLp(22))
        relayF = relayField()
        hc.addView(relayCard(relayF), cardLp())
        hc.addView(pill("إلغاء الاقتران", false) { unpair() }, cardLp(22))
        homeScreen = ScrollView(this).apply { addView(hc); isFillViewport = true }

        // call screen
        callLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        remoteView = SurfaceViewRenderer(this); localView = SurfaceViewRenderer(this)
        remoteView.init(egl.eglBaseContext, null); remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.init(egl.eglBaseContext, null); localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.setMirror(true); localView.setZOrderMediaOverlay(true)
        localView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat()) }
        }
        localView.clipToOutline = true
        callLayout.addView(remoteView, FrameLayout.LayoutParams(-1, -1))
        callLayout.addView(localView, FrameLayout.LayoutParams(dp(104), dp(148), Gravity.TOP or Gravity.END).apply { setMargins(dp(14), dp(44), dp(14), 0) })
        diagView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f
            background = rounded(Color.parseColor("#80000000"), 10); setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        callLayout.addView(diagView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(44), 0, 0) })
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.parseColor("#80000000"), 14); setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        callLayout.addView(titleView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(124)) })

        // chat panel (slides over the bottom half of the call screen)
        chatScroll = ScrollView(this)
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        chatScroll.addView(chatList)
        chatInput = field("اكتب رسالة...")
        val sendBtn = circle("➤", ACCENT2) { sendChat() }
        val chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            addView(chatInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        }
        chatPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#E6101828"), 20)
            visibility = View.GONE
            addView(chatScroll, LinearLayout.LayoutParams(-1, dp(260)))
            addView(chatRow)
        }
        callLayout.addView(chatPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(100)) })

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#B3101828"), 40)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        fun addBtn(v: View) = bar.addView(v, LinearLayout.LayoutParams(dp(50), dp(50)).apply { setMargins(dp(4), 0, dp(4), 0) })
        micBtn = circle("🎤", SOFT) {}
        micBtn.setOnClickListener { muted = !muted; engine?.setMuted(muted); micBtn.text = if (muted) "🔇" else "🎤" }
        camBtn = circle("🎥", SOFT) {}
        camBtn.setOnClickListener { cameraOn = !cameraOn; engine?.setCameraOn(cameraOn); camBtn.text = if (cameraOn) "🎥" else "🚫" }
        addBtn(micBtn); addBtn(camBtn)
        addBtn(circle("🔄", SOFT) { engine?.flipCamera() })
        addBtn(circle("💬", SOFT) { chatPanel.visibility = if (chatPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        addBtn(circle("📞", DANGER) { hangup() }.apply { rotation = 135f })
        callLayout.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(36)) })

        for (v in listOf<View>(setupScreen, homeScreen, callLayout)) root.addView(v, FrameLayout.LayoutParams(-1, -1))
        Fonts.apply(root, this)
        setContentView(root)
    }

    private fun show(v: View) {
        if (v === callLayout) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        for (x in listOf<View>(setupScreen, homeScreen, callLayout)) x.visibility = if (x === v) View.VISIBLE else View.GONE
    }

    private fun refreshHome() {
        peerLabel.text = (prefs.getString("peer_name", "") ?: "").ifBlank { "بانتظار أول اتصال" }
        turnF.setText(prefs.getString("turn", "") ?: "")
        relayF.setText(prefs.getString("relay_url", "") ?: "")
    }

    // ---------- pairing ----------
    private fun createPairing() {
        val myName = myNameField.text.toString().trim()
        if (myName.isEmpty()) { toast("أدخل اسمك أولاً"); return }
        val code = CallCrypto.newCode()
        prefs.edit().putString("pname", myName).apply()
        val payload = Qr.encode(myName, code, Turn.normalize(prefs.getString("turn", "") ?: ""),
            prefs.getString("cf_id", "") ?: "", prefs.getString("cf_tok", "") ?: "", prefs.getString("relay_url", "") ?: "")
        val bmp = try { Qr.bitmap(payload, 720) } catch (_: Exception) { null }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(label("اطلب من الطرف الآخر مسح هذا الرمز، أو انسخ الكود النصي وأرسله له بطريقة آمنة. لا تصوّره ولا تشاركه مع أحد غيره.", 12f, MUTED, false))
            if (bmp != null) {
                val img = ImageView(this@MainActivity).apply { setImageBitmap(bmp); setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
                addView(img, LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(10) })
            }
            addView(label(code, 13f, ACCENT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
            addView(pill("📤 نسخ الكود النصي", false) { copy(code) }, cardLp(10))
        }
        AlertDialog.Builder(this).setTitle("رمز الاقتران").setView(box)
            .setPositiveButton("تم") { _, _ -> completePairing(code, myName) }
            .setCancelable(false).showStyled()
    }

    private fun completePairing(rawCode: String, nameAlreadySet: String) {
        val code = CallCrypto.normalize(rawCode)
        if (!CallCrypto.isValid(code)) { toast("رمز الاقتران غير صالح"); return }
        var myName = nameAlreadySet
        if (myName.isBlank()) myName = myNameField.text.toString().trim()
        if (myName.isBlank()) { toast("أدخل اسمك أولاً"); return }
        prefs.edit().putString("pcode", code).putString("pname", myName).apply()
        CallListenService.start(this)
        refreshHome()
        show(homeScreen)
        toast("تم الاقتران ✓")
    }

    private fun unpair() {
        AlertDialog.Builder(this).setTitle("إلغاء الاقتران")
            .setMessage("سيتوقف استقبال المكالمات من الطرف الآخر نهائياً حتى تُقرنا الجهازين من جديد.")
            .setPositiveButton("إلغاء الاقتران") { _, _ ->
                CallListenService.stop(this)
                prefs.edit().remove("pcode").remove("peer_name").apply()
                show(setupScreen)
            }.setNegativeButton("تراجع", null).showStyled()
    }

    private fun startQrScan() {
        try {
            IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("وجّه الكاميرا نحو رمز QR المعروض لدى الطرف الآخر").setBeepEnabled(false).initiateScan()
        } catch (_: Exception) { toast("تعذّر فتح الماسح") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val r = IntentIntegrator.parseActivityResult(rc, res, data) ?: return
        val txt = r.contents ?: return
        val j = Qr.decode(txt) ?: run { toast("هذا ليس رمز اقتران SecureCall"); return }
        prefs.edit().putString("peer_name", j.optString("n")).apply()
        if (j.has("t")) prefs.edit().putString("turn", j.optString("t")).apply()
        if (j.has("r")) prefs.edit().putString("relay_url", j.optString("r")).apply()
        if (j.has("ci") && j.has("ct")) {
            prefs.edit().putString("cf_id", j.optString("ci")).putString("cf_tok", j.optString("ct")).putLong("cf_exp", 0L).apply()
            Turn.cfRefresh(prefs) { _, _ -> }
        }
        completePairing(j.optString("c"), myNameField.text.toString().trim())
    }

    // ---------- outgoing call ----------
    private fun startCall(video: Boolean) {
        val code = prefs.getString("pcode", "") ?: ""
        if (!CallCrypto.isValid(code)) { toast("غير مقترن بعد"); return }
        saveRelay(relayF)
        prefs.edit().putString("turn", turnF.text.toString()).apply()
        callType = if (video) "v" else "a"
        wantVideo = video
        ensurePerms(if (video) listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else listOf(Manifest.permission.RECORD_AUDIO)) {
            startCallService()
            resetCall()
            auto = true
            waitingPeer = true
            val waitStart = System.currentTimeMillis()
            val r = Rendezvous(CallCrypto.roomId(code), { msg -> runOnUiThread { onRemote(msg) } }, { m -> runOnUiThread { status(m) } })
            rv = r
            r.subscribe(System.currentTimeMillis() / 1000 - 5)
            sendHello()
            var helloN = 0
            val delays = longArrayOf(3000, 5000, 7000, 10000, 10000, 15000)
            val tick = object : Runnable {
                override fun run() {
                    if (!waitingPeer) return
                    if (System.currentTimeMillis() - waitStart > 60000) { endCallUi("لم يردّ الطرف الآخر. تأكد أن التطبيق لديه إنترنت."); return }
                    if (Relay.limited()) { endCallUi("وصلت حد رسائل خدمة ntfy المجانية. اضبط خادم وسيط خاص أو انتظر قليلاً."); return }
                    sendHello()
                    ui.postDelayed(this, delays.getOrElse(helloN++) { 15000L })
                }
            }
            ui.postDelayed(tick, 3000)
            status("جارٍ الاتصال بـ ${prefs.getString("peer_name", "") ?: "الطرف الآخر"} ...")
        }
    }

    private fun sendHello() {
        val code = prefs.getString("pcode", "") ?: return
        val myName = prefs.getString("pname", "") ?: "مستخدم"
        rv?.publish(CallCrypto.seal(code, "hello", null, "P|$myId|$myName|$callType")) { }
    }

    private fun becomeCaller() {
        status("وجدت الطرف الآخر، جارٍ تجهيز المكالمة...")
        val keep = rv; rv = null
        val e = beginEngine(1, wantVideo, true)
        rv = keep
        if (e == null) return
        auto = true
        ui.postDelayed(pairTimeout, 45000)
        val code = prefs.getString("pcode", "") ?: return
        e.createOffer({ sdp ->
            val blob = CallCrypto.seal(code, "offer", null, sdp)
            offerBlob = blob
            if (blob.length > 4000) status("رمز الإعداد أطول من الحد المسموح. أضف خادم TURN.")
            else publishRetry(blob) { status("تعذّر الإرسال عبر الإنترنت.") }
        }, { m -> status("خطأ: $m") })
    }

    private fun answerOfferFlow(t: String, sdp: String) {
        val code = prefs.getString("pcode", "") ?: return
        val bind = CallCrypto.digest(t)
        val keep = rv; rv = null
        val e = beginEngine(2, wantVideo, false)
        rv = keep
        if (e == null) return
        auto = true
        lastHandled = t
        ui.postDelayed(pairTimeout, 45000)
        status("جارٍ الرد...")
        e.acceptOffer(sdp, { ans ->
            val blob = CallCrypto.seal(code, "answer", bind, ans)
            if (blob.length > 4000) status("الرد أطول من الحد المسموح. أضف خادم TURN.")
            else publishRetry(blob) { status("تعذّر إرسال الرد.") }
        }, { m -> status("خطأ: $m") })
    }

    private fun publishRetry(blob: String, tries: Int = 3, fail: () -> Unit) {
        val r = rv ?: return
        r.publish(blob) { ok ->
            if (ok) return@publish
            if (tries > 1) ui.postDelayed({ publishRetry(blob, tries - 1, fail) }, 2500)
            else runOnUiThread { fail() }
        }
    }

    private val pairTimeout = Runnable {
        if (callLayout.visibility == View.VISIBLE) return@Runnable
        endCallUi("تعذّر الاتصال. جرّب الواي فاي أو أضف خادم TURN على الهاتفين.")
    }

    private fun beginEngine(r: Int, withVideo: Boolean, controller: Boolean): CallEngine? {
        resetCall()
        role = r
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode; savedSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        val e = CallEngine(applicationContext, egl, localView, remoteView,
            { s -> status(s) }, { showCall() }, { d -> diagView.text = d },
            { c -> onCmd(c) }, { endCallUi() }, { })
        engine = e
        try { e.start(Turn.iceServers(prefs), withVideo, controller) } catch (ex: Exception) {
            status("تعذّر تشغيل الاتصال: ${ex.message}"); return null
        }
        return e
    }

    /** A message arrived from the mailbox. Anything that does not decrypt with the pairing code is ignored. */
    private fun onRemote(msg: String) {
        val code = prefs.getString("pcode", "") ?: return
        val t = Regex("SKC[12]:[A-Za-z0-9_-]+").find(CallCrypto.clean(msg))?.value ?: return
        if (callLayout.visibility == View.VISIBLE) return

        if (waitingPeer) {
            val p = try { CallCrypto.open(code, "hello", null, t, 90 * 1000L) } catch (_: Exception) { return }
            val f = p.split("|")
            if (f.size < 4 || f[0] != "C" || f[3] != myId) return
            if (!seen.add(t)) return
            waitingPeer = false
            peerId = f[1]; peerName = f[2]
            prefs.edit().putString("peer_name", peerName).apply()
            becomeCaller()
            return
        }
        if (role == 1) {
            if (t == lastHandled || t == offerBlob) return
            val sdp = try { CallCrypto.open(code, "answer", CallCrypto.digest(offerBlob), t) } catch (_: Exception) { return }
            lastHandled = t
            status("جارٍ الاتصال...")
            engine?.acceptAnswer(sdp) { m -> status("خطأ: $m") }
            return
        }
        if (waitingOffer) {
            if (t == lastHandled) return
            val sdp = try { CallCrypto.open(code, "offer", null, t, 3 * 60 * 1000L) } catch (_: Exception) { return }
            waitingOffer = false
            answerOfferFlow(t, sdp)
        }
    }

    // ---------- call screen ----------
    private fun showCall() {
        ui.removeCallbacksAndMessages(null)
        rv?.stop(); rv = null
        show(callLayout)
        muted = false; cameraOn = wantVideo
        micBtn.text = "🎤"; camBtn.text = if (wantVideo) "🎥" else "🚫"
        titleView.text = (prefs.getString("peer_name", "") ?: peerName).ifBlank { "متصل" }
        localView.visibility = if (wantVideo) View.VISIBLE else View.GONE
        chatPanel.visibility = View.GONE
        chatList.removeAllViews()
    }

    private fun onCmd(c: String) {
        if (c.startsWith("chat|")) {
            val text = c.removePrefix("chat|")
            addChatBubble(text, mine = false)
        }
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        engine?.sendCmd("chat|$text")
        addChatBubble(text, mine = true)
        chatInput.setText("")
    }

    private fun addChatBubble(text: String, mine: Boolean) {
        val b = label(text, 14f, Color.WHITE, false).apply {
            background = rounded(if (mine) ACCENT2 else SOFT, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.START else Gravity.END
            addView(b, LinearLayout.LayoutParams(-2, -2))
        }
        chatList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hangup() {
        val e = engine
        if (e == null) { endCallUi(); return }
        e.sendCmd("bye")
        ui.postDelayed({ endCallUi() }, 300)
    }

    private fun endCallUi(msg: String = "انتهت المكالمة.") {
        resetCall()
        stopCallService()
        refreshHome()
        show(homeScreen)
        status(msg)
    }

    private fun resetCall() {
        engine?.close(); engine = null
        ui.removeCallbacksAndMessages(null)
        rv?.stop(); rv = null; auto = false; waitingOffer = false; waitingPeer = false
        remoteView.clearImage(); localView.clearImage()
        role = 0; offerBlob = ""; muted = false
        diagView.text = ""; lastHandled = ""
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) { am.mode = savedAudioMode; am.isSpeakerphoneOn = savedSpeaker }
    }

    private fun status(s: String) {
        statusHome.text = s
        statusHome.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startCallService() {
        try { startForegroundService(Intent(this, CallService::class.java)) } catch (_: Exception) {}
        CallService.onHangup = { ui.post { hangup() } }
    }

    private fun stopCallService() {
        try { stopService(Intent(this, CallService::class.java)) } catch (_: Exception) {}
        CallService.onHangup = null
    }

    private fun hasPerms(l: List<String>) = l.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private var pendingAction: (() -> Unit)? = null
    private fun ensurePerms(l: List<String>, action: () -> Unit) {
        if (hasPerms(l)) { action(); return }
        pendingAction = action
        requestPermissions(l.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 1 && res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) pendingAction?.invoke()
        pendingAction = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun copy(t: String) {
        if (t.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", t))
        toast("تم النسخ")
    }

    override fun onPause() {
        engine?.pauseCamera()
        if (this::relayF.isInitialized) saveRelay(relayF)
        if (this::turnF.isInitialized) prefs.edit().putString("turn", turnF.text.toString()).apply()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine?.resumeCamera()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            callLayout.visibility == View.VISIBLE -> hangup()
            homeScreen.visibility == View.VISIBLE && !paired() -> show(setupScreen)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopCallService()
        ui.removeCallbacksAndMessages(null)
        rv?.stop(); rv = null
        engine?.close(); engine = null
        try { remoteView.release(); localView.release() } catch (_: Exception) {}
        try { egl.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

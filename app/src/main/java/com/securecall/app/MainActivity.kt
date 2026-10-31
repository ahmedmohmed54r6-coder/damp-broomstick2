package com.securecall.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
    private lateinit var copyOutBtn: TextView
    private lateinit var shareOutBtn: TextView
    private lateinit var diagView: TextView

    private var engine: CallEngine? = null
    private var role = 0            // 1 = caller, 2 = callee
    private var offerBlob = ""      // caller: the offer it created
    private var outBlob = ""
    private var lastHandled = ""
    private var pendingAction: (() -> Unit)? = null
    private var muted = false
    private var camOn = true
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var rv: Rendezvous? = null
    private var auto = false
    private var waitingOffer = false
    private var waitingPeer = false
    private var myId = ""
    private var peerId = ""
    private var attempts = 0
    private var waitStart = 0L
    private val dead = mutableSetOf<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var svcOn = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        egl = EglBase.create()
        buildUi()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

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
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pill(t: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(if (primary) Color.parseColor("#04141A") else Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val bg: Drawable = if (primary)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2))
                    .apply { cornerRadius = dp(16).toFloat() }
            else rounded(SOFT, 16)
            background = ripple(bg)
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
            views.forEach { v ->
                addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }

    private fun cardLp(top: Int = 14) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        // ---------- setup screen ----------
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val badge = TextView(this).apply {
            text = "🔒"; textSize = 24f; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2))
                .apply { shape = GradientDrawable.OVAL }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("SecureCall", 26f, Color.WHITE, true))
            addView(label("مكالمات فيديو وصوت مشفّرة من طرف إلى طرف", 12.5f, MUTED, false))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        }
        col.addView(header)

        // 1) shared code
        codeField = field("الرمز المشترك — يعرفه أنت وصديقك فقط").apply {
            inputType = inputType or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = Typeface.MONOSPACE
            textSize = 15f
        }
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("🎲 توليد رمز", false) {
                codeField.setText(CallCrypto.newCode())
                toast("انسخ الرمز وأرسله لصديقك مرة واحدة بطريقة آمنة، وليس في نفس محادثة رموز المكالمة")
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(pill("نسخ", false) { copy(codeField.text.toString()) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(card("١ · الرمز المشترك", codeField, codeRow), cardLp(22))

        // 2) TURN
        turnField = field("turn:host:3478,user,pass").apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
        }
        col.addView(
            card("٢ · خادم TURN (موصى به، على الهاتفين)", turnField,
                label("يجعل الاتصال يعمل على بيانات الجوال وبين الشبكات المختلفة، ويخفي عنوان IP عن الطرف الآخر.", 11.5f, MUTED, false)),
            cardLp()
        )

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        codeField.setText(prefs.getString("code", "") ?: "")
        turnField.setText(prefs.getString("turn", "") ?: "")

        // start (automatic, through the mailbox)
        col.addView(pill("📞  اتصال", true) { ensurePerms { startCall() } }, cardLp(18))
        col.addView(
            label("اتفقا على نفس الرمز المشترك، ثم يضغط كلاكما «اتصال». الأول ينتظر والثاني يلتحق به تلقائياً — بدون نسخ أو لصق ولا يهم من يبدأ.\n" +
                "تُستخدم خدمة ntfy.sh كصندوق بريد لنقل رموز مشفّرة فقط، ولا ترى الصوت ولا الفيديو.", 11.5f, MUTED, false)
                .apply { gravity = Gravity.CENTER },
            cardLp(12)
        )

        // manual fallback
        pasteField = field("الصق هنا الرمز الذي وصلك (يعمل تلقائياً)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3; maxLines = 6
            gravity = Gravity.TOP
            textSize = 11f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
        }
        pasteField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onPasted(s?.toString() ?: "") }
        })
        outView = label("", 12f, ACCENT, false)
        copyOutBtn = pill("نسخ رمز المكالمة", false) { copy(outBlob) }.apply { visibility = View.GONE }
        shareOutBtn = pill("مشاركة الرمز", false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, outBlob)
            }, "مشاركة"))
        }.apply { visibility = View.GONE }
        val manual = card(
            "الطريقة اليدوية (احتياطية)",
            pill("ابدأ مكالمة يدوياً (أنا المتصل)", false) { ensurePerms { startCaller() } },
            pasteField, outView, copyOutBtn, shareOutBtn
        ).apply { visibility = View.GONE }
        val toggle = label("▾ الطريقة اليدوية (احتياطية)", 12.5f, ACCENT, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(10))
            isClickable = true
            setOnClickListener {
                manual.visibility = if (manual.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        col.addView(toggle, cardLp(6))
        col.addView(manual, cardLp(0))

        statusView = label("", 13f, Color.WHITE, false).apply {
            background = rounded(Color.parseColor("#12304A"), 14, Color.parseColor("#1D4E7A"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        col.addView(statusView, cardLp())

        setup = ScrollView(this).apply { addView(col); isFillViewport = true }
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
        localView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat())
            }
        }
        localView.clipToOutline = true
        callLayout.addView(remoteView, FrameLayout.LayoutParams(-1, -1))
        callLayout.addView(localView, FrameLayout.LayoutParams(dp(104), dp(148), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(14), dp(44), dp(14), 0)
        })
        diagView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f
            background = rounded(Color.parseColor("#80000000"), 10)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        callLayout.addView(diagView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(14), dp(44), 0, 0)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#B3101828"), 40)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        fun addBtn(v: View) = bar.addView(v, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        val micBtn = circle("🎤", SOFT) {}
        micBtn.setOnClickListener {
            muted = !muted; engine?.setMuted(muted); micBtn.text = if (muted) "🔇" else "🎤"
        }
        val camBtn = circle("📷", SOFT) {}
        camBtn.setOnClickListener {
            camOn = !camOn; engine?.setCameraOn(camOn); camBtn.text = if (camOn) "📷" else "🚫"
        }
        addBtn(micBtn)
        addBtn(camBtn)
        addBtn(circle("🔄", SOFT) { engine?.flipCamera() })
        addBtn(circle("📞", DANGER) { hangup() }.apply { rotation = 135f })
        callLayout.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, dp(36))
        })
        root.addView(callLayout, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    // ---------- flow ----------
    /** Keeps the call alive in the background (camera + microphone + network) while it is running. */
    private fun startCallService() {
        CallService.onHangup = { hangup() }
        try {
            val i = Intent(this, CallService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            svcOn = true
        } catch (e: Exception) {
            svcOn = false
        }
    }

    private fun stopCallService() {
        CallService.onHangup = null
        svcOn = false
        try { stopService(Intent(this, CallService::class.java)) } catch (_: Exception) {}
    }

    private fun ensurePerms(action: () -> Unit) {
        if (!CallCrypto.isValid(codeField.text.toString())) {
            toast("الرمز المشترك غير صالح. ولّد رمزاً عشوائياً أو الصق الرمز الذي أرسله صديقك.")
            return
        }
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { startCallService(); action() } else {
            pendingAction = { startCallService(); action() }
            requestPermissions(need.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc != 1) return
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

    private fun beginEngine(r: Int): CallEngine? {
        resetCall()
        role = r
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode; savedSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true

        val e = CallEngine(applicationContext, egl, localView, remoteView,
            { s -> status(s) }, { showCall() }, { d -> diagView.text = d })
        engine = e
        try { e.start(iceServers()) } catch (ex: Exception) {
            status("تعذّر تشغيل الاتصال: ${ex.message}"); return null
        }
        return e
    }

    /** Caller: creates the call code, copies it, waits for the friend's reply. */
    private fun startCaller() {
        val e = beginEngine(1) ?: return
        val code = codeField.text.toString()
        status("جارٍ إنشاء رمز المكالمة...")
        e.createOffer({ sdp ->
            offerBlob = CallCrypto.seal(code, "offer", null, sdp)
            showOut(offerBlob)
            copy(offerBlob)
            status("تم نسخ رمز المكالمة. الصقه في المحادثة وأرسله لصديقك. عندما يردّ عليك، الصق ردّه في الخانة السفلية.\n" + e.candInfo)
        }, { m -> status("خطأ: $m") })
    }

    private fun saveCfg() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("code", codeField.text.toString())
            .putString("turn", turnField.text.toString()).apply()
    }

    private fun checkStrong(code: String): Boolean {
        if (CallCrypto.isStrong(code)) return true
        toast("الرمز المشترك ضعيف أو غير صالح. اضغط «توليد رمز» للحصول على رمز آمن.")
        return false
    }

    private fun newMailbox(code: String): Rendezvous =
        Rendezvous(CallCrypto.roomId(code),
            { msg -> runOnUiThread { onRemote(msg) } },
            { m -> runOnUiThread { status(m) } })

    private fun sendHello(code: String) {
        val blob = CallCrypto.seal(code, "hello", null, myId)
        rv?.publish(blob) { _ -> }
    }

    /** Re-announces presence every 15 s while waiting for the friend (up to 5 minutes). */
    private val helloTick = object : Runnable {
        override fun run() {
            if (!waitingPeer) return
            if (System.currentTimeMillis() - waitStart > 5 * 60 * 1000L) {
                resetCall()
                stopCallService()
                status("انتهت مهلة الانتظار (5 دقائق) ولم يلتحق صديقك. اضغط «اتصال» للمحاولة من جديد.")
                return
            }
            sendHello(codeField.text.toString())
            ui.postDelayed(this, 15000)
        }
    }

    /** If the two phones do not connect within 45 s, try again with a fresh start (up to 3 tries). */
    private val pairTimeout = Runnable {
        if (auto && callLayout.visibility != View.VISIBLE) {
            if (peerId.isNotEmpty()) dead.add(peerId)
            if (attempts >= 2) {
                resetCall()
                stopCallService()
                status("تعذّر الاتصال بعد عدة محاولات. جرّب الواي فاي على الهاتفين، أو أضف خادم TURN على الهاتفين.")
            } else {
                attempts++
                status("لم ينجح الاتصال، أعيد المحاولة تلقائياً...")
                startCall(true)
            }
        }
    }

    /** One button for both people: announce presence in the mailbox; whoever finds the other pairs up. */
    private fun startCall(retry: Boolean = false) {
        val code = codeField.text.toString()
        if (!checkStrong(code)) return
        saveCfg()
        if (!retry) attempts = 0
        resetCall()
        auto = true
        waitingPeer = true
        waitStart = System.currentTimeMillis()
        myId = CallCrypto.newCode().filter { it != '-' }.take(10)
        peerId = ""
        val r = newMailbox(code)
        rv = r
        r.subscribe(System.currentTimeMillis() / 1000 - 90)
        sendHello(code)
        ui.postDelayed(helloTick, 15000)
        status("بانتظار صديقك... اطلب منه أن يفتح التطبيق ويضغط «اتصال» بنفس الرمز المشترك.")
    }

    /** The friend's presence message arrived: the larger id becomes the caller (same result on both phones). */
    private fun onHello(code: String, id: String) {
        if (id == myId || id in dead) return
        waitingPeer = false
        ui.removeCallbacks(helloTick)
        peerId = id
        if (myId > id) becomeCaller(code) else becomeCallee()
    }

    private fun becomeCaller(code: String) {
        status("وجدت صديقك، جارٍ تجهيز المكالمة...")
        val keep = rv
        rv = null                       // resetCall() inside beginEngine must not stop the mailbox
        val e = beginEngine(1)
        rv = keep
        if (e == null) return
        auto = true
        ui.postDelayed(pairTimeout, 45000)
        e.createOffer({ sdp ->
            val blob = CallCrypto.seal(code, "offer", null, sdp)
            offerBlob = blob
            if (blob.length > 4000) {
                status("الرمز أطول من الحد المسموح للإرسال التلقائي. استعمل الطريقة اليدوية.")
                showOut(blob); copy(blob)
            } else {
                rv?.publish(blob) { ok ->
                    runOnUiThread { if (!ok) status("تعذّر الإرسال عبر الإنترنت. تحقق من الاتصال.") }
                }
            }
        }, { m -> status("خطأ: $m") })
    }

    private fun becomeCallee() {
        status("وجدت صديقك، بانتظار بدء المكالمة...")
        val keep = rv
        rv = null
        resetCall()
        rv = keep
        auto = true
        waitingOffer = true
        ui.postDelayed(pairTimeout, 45000)
    }

    private fun answerOffer(code: String, t: String, sdp: String) {
        val bind = CallCrypto.digest(t)
        val keep = rv
        rv = null
        val e = beginEngine(2)
        rv = keep
        if (e == null) return
        auto = true
        lastHandled = t
        ui.postDelayed(pairTimeout, 45000)
        status("وصلتك المكالمة، جارٍ الرد...")
        e.acceptOffer(sdp, { ans ->
            val blob = CallCrypto.seal(code, "answer", bind, ans)
            if (blob.length > 4000) {
                status("الرد أطول من الحد المسموح للإرسال التلقائي. استعمل الطريقة اليدوية.")
                showOut(blob); copy(blob)
            } else {
                rv?.publish(blob) { ok ->
                    runOnUiThread { if (!ok) status("تعذّر إرسال الرد. تحقق من الاتصال بالإنترنت.") }
                }
            }
        }, { m -> status("خطأ: $m") })
    }

    /** A message arrived from the mailbox. Own messages and anything that does not decrypt are ignored. */
    private fun onRemote(msg: String) {
        val t = Regex("SKC1:[A-Za-z0-9_-]+").find(CallCrypto.clean(msg))?.value ?: return
        if (callLayout.visibility == View.VISIBLE) return
        val code = codeField.text.toString()
        if (waitingPeer) {
            val id = try {
                CallCrypto.open(code, "hello", null, t, 90 * 1000L)
            } catch (_: Exception) { return }
            onHello(code, id)
            return
        }
        if (t == lastHandled || t == offerBlob) return
        if (role == 1) {
            val sdp = try {
                CallCrypto.open(code, "answer", CallCrypto.digest(offerBlob), t)
            } catch (_: Exception) { return }
            lastHandled = t
            status("جارٍ الاتصال...")
            engine?.acceptAnswer(sdp) { m -> status("خطأ: $m") }
        } else if (waitingOffer || role == 2) {
            val sdp = try {
                CallCrypto.open(code, "offer", null, t, 3 * 60 * 1000L)
            } catch (_: Exception) { return }
            answerOffer(code, t, sdp)
        }
    }

    /** Called when text is pasted: the app works out by itself whether it is a call code or a reply. */
    private fun onPasted(raw: String) {
        if (auto) return
        val t = Regex("SKC1:[A-Za-z0-9_-]+").find(CallCrypto.clean(raw))?.value ?: return
        if (t.length < 200) return
        if (t == lastHandled) return
        lastHandled = t
        val code = codeField.text.toString()
        if (!CallCrypto.isValid(code)) {
            toast("اكتب الرمز المشترك أولاً (نفس الرمز عند الطرفين).")
            lastHandled = ""
            return
        }
        try {
            when (role) {
                0 -> { // friend: a call code arrived
                    val sdp = CallCrypto.open(code, "offer", null, t)
                    val bind = CallCrypto.digest(t)
                    ensurePerms {
                        val e = beginEngine(2) ?: return@ensurePerms
                        status("جارٍ إنشاء الرد...")
                        e.acceptOffer(sdp, { ans ->
                            val blob = CallCrypto.seal(code, "answer", bind, ans)
                            showOut(blob)
                            copy(blob)
                            status("تم نسخ الرد. الصقه في المحادثة وأرسله لصديقك، والاتصال يبدأ تلقائياً.\n" + e.candInfo)
                        }, { m -> status("خطأ: $m") })
                    }
                }
                1 -> { // caller: the reply arrived
                    val e = engine ?: return
                    val sdp = CallCrypto.open(code, "answer", CallCrypto.digest(offerBlob), t)
                    status("جارٍ الاتصال...")
                    e.acceptAnswer(sdp) { m -> status("خطأ: $m") }
                }
                else -> toast("تم استلام الرمز من قبل.")
            }
        } catch (ex: Exception) {
            lastHandled = ""
            status("الرمز غير صحيح، أو الرمز المشترك مختلف عن رمز صديقك، أو انتهت صلاحيته (30 دقيقة).")
        }
    }

    private fun showOut(blob: String) {
        outBlob = blob
        outView.text = "تم إنشاء الرمز (${blob.length} حرف). انسخه أو شاركه."
        copyOutBtn.visibility = View.VISIBLE
        shareOutBtn.visibility = View.VISIBLE
    }

    private fun showCall() {
        ui.removeCallbacksAndMessages(null)
        rv?.stop(); rv = null
        setup.visibility = View.GONE
        callLayout.visibility = View.VISIBLE
    }

    private fun hangup() {
        resetCall()
        stopCallService()
        callLayout.visibility = View.GONE
        setup.visibility = View.VISIBLE
        status("انتهت المكالمة.")
    }

    private fun resetCall() {
        engine?.close(); engine = null
        ui.removeCallbacksAndMessages(null)
        rv?.stop(); rv = null; auto = false; waitingOffer = false; waitingPeer = false
        remoteView.clearImage(); localView.clearImage()
        role = 0; offerBlob = ""; outBlob = ""
        muted = false; camOn = true
        pasteField.setText("")
        outView.text = ""
        diagView.text = ""
        lastHandled = ""
        copyOutBtn.visibility = View.GONE
        shareOutBtn.visibility = View.GONE
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            am.mode = savedAudioMode; am.isSpeakerphoneOn = savedSpeaker
        }
    }

    private fun status(s: String) {
        statusView.text = s
        statusView.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun copy(t: String) {
        if (t.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("code", t))
        toast("تم النسخ")
    }

    override fun onPause() { if (!svcOn) engine?.pauseCamera(); super.onPause() }
    override fun onResume() { super.onResume(); engine?.resumeCamera() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (callLayout.visibility == View.VISIBLE) hangup() else super.onBackPressed()
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

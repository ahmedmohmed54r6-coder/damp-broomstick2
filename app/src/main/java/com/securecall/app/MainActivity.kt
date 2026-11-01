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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.media.AudioManager
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
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.security.MessageDigest

/**
 * SecureCall Family.
 *  - Parent phone (mode 1): one entry per child (name + pairing code). Buttons: voice call / watch.
 *  - Child phone (mode 2): paired once with the code, name and a PIN. Waits quietly and answers the
 *    parent automatically. While the camera is live the child's screen shows a clear red banner and the
 *    notification stays visible. Leaving child mode needs the PIN.
 * Parent is always the caller, child always the callee. Setup messages are AES-GCM encrypted with the
 * pairing code; the media itself is DTLS-SRTP end to end.
 */
class MainActivity : Activity() {
    private lateinit var egl: EglBase
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var modeScreen: ScrollView
    private lateinit var parentScreen: ScrollView
    private lateinit var childSetup: ScrollView
    private lateinit var childStandby: LinearLayout
    private lateinit var callLayout: FrameLayout
    private lateinit var parentList: LinearLayout
    private lateinit var newKidName: EditText
    private lateinit var childName: EditText
    private lateinit var childCode: EditText
    private lateinit var childPin: EditText
    private lateinit var turnP: EditText
    private lateinit var turnC: EditText
    private lateinit var wakeUrl: EditText
    private lateinit var readyView: TextView
    private lateinit var relayP: EditText
    private lateinit var relayC: EditText
    private lateinit var statusP: TextView
    private lateinit var standbyTitle: TextView
    private lateinit var standbyStatus: TextView
    private lateinit var diagView: TextView
    private lateinit var titleView: TextView
    private lateinit var camBanner: TextView
    private lateinit var bar: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var vidBtn: TextView
    private lateinit var msgView: TextView
    private lateinit var standbyMsg: TextView
    private lateinit var levelView: TextView
    private lateinit var locCard: LinearLayout
    private lateinit var locView: TextView

    /** Encrypted at rest (AES-256-GCM, AndroidX Security, key held in the hardware Keystore) —
     *  this is where the pairing code (the master secret), child name and PIN hash live, so a
     *  plain root/backup file read on the device no longer exposes them in clear text. Falls back
     *  to a normal file only if the Keystore itself is unavailable (very old/broken devices). */
    private val prefs by lazy { Store.prefs(this) }
    companion object {
        /** True while the child's app is on screen (the background listener then leaves calls to the app). */
        @Volatile var childVisible = false
    }

    private val ui = Handler(Looper.getMainLooper())
    /** Periodic ticks live here, NOT in [ui]: resetCall()/showCall() clear every callback of [ui]. */
    private val tickH = Handler(Looper.getMainLooper())

    private var mode = 0                // 1 = parent, 2 = child
    private var engine: CallEngine? = null
    private var role = 0                // 1 = caller (parent), 2 = callee (child)
    private var curName = ""            // parent: chosen child's name | child: its own name
    private var curCode = ""
    private var peerName = ""
    private var wantVideo = false
    private var callType = "a"          // a = call, l = listen (baby monitor), v = watch
    private var baseTitle = ""
    private val bg = Handler(Looper.getMainLooper())   // location timers (not cleared by call resets)
    private var locRv: Rendezvous? = null
    private var locReqId = ""
    private var locKid: Kid? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var liveReq = ""
    private var liveUntil = 0L
    private var locating = false
    private var pendingRequired: List<String> = emptyList()
    private var loudCount = 0
    private var lastAlert = 0L
    private var myId = ""
    private var parentId = ""
    private var offerBlob = ""
    private var lastHandled = ""
    private var pendingAction: (() -> Unit)? = null
    private var muted = false
    private var remoteVideoOn = false
    private var remoteBack = false
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var savedVol = -1
    private var rv: Rendezvous? = null
    private var notifWasEnabled = true
    private var lastNotifAlertMs = 0L
    /** Parent only: one quiet mailbox listener per saved child, active while the parent screen is
     *  open, so an "A|notif_off" alert from any of them is caught even outside an active call. */
    private val parentWatchers = mutableMapOf<String, Rendezvous>()
    private var auto = false
    private var waitingPeer = false
    private var waitingOffer = false
    private var inSession = false       // child: a parent call is being set up
    private var attempts = 0
    private var waitStart = 0L
    private val seen = mutableSetOf<String>()
    private var svcOn = false
    private var lastHbMs = 0L
    private var cfBusy = false
    private var lastCfTry = 0L
    private val seenLabels = mutableMapOf<String, TextView>()
    private val offlineAlerted = mutableSetOf<String>()
    private var tickerOn = false
    private var watchStartMs = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        egl = EglBase.create()
        buildUi()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        mode = prefs.getInt("mode", 0)
        when {
            mode == 2 && prefs.getBoolean("cactive", false) &&
                hasPerms(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) -> activateChild()
            mode == 1 -> { refreshParent(); show(parentScreen); startParentWatchers() }
            mode == 2 -> show(childSetup)
            else -> show(modeScreen)
        }
        Relay.load(prefs)
        handleWakeIntent(intent)
        tickH.postDelayed(notifWatchTick, 20000)
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
            views.forEach { v ->
                addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }

    private fun cardLp(top: Int = 14) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    // ---------- UI building ----------
    private fun screenCol(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(40), dp(18), dp(28))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun header(title: String, sub: String): LinearLayout {
        val badge = TextView(this).apply {
            text = "👨‍👩‍👧"; textSize = 22f; gravity = Gravity.CENTER
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
        textSize = 12f
        minLines = 2; maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE
        textDirection = View.TEXT_DIRECTION_LTR
        setText(prefs.getString("turn", "") ?: "")
    }

    private fun chip(t: String, onClick: () -> Unit): TextView = pill(t, false, onClick).apply {
        textSize = 12f; setPadding(dp(6), dp(11), dp(6), dp(11))
    }

    private fun chipRow(vararg chips: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private fun clipText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    }

    /** One-tap helpers under the TURN field: paste (iceServers JSON or "url,user,pass" lines), free
     *  sign-up pages, Cloudflare auto-renewal, a real relay test, and copy to the other phone. */
    private fun turnBar(target: EditText): LinearLayout {
        val paste = chip("📋 لصق") {
            val norm = normalizeTurn(clipText())
            if (norm.isEmpty()) toast("الحافظة لا تحتوي إعداد TURN. انسخه من لوحة المزوّد ثم اضغط لصق.")
            else { target.setText(norm); toast("تم ضبط خادم TURN ✓ — اضغط اختبار للتأكد") }
        }
        val copyB = chip("📤 نسخ") {
            val t = normalizeTurn(target.text.toString())
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
            val cfOn = Turn.cfConfigured(prefs)
            if (cfOn) addView(label("☁ Cloudflare التلقائي مفعّل ✓", 11f, ACCENT, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun cloudflareDialog() {
        fun mono(hint: String) = field(hint).apply {
            textSize = 12f; typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR
        }
        val idF = mono("Turn Token ID").apply { setText(prefs.getString("cf_id", "") ?: "") }
        val tkF = mono("API Token").apply {
            setText(prefs.getString("cf_tok", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(label("أنشئ «TURN key» في لوحة Cloudflare (Realtime ← TURN) والصق المعرّف والتوكن هنا. " +
                "يجدّد التطبيق بيانات TURN القصيرة العمر تلقائياً كل يوم.", 12f, MUTED, false))
            addView(idF, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(tkF, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("Cloudflare TURN (تلقائي)")
            .setView(box)
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
            }
            .showStyled()
    }

    private fun testTurn(target: EditText) {
        if (engine != null) { toast("أنهِ المكالمة الحالية أولاً"); return }
        if (Turn.cfConfigured(prefs) && Turn.cfCached(prefs).isEmpty()) {
            toast("جارٍ جلب بيانات Cloudflare...")
            Turn.cfRefresh(prefs) { ok, m -> if (ok) testTurn(target) else toast(m) }
            return
        }
        val servers = Turn.build(target.text.toString(), Turn.cfCached(prefs), false)
        if (servers.isEmpty()) { toast("أضف خادم TURN أولاً (لصق أو Cloudflare)"); return }
        toast("جارٍ الاختبار... (حتى ١٢ ثانية)")
        Turn.test(this, servers) { _, m ->
            AlertDialog.Builder(this).setMessage(m).setPositiveButton("حسناً", null).showStyled()
        }
    }

    private fun relayField(): EditText = field("https://ntfy.example.com").apply {
        textSize = 12f; typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR
        setText(prefs.getString("relay_url", "") ?: "")
    }

    private fun relayCard(f: EditText): LinearLayout = card("🛰 خادم الوسيط (اختياري)", f,
        chipRow(
            chip("📋 لصق") { val t = clipText().trim(); if (t.startsWith("https://")) f.setText(t) else toast("انسخ رابط الخادم (https://...) أولاً") },
            chip("↩ الافتراضي") { f.setText("") }),
        label("الخادم المجاني ntfy.sh يحدّ كل جهاز بـ ٢٥٠ رسالة يومياً. خادم ntfy خاص بك يلغي الحد ويزيد الخصوصية. اضبط نفس الرابط على الهاتفين (أو يُنقل برمز QR).", 11.5f, MUTED, false))

    private fun saveRelay(f: EditText) {
        val u = f.text.toString().trim()
        prefs.edit().putString("relay_url", u).apply()
        Relay.set(u)
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { copy(u) }
    }

    private fun normalizeTurn(raw: String): String = Turn.normalize(raw)

    private fun statusBox(): TextView = label("", 13f, Color.WHITE, false).apply {
        background = rounded(Color.parseColor("#12304A"), 14, Color.parseColor("#1D4E7A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        visibility = View.GONE
    }

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        // ---------- mode screen ----------
        val mc = screenCol()
        mc.addView(header("SecureCall عائلة", "نداء ومراقبة مشفّرة بين هاتف الأهل وهاتف الطفل"))
        mc.addView(label("اختر دور هذا الهاتف:", 14f, Color.WHITE, true), cardLp(30))
        mc.addView(pill("👨‍👩‍👧  هاتف الأهل (أتصل وأتابع)", true) { setMode(1) }, cardLp(12))
        mc.addView(pill("🧒  هاتف الطفل (يجيب تلقائياً)", false) { setMode(2) }, cardLp(12))
        mc.addView(
            label("يتم الاقتران مرة واحدة برمز مشترك سري. لا يمكن لأي شخص آخر الاتصال بهاتف الطفل بدون هذا الرمز.", 11.5f, MUTED, false)
                .apply { gravity = Gravity.CENTER }, cardLp(18)
        )
        modeScreen = ScrollView(this).apply { addView(mc); isFillViewport = true }

        // ---------- parent screen ----------
        val pc = screenCol()
        pc.addView(header("هاتف الأهل", "اضغط على اسم الطفل للاتصال بهاتفه"))
        parentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(parentList, cardLp(18))
        locView = label("", 13f, Color.WHITE, false)
        locCard = card("📍 موقع الطفل", locView,
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("🗺 الخريطة", true) { openMap() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                addView(pill("📋 نسخ", false) { copy("$lastLat,$lastLon") },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                addView(pill("⏹ إيقاف", false) { stopLocWatch(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            }).apply { visibility = View.GONE }
        pc.addView(locCard, cardLp(12))
        newKidName = field("اسم الطفل (مثلاً: سارة)")
        pc.addView(
            card("إضافة طفل جديد", newKidName,
                pill("🔗 إنشاء رمز اقتران", false) { addKid() },
                label("أنشئ الرمز ثم أدخله مرة واحدة في هاتف الطفل (وضع الطفل). أرسله بطريقة آمنة.", 11.5f, MUTED, false)),
            cardLp(14)
        )
        turnP = turnField()
        pc.addView(
            card("خادم TURN (اختياري، موصى به)", turnP, turnBar(turnP),
                label("يجعل الاتصال يعمل على بيانات الجوال وبين الشبكات المختلفة. ضعه على الهاتفين.", 11.5f, MUTED, false)),
            cardLp()
        )
        wakeUrl = field("https://...run.app").apply {
            textSize = 12f; typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR
            setText(prefs.getString("wake_url", "") ?: "")
        }
        pc.addView(
            card("⚡ إيقاظ فوري عبر FCM (اختياري)", wakeUrl,
                chipRow(
                    chip("📋 لصق") { val t = clipText().trim(); if (t.startsWith("https://")) wakeUrl.setText(t) else toast("انسخ رابط الدالة أولاً") },
                    chip("🧪 اختبار") {
                        Fcm.wakeRoom(wakeUrl.text.toString(), "sc-" + "0".repeat(40)) { ok, m ->
                            runOnUiThread { toast(if (ok) "✅ الدالة تعمل ($m)" else "❌ فشل: $m") }
                        }
                    }),
                label("يوقظ هاتف الطفل فوراً حتى لو أغلقه نظام الهاتف. يحتاج إعداد Firebase مرة واحدة (SETUP_FCM.md).", 11.5f, MUTED, false)),
            cardLp())
        relayP = relayField()
        pc.addView(relayCard(relayP), cardLp())
        statusP = statusBox()
        pc.addView(statusP, cardLp())
        pc.addView(pill("⬅ تغيير دور الهاتف", false) { show(modeScreen) }, cardLp(18))
        parentScreen = ScrollView(this).apply { addView(pc); isFillViewport = true }

        // ---------- child setup screen ----------
        val cc = screenCol()
        cc.addView(header("هاتف الطفل", "إعداد لمرة واحدة"))
        cc.addView(pill("📷 مسح رمز QR من هاتف الأهل (الأسرع)", true) { startQrScan() }, cardLp(18))
        childName = field("اسم الطفل (كما سيظهر عند الأهل)").apply {
            setText(prefs.getString("cname", "") ?: "")
        }
        childCode = field("رمز الاقتران من هاتف الأهل").apply {
            inputType = inputType or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setText(prefs.getString("ccode", "") ?: "")
        }
        childPin = field("رمز PIN سري للأهل (4 أرقام أو أكثر)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        cc.addView(card("١ · اسم الطفل", childName), cardLp(22))
        cc.addView(card("٢ · رمز الاقتران", childCode), cardLp())
        cc.addView(
            card("٣ · PIN للخروج من وضع الطفل", childPin,
                label("لن يستطيع إيقاف وضع الطفل أو الخروج منه إلا من يعرف الـ PIN.", 11.5f, MUTED, false)),
            cardLp()
        )
        turnC = turnField()
        cc.addView(card("٤ · خادم TURN (اختياري)", turnC, turnBar(turnC)), cardLp())
        relayC = relayField()
        cc.addView(relayCard(relayC), cardLp())
        cc.addView(pill("✅ تفعيل وضع الطفل", true) { onActivateChild() }, cardLp(18))
        cc.addView(
            pill("🔋 استثناء التطبيق من توفير البطارية", false) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }, cardLp(10)
        )
        cc.addView(pill("⬅ تغيير دور الهاتف", false) { show(modeScreen) }, cardLp(10))
        childSetup = ScrollView(this).apply { addView(cc); isFillViewport = true }

        // ---------- child standby screen ----------
        childStandby = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        childStandby.addView(label("🧒", 56f, Color.WHITE, false).apply { gravity = Gravity.CENTER })
        standbyTitle = label("", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        childStandby.addView(standbyTitle, cardLp(12))
        standbyStatus = label("", 14f, ACCENT, false).apply { gravity = Gravity.CENTER }
        childStandby.addView(standbyStatus, cardLp(10))
        standbyMsg = label("", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#B45309"), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        childStandby.addView(standbyMsg, cardLp(16))
        childStandby.addView(
            label("هذا الهاتف يستقبل نداء الأهل تلقائياً حتى لو أُغلقت الشاشة أو خرجت من التطبيق. اترك الإنترنت شغّالاً.", 11.5f, MUTED, false)
                .apply { gravity = Gravity.CENTER }, cardLp(18)
        )
        readyView = label("", 12f, MUTED, false).apply { gravity = Gravity.CENTER }
        childStandby.addView(readyView, cardLp(10))
        childStandby.addView(pill("🩺 فحص الجاهزية", false) { showReadiness(false) }, cardLp(10))
        childStandby.addView(pill("⚙️ إيقاف وضع الطفل (PIN)", false) { askPin { leaveChild() } }, cardLp(26))

        // ---------- call screen ----------
        callLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
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
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.parseColor("#80000000"), 14)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        callLayout.addView(titleView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, dp(124))
        })
        camBanner = TextView(this).apply {
            text = "🔴 الكاميرا تعمل الآن — الأهل يشاهدونك"
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.parseColor("#CCB91C1C"), 14)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        callLayout.addView(camBanner, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, dp(170))
        })

        msgView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.parseColor("#E6B45309"), 18)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        callLayout.addView(msgView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })
        levelView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            background = rounded(Color.parseColor("#80000000"), 10)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            visibility = View.GONE
        }
        callLayout.addView(levelView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(44), dp(14), 0)
        })

        // parent's control bar (the child phone has no controls)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#B3101828"), 40)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        fun addBtn(v: View) = bar.addView(v, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        micBtn = circle("🎤", SOFT) {}
        micBtn.setOnClickListener {
            muted = !muted; engine?.setMuted(muted); micBtn.text = if (muted) "🔇" else "🎤"
        }
        vidBtn = circle("🎥", SOFT) {}
        vidBtn.setOnClickListener {
            remoteVideoOn = !remoteVideoOn
            engine?.sendCmd(if (!remoteVideoOn) "video:off" else if (remoteBack) "cam:back" else "cam:front")
            vidBtn.text = if (remoteVideoOn) "🎥" else "🚫"
            scheduleGlance()
        }
        addBtn(micBtn)
        addBtn(vidBtn)
        addBtn(circle("🔄", SOFT) {
            remoteBack = !remoteBack
            remoteVideoOn = true
            vidBtn.text = "🎥"
            engine?.sendCmd(if (remoteBack) "cam:back" else "cam:front")
            scheduleGlance()
            toast(if (remoteBack) "الكاميرا الخلفية في هاتف الطفل" else "الكاميرا الأمامية في هاتف الطفل")
        })
        addBtn(circle("✉️", SOFT) {
            askMessage { text -> engine?.sendCmd("msg:$text"); toast("أُرسلت الرسالة") }
        })
        addBtn(circle("📞", DANGER) { hangup() }.apply { rotation = 135f })
        callLayout.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, dp(36))
        })

        for (v in listOf<View>(modeScreen, parentScreen, childSetup, childStandby, callLayout))
            root.addView(v, FrameLayout.LayoutParams(-1, -1))
        Fonts.apply(root, this)
        setContentView(root)
    }

    private fun show(v: View) {
        // The screen stays on only during a call; in standby it may switch off to save battery.
        if (v === callLayout) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        for (x in listOf<View>(modeScreen, parentScreen, childSetup, childStandby, callLayout))
            x.visibility = if (x === v) View.VISIBLE else View.GONE
    }

    private fun setMode(m: Int) {
        mode = m
        prefs.edit().putInt("mode", m).apply()
        if (m == 1) { refreshParent(); show(parentScreen); startParentWatchers() } else show(childSetup)
    }

    // ---------- parent: children list ----------
    private data class Kid(val name: String, val code: String)

    private fun loadKids(): MutableList<Kid> {
        val out = mutableListOf<Kid>()
        try {
            val a = JSONArray(prefs.getString("kids", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Kid(o.getString("name"), o.getString("code")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveKids(l: List<Kid>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("name", it.name).put("code", it.code)) }
        prefs.edit().putString("kids", a.toString()).apply()
    }

    /** Starts (or re-syncs) one listener per saved child. Safe to call repeatedly — already-running
     *  listeners for kids still on the list are left alone; a kid removed from the list has its
     *  listener stopped. */
    private fun startParentWatchers() {
        val codes = loadKids().associateBy { it.code }
        val gone = parentWatchers.keys - codes.keys
        gone.forEach { parentWatchers.remove(it)?.stop() }
        codes.values.forEach { k ->
            if (parentWatchers.containsKey(k.code)) return@forEach
            val r = Rendezvous(CallCrypto.roomId(k.code), { raw -> handleChildAlert(k, raw) }, { })
            r.subscribe(System.currentTimeMillis() / 1000 - 60)
            parentWatchers[k.code] = r
        }
        if (!tickerOn) {
            tickerOn = true; watchStartMs = System.currentTimeMillis()
            autoPings = 0; lastAutoPing = watchStartMs
            pingAll()
            tickH.post(seenTick)
        }
    }

    private fun stopParentWatchers() {
        tickerOn = false
        tickH.removeCallbacks(seenTick)
        parentWatchers.values.forEach { it.stop() }
        parentWatchers.clear()
    }

    private fun handleChildAlert(k: Kid, raw: String) {
        val t = Regex("SKC[12]:[A-Za-z0-9_-]+").find(CallCrypto.clean(raw))?.value ?: return
        val p = try { CallCrypto.open(k.code, "msg", null, t, 20 * 60 * 1000L) } catch (_: Exception) { return }
        val f = p.split("|")
        if (f.size < 3 || f[0] != "A" || !seen.add(t)) return
        when (f[1]) {
            "notif_off" -> {
                val ts = f[2].toLongOrNull() ?: 0L
                if (System.currentTimeMillis() / 1000 - ts < 120) runOnUiThread { alertNotificationsDisabled(k.name) }
            }
            "alm" -> {
                val ts = f.getOrNull(3)?.toLongOrNull() ?: 0L
                if (System.currentTimeMillis() / 1000 - ts < 120) runOnUiThread { toast("✅ وصل المنبه إلى هاتف ${k.name}") }
            }
            "pong" -> {
                val ts = f[2].toLongOrNull() ?: return
                if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 45) return   // an old answer is not proof
                val room = CallCrypto.roomId(k.code)
                val batt = f.getOrNull(3)?.toIntOrNull() ?: -1
                val now = System.currentTimeMillis()
                pongAt[k.code] = now
                prefs.edit().putLong("seen_$room", now).putInt("batt_$room", batt).apply()
                runOnUiThread { updateSeenLabels() }
            }
        }
    }

    private fun alertNotificationsDisabled(childName: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ar")).format(java.util.Date())
        val title = "تنبيه: $childName عطّل إشعارات SecureCall"
        val text = "الساعة $time. السحب السريع للكاميرا/المايكروفون قد يختفي معه. تواصل معه مباشرة إن احتجت."
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("securecall_alert", "تنبيهات SecureCall", android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(
                this, 3, Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "securecall_alert")
            else android.app.Notification.Builder(this).setPriority(android.app.Notification.PRIORITY_HIGH)
            b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
            nm.notify(("alert_" + childName).hashCode(), b.build())
        } catch (_: Exception) {}
        if (parentScreen.visibility == View.VISIBLE) toast(title)
    }

    private fun refreshParent() {
        parentList.removeAllViews()
        seenLabels.clear()
        val kids = loadKids()
        if (kids.isEmpty()) {
            parentList.addView(label("لا يوجد أطفال بعد. أضف طفلاً من الأسفل.", 13f, MUTED, false))
            return
        }
        kids.forEach { k ->
            fun row(vararg p: Pair<String, () -> Unit>) = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                p.forEachIndexed { i, (t, f) ->
                    addView(pill(t, true) { f() },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) marginEnd = dp(8) })
                }
            }
            val callRow = row("🎤 نداء صوتي" to { callKid(k, "a") }, "👂 استماع" to { callKid(k, "l") })
            val watchRow = row("📹 صوت وصورة" to { callKid(k, "v") }, "✉️ رسالة" to {
                askMessage { text -> sendMailboxMsg(k, text) }
            })
            val quietRow = row("🌙 نظرة هادئة" to { callKid(k, "q") }, "⏰ منبه" to { alarmsDialog(k) })
            val locRow = row("📍 موقعه الآن" to { requestLocation(k, false) }, "📡 تتبع مباشر" to { requestLocation(k, true) })
            val manageRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("📋 الرمز", false) { showKidCode(k) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(pill("📷 QR", false) { showKidQr(k) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(pill("🗑 حذف", false) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("حذف ${k.name}؟")
                        .setMessage("سيتوقف هاتف هذا الطفل عن الاقتران بك. يمكنك إضافته من جديد برمز جديد.")
                        .setPositiveButton("حذف") { _, _ ->
                            saveKids(loadKids().filter { it.code != k.code }); refreshParent()
                        }
                        .setNegativeButton("إلغاء", null).showStyled()
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val seen = label("", 12f, MUTED, false)
            seenLabels[k.code] = seen
            seen.setOnClickListener { pingKid(k); updateSeenLabels() }
            parentList.addView(card("🧒 ${k.name}", seen, callRow, watchRow, quietRow, locRow, manageRow), cardLp(10))
        }
        updateSeenLabels()
    }

    // ---------- alarms: set from the parent, rings on the child phone even without internet ----------
    private fun almKey(k: Kid) = "alm_" + CallCrypto.roomId(k.code)

    private fun loadAlarmList(k: Kid): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val a = JSONArray(prefs.getString(almKey(k), "[]"))
            val now = System.currentTimeMillis()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optBoolean("r") || o.optLong("w") > now) out.add(o)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveAlarmList(k: Kid, l: List<JSONObject>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        prefs.edit().putString(almKey(k), a.toString()).apply()
    }

    private fun alarmsDialog(k: Kid) {
        val list = loadAlarmList(k)
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val items = mutableListOf("➕ منبه جديد")
        list.forEach { o ->
            items.add("⏰ " + fmt.format(java.util.Date(o.optLong("w"))) +
                (if (o.optBoolean("r")) " · كل يوم" else "") +
                (if (o.optString("l").isNotEmpty()) " · " + o.optString("l") else "") + "   (اضغط للإلغاء)")
        }
        AlertDialog.Builder(this).setTitle("منبهات ${k.name}")
            .setItems(items.toTypedArray<CharSequence>()) { _, i -> if (i == 0) newAlarm(k) else cancelAlarm(k, list[i - 1]) }
            .setNegativeButton("إغلاق", null).showStyled()
    }

    private fun newAlarm(k: Kid) {
        val c = java.util.Calendar.getInstance()
        android.app.TimePickerDialog(this, { _, h, m -> askAlarmOptions(k, h, m) },
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun askAlarmOptions(k: Kid, h: Int, m: Int) {
        val lbl = field("اسم المنبه (اختياري)").apply { setText("استيقظ") }
        val daily = CheckBox(this).apply { text = "كرّر كل يوم"; setTextColor(Color.WHITE) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(lbl)
            addView(daily, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        AlertDialog.Builder(this).setTitle("منبه %02d:%02d".format(h, m)).setView(box)
            .setPositiveButton("إرسال") { _, _ ->
                sendAlarm(k, h, m, daily.isChecked, lbl.text.toString().trim().replace("|", " "))
            }
            .setNegativeButton("إلغاء", null).showStyled()
    }

    private fun sendAlarm(k: Kid, h: Int, m: Int, rep: Boolean, label: String) {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, m)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val id = "%02d%02d".format(h, m)
        val w = c.timeInMillis
        val payload = "AL|$id|${System.currentTimeMillis()}|$w|${if (rep) 1 else 0}|$label"
        Rendezvous(CallCrypto.roomId(k.code), { }, { }).publish(CallCrypto.seal(k.code, "alm", null, payload)) { ok ->
            runOnUiThread {
                if (ok) {
                    val l = loadAlarmList(k).filter { it.optString("id") != id }.toMutableList()
                    l.add(JSONObject().put("id", id).put("w", w).put("r", rep).put("l", label))
                    saveAlarmList(k, l)
                    toast("أُرسل المنبه ✓ يصل الهاتف عند اتصاله بالإنترنت، وبعدها يرنّ حتى بدون إنترنت")
                    Fcm.wake(prefs.getString("wake_url", "") ?: "", k.code, true)
                } else toast("تعذّر الإرسال، تحقق من الإنترنت")
            }
        }
    }

    private fun cancelAlarm(k: Kid, o: JSONObject) {
        val id = o.optString("id")
        Rendezvous(CallCrypto.roomId(k.code), { }, { })
            .publish(CallCrypto.seal(k.code, "alm", null, "AC|$id|${System.currentTimeMillis()}")) { ok ->
                runOnUiThread {
                    if (ok) {
                        saveAlarmList(k, loadAlarmList(k).filter { it.optString("id") != id })
                        toast("أُرسل طلب الإلغاء")
                    } else toast("تعذّر الإرسال، تحقق من الإنترنت")
                }
            }
    }

    /** Child: a parent's alarm request (kind "alm", valid 12 h so it also works if the phone was offline when sent). */
    private fun handleAlarmMsg(t: String): Boolean {
        val p = try { CallCrypto.open(curCode, "alm", null, t, 12 * 3600 * 1000L) } catch (_: Exception) { return false }
        if (!seen.add(t)) return true
        val f = p.split("|", limit = 6)
        val id: String
        when {
            f[0] == "AL" && f.size >= 6 -> {
                id = f[1]
                Alarms.apply(this, id, f[2].toLongOrNull() ?: 0L, f[3].toLongOrNull() ?: 0L, f[4] == "1", f[5])
            }
            f[0] == "AC" && f.size >= 3 -> {
                id = f[1]
                Alarms.cancel(this, id, f[2].toLongOrNull() ?: 0L)
            }
            else -> return true
        }
        rv?.publish(CallCrypto.seal(curCode, "msg", null, "A|alm|$id|${System.currentTimeMillis() / 1000}")) { }
        return true
    }

    private fun showKidQr(k: Kid) {
        val payload = Qr.encode(k.name, k.code, normalizeTurn(prefs.getString("turn", "") ?: ""),
            prefs.getString("cf_id", "") ?: "", prefs.getString("cf_tok", "") ?: "", prefs.getString("relay_url", "") ?: "")
        val bmp = try { Qr.bitmap(payload, 720) } catch (e: Exception) { toast("الرمز كبير جداً. قلّل إعدادات TURN"); return }
        val img = ImageView(this).apply {
            setImageBitmap(bmp); setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(label("امسحه من هاتف ${k.name} (وضع الطفل ← مسح QR). يحوي الرمز السري: لا تصوّره ولا ترسله.", 12f, MUTED, false))
            addView(img, LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(10) })
        }
        val d = AlertDialog.Builder(this).setTitle("QR اقتران ${k.name}").setView(box)
            .setPositiveButton("إغلاق", null).create()
        d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        d.show()
        Fonts.apply(d.window?.decorView, this)
    }

    private fun startQrScan() {
        try {
            IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("وجّه الكاميرا نحو رمز QR المعروض في هاتف الأهل")
                .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        } catch (e: Exception) { toast("تعذّر فتح الماسح") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val r = IntentIntegrator.parseActivityResult(rc, res, data) ?: return
        val txt = r.contents ?: return
        val j = Qr.decode(txt)
        if (j == null) { toast("هذا ليس رمز اقتران SecureCall"); return }
        childName.setText(j.optString("n")); childCode.setText(j.optString("c"))
        if (j.has("t")) turnC.setText(j.optString("t"))
        if (j.has("r")) relayC.setText(j.optString("r"))
        if (j.has("ci") && j.has("ct")) {
            prefs.edit().putString("cf_id", j.optString("ci")).putString("cf_tok", j.optString("ct")).putLong("cf_exp", 0L).apply()
            Turn.cfRefresh(prefs) { _, _ -> }
        }
        toast("تم ملء البيانات ✓ أدخل رمز PIN ثم اضغط تفعيل")
    }

    // ---------- parent: is the child phone really reachable? (live ping/pong, nothing runs in the background) ----------
    private val pingAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pongAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var autoPings = 0
    private var lastAutoPing = 0L

    private val seenTick = object : Runnable {
        override fun run() {
            updateSeenLabels()
            val now = System.currentTimeMillis()
            if (autoPings < 12 && now - lastAutoPing > 150000L) { lastAutoPing = now; autoPings++; pingAll() }
            tickH.postDelayed(this, 5000)
        }
    }

    private fun pingKid(k: Kid) {
        pingAt[k.code] = System.currentTimeMillis()
        Rendezvous(CallCrypto.roomId(k.code), { }, { })
            .publish(CallCrypto.seal(k.code, "msg", null, "PG|${System.currentTimeMillis() / 1000}")) { ok ->
                if (!ok) { pingAt[k.code] = 1L; runOnUiThread { updateSeenLabels() } }
            }
    }

    private fun pingAll() = loadKids().forEach { pingKid(it) }

    private fun updateSeenLabels() {
        val now = System.currentTimeMillis()
        loadKids().forEach { k ->
            val room = CallCrypto.roomId(k.code)
            val lbl = seenLabels[k.code] ?: return@forEach
            val ping = pingAt[k.code] ?: 0L
            val pong = pongAt[k.code] ?: 0L
            val last = prefs.getLong("seen_$room", 0L)
            val batt = prefs.getInt("batt_$room", -1)
            val bt = if (batt >= 0) " · 🔋 $batt%" else ""
            val lastTxt = if (last > 0) "آخر ظهور " + timeFmt.format(java.util.Date(last)) else "لم يظهر بعد"
            val limited = if (Relay.limited()) "\n⚠️ وصلت حد رسائل ntfy المجاني (٢٥٠/يوم). انتظر أو استخدم خادماً خاصاً." else ""
            when {
                ping == 0L && pong == 0L -> { lbl.text = "⚪ جارٍ فحص الاتصال..."; lbl.setTextColor(MUTED) }
                ping == 1L -> { lbl.text = "⚠️ تعذّر الفحص (لا إنترنت عندك أو حد الرسائل)$limited"; lbl.setTextColor(DANGER) }
                pong >= ping && now - pong < 200000L -> {
                    lbl.text = "🟢 متصل الآن · تأكّد قبل ${(now - pong) / 1000} ث$bt"; lbl.setTextColor(ACCENT)
                }
                pong < ping && now - ping > 20000L -> {
                    lbl.text = "🔴 لا يردّ — $lastTxt$bt\n(اضغط هنا للفحص من جديد)$limited"; lbl.setTextColor(DANGER)
                }
                pong < ping -> { lbl.text = "⏳ جارٍ الفحص..."; lbl.setTextColor(MUTED) }
                else -> { lbl.text = "⚪ آخر تأكّد قبل ${(now - pong) / 60000} د — اضغط هنا للتحديث"; lbl.setTextColor(MUTED) }
            }
            if (ping > 1L && pong < ping && now - ping > 20000L && now - watchStartMs > 25000L) {
                if (offlineAlerted.add(k.code))
                    postAlert("off_" + k.name, "هاتف ${k.name} لا يردّ",
                        "لم يردّ على فحص الاتصال. قد لا تصل المكالمة حتى يعود للاتصال بالإنترنت أو يُفتح وضع الطفل.")
            } else if (pong >= ping && pong > 0L) offlineAlerted.remove(k.code)
        }
    }

    private fun postAlert(key: String, title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("securecall_alert", "تنبيهات SecureCall", android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(
                this, 3, Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "securecall_alert")
            else android.app.Notification.Builder(this).setPriority(android.app.Notification.PRIORITY_HIGH)
            b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
            nm.notify(key.hashCode(), b.build())
        } catch (_: Exception) {}
    }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val l = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        return if (l < 0 || sc <= 0) -1 else l * 100 / sc
    }

    private fun addKid() {
        val name = newKidName.text.toString().trim().replace("|", " ")
        if (name.isEmpty()) { toast("اكتب اسم الطفل أولاً"); return }
        val k = Kid(name, CallCrypto.newCode())
        val l = loadKids(); l.add(k); saveKids(l)
        newKidName.setText("")
        refreshParent()
        showKidCode(k)
    }

    private fun showKidCode(k: Kid) {
        AlertDialog.Builder(this)
            .setTitle("رمز اقتران ${k.name}")
            .setMessage(k.code + "\n\nأدخل هذا الرمز مرة واحدة في هاتف الطفل (وضع الطفل). لا ترسله في مكان عام.")
            .setPositiveButton("نسخ") { _, _ -> copy(k.code) }
            .setNeutralButton("مشاركة") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, k.code)
                }, "مشاركة"))
            }
            .setNegativeButton("إغلاق", null).showStyled()
    }

    // ---------- permissions / service ----------
    private fun hasPerms(l: List<String>) = l.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun ensurePerms(l: List<String>, optional: List<String> = emptyList(), action: () -> Unit) {
        val need = (l + optional).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) action() else {
            pendingAction = action
            pendingRequired = l
            requestPermissions(need.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc != 1) return
        if (hasPerms(pendingRequired)) pendingAction?.invoke()
        else toast("يلزم السماح بالميكروفون (وبالكاميرا في هاتف الطفل)")
        pendingAction = null
    }

    /** Asks the user (standard Android system dialog) to make SecureCall a device admin, so
     *  Settings requires "deactivate admin" before "Uninstall" is usable on this phone.
     *  Shown once, when child mode is activated; the system dialog explains why, in the
     *  description text from strings.xml, before the user accepts or declines. */
    private fun requestUninstallProtection() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(this, ChildDeviceAdmin::class.java)
        if (dpm.isAdminActive(admin)) return
        try {
            startActivity(Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_description)
                )
            })
        } catch (_: Exception) {}
    }

    private fun startCallService(child: Boolean) {
        CallService.onHangup = { hangup() }
        try {
            val i = Intent(this, CallService::class.java).putExtra(CallService.EXTRA_CHILD, child)
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

    private fun iceServers(): List<PeerConnection.IceServer> = Turn.iceServers(prefs)

    /** PIN hashing: PBKDF2-HMAC-SHA256, 120,000 rounds, random 16-byte salt per install — far
     *  slower to brute-force than a single SHA-256 pass, and the salt stops a precomputed table
     *  from working even if the stored hash is ever read. */
    private fun hashPin(pin: String, saltHex: String): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    private fun newSalt(): String {
        val b = ByteArray(16); java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit().putString("pinsalt", salt).putString("pinh", hashPin(pin, salt)).apply()
    }

    private fun checkPin(pin: String): Boolean {
        val salt = prefs.getString("pinsalt", "") ?: ""
        val stored = prefs.getString("pinh", "") ?: ""
        if (salt.isEmpty() || stored.isEmpty()) return false
        return hashPin(pin, salt) == stored
    }

    private fun askPin(onOk: () -> Unit) {
        val fails = prefs.getInt("pinfails", 0)
        val lockUntil = prefs.getLong("pinlock", 0L)
        if (lockUntil > System.currentTimeMillis()) {
            val mins = (lockUntil - System.currentTimeMillis()) / 60000 + 1
            toast("عدة محاولات خاطئة. حاول بعد $mins دقيقة.")
            return
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("أدخل رمز PIN الخاص بالأهل")
            .setView(et)
            .setPositiveButton("موافق") { _, _ ->
                if (checkPin(et.text.toString())) {
                    prefs.edit().putInt("pinfails", 0).apply()
                    onOk()
                } else {
                    val n = fails + 1
                    val e = prefs.edit().putInt("pinfails", n)
                    // backs off: 5th wrong try locks 1 min, 10th locks 10 min, 15th+ locks 30 min
                    if (n >= 15) e.putLong("pinlock", System.currentTimeMillis() + 30 * 60000)
                    else if (n >= 10) e.putLong("pinlock", System.currentTimeMillis() + 10 * 60000)
                    else if (n >= 5) e.putLong("pinlock", System.currentTimeMillis() + 60000)
                    e.apply()
                    toast("رمز PIN غير صحيح")
                }
            }
            .setNegativeButton("إلغاء", null).showStyled()
    }

    private fun dim(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // ---------- child mode ----------
    private fun onActivateChild() {
        val name = childName.text.toString().trim().replace("|", " ")
        val code = childCode.text.toString()
        val pin = childPin.text.toString()
        if (name.isEmpty()) { toast("اكتب اسم الطفل"); return }
        if (!CallCrypto.isStrong(code)) { toast("رمز الاقتران غير صالح. انسخه من هاتف الأهل كما هو."); return }
        val hasPin = (prefs.getString("pinh", "") ?: "").isNotEmpty()
        if (pin.length < 4 && !(hasPin && pin.isEmpty())) { toast("رمز PIN يجب أن يكون 4 أرقام على الأقل"); return }
        prefs.edit()
            .putString("cname", name).putString("ccode", code)
            .putString("turn", turnC.text.toString()).putBoolean("cactive", true)
            .apply()
        saveRelay(relayC)
        if (pin.isNotEmpty()) setPin(pin)
        ensurePerms(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) { activateChild() }
    }

    /** Once, right after activating child mode: asks (via the real system dialogs — nothing silent)
     *  to be excluded from battery optimization, and on manufacturers known to kill background apps
     *  aggressively (Xiaomi, Huawei/Honor, Oppo, Vivo, OnePlus, ...), offers to open that phone's own
     *  "autostart / keep running" settings screen. Both are the user's own call-to-action; nothing here
     *  bypasses what the person chooses on those screens. This is also exactly why a call may not reach
     *  a child's phone unless the app is already open on some of these ROMs when these are skipped. */
    private fun maybeAskBackgroundReliability() {
        if (prefs.getBoolean("askedBgReliability", false)) return
        prefs.edit().putBoolean("askedBgReliability", true).apply()
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val needsBattery = !pm.isIgnoringBatteryOptimizations(packageName)
        val needsAutostart = autoStartIntent() != null
        if (!needsBattery && !needsAutostart) return
        AlertDialog.Builder(this)
            .setTitle("مهم لضمان وصول نداء الأهل")
            .setMessage(
                "بعض الهواتف (خصوصًا شاومي/هواوي/أوبو/فيفو) توقف التطبيقات بالخلفية تلقائيًا إلا لو " +
                "استثنيتها بنفسك. بدون هذا، ممكن ما يرن التطبيق إلا لو كان مفتوحًا فعليًا.\n\n" +
                "الخطوتين التاليتين من إعدادات هاتفك مباشرة (مش من داخل تطبيقنا):"
            )
            .setPositiveButton("افتح الإعدادات") { _, _ ->
                if (needsBattery) requestBatteryExemption()
                if (needsAutostart) ui.postDelayed({ openAutoStartSettings() }, if (needsBattery) 1200 else 0)
            }
            .setNegativeButton("لاحقًا", null)
            .showStyled()
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) {}
        }
    }

    /** Known per-manufacturer "autostart / protected apps" screens. Returns the first one that
     *  actually resolves on this device, or null if none match (most phones don't need this). */
    private fun autoStartIntent(): Intent? {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
        )
        val pm = packageManager
        for ((pkg, cls) in candidates) {
            val i = Intent().setComponent(android.content.ComponentName(pkg, cls))
            if (i.resolveActivity(pm) != null) return i
        }
        return null
    }

    private fun openAutoStartSettings() {
        val i = autoStartIntent()
        if (i != null) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        toast("دوّر على \"بدء تلقائي\" أو \"تشغيل بالخلفية\" في إعدادات هذا التطبيق")
    }

    // ---------- child readiness: everything a call needs while the app is closed ----------
    private class Gap(val text: String, val fix: () -> Unit)

    private fun readinessGaps(): List<Gap> {
        val g = mutableListOf<Gap>()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!nm.areNotificationsEnabled()) g.add(Gap("الإشعارات مغلقة. على بعض الهواتف (شاومي خصوصاً) يوقف النظام التطبيق معها فلا تصل المكالمات.") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) {}
        })
        if (!pm.isIgnoringBatteryOptimizations(packageName)) g.add(Gap("استثناء توفير البطارية غير مفعّل.") { requestBatteryExemption() })
        if (!Settings.canDrawOverlays(this)) g.add(Gap("«العرض فوق التطبيقات» غير مفعّل. هو الذي يسمح للتطبيق بالفتح عند النداء وهو مغلق.") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        })
        if (android.os.Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) g.add(Gap("إشعارات ملء الشاشة غير مفعّلة.") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        })
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) g.add(Gap("الموقع «طوال الوقت» غير مفعّل. بدونه لا يُرسل الموقع والتطبيق مغلق.") {
            try { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3) } catch (_: Exception) {}
        })
        return g
    }

    private fun updateReadyLabel() {
        if (!this::readyView.isInitialized) return
        val n = readinessGaps().size
        readyView.text = if (n == 0) "✅ الهاتف جاهز لاستقبال النداء" else "⚠️ $n إعدادات ناقصة قد تمنع وصول المكالمة — اضغط «فحص الجاهزية»"
        readyView.setTextColor(if (n == 0) ACCENT else DANGER)
    }

    private fun showReadiness(auto: Boolean) {
        if (mode != 2) return
        val gaps = readinessGaps()
        updateReadyLabel()
        if (gaps.isEmpty()) { if (!auto) toast("✅ كل شيء جاهز لاستقبال نداء الأهل"); return }
        val auto2 = autoStartIntent() != null
        val msg = gaps.joinToString("\n\n") { "⚠️ " + it.text } +
            (if (auto2) "\n\nوفي هذا الهاتف فعّل أيضاً «التشغيل التلقائي» وثبّت التطبيق في الخلفية." else "")
        val b = AlertDialog.Builder(this).setTitle("جاهزية استقبال المكالمات").setMessage(msg)
            .setPositiveButton("إصلاح التالي") { _, _ -> gaps[0].fix() }
            .setNegativeButton("لاحقاً", null)
        if (auto2) b.setNeutralButton("التشغيل التلقائي") { _, _ -> openAutoStartSettings() }
        b.showStyled()
    }

    /** Android 14+: a call-style full-screen notification needs the user's OK in a system screen. */
    private fun maybeAskFullScreen() {
        if (android.os.Build.VERSION.SDK_INT < 34 || !Fcm.available(this)) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.canUseFullScreenIntent() || prefs.getBoolean("askedFsi", false)) return
        prefs.edit().putBoolean("askedFsi", true).apply()
        AlertDialog.Builder(this)
            .setTitle("السماح بفتح التطبيق عند نداء الأهل")
            .setMessage("حتى يظهر التطبيق على الشاشة فور النداء، فعّل «إشعارات ملء الشاشة» لهذا التطبيق في الشاشة التالية.")
            .setPositiveButton("فتح الإعداد") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            .setNegativeButton("لاحقاً", null).showStyled()
    }

    private fun handleWakeIntent(i: Intent?) {
        val ii = i ?: return
        val alarm = ii.getStringExtra("alarm")
        val quiet = ii.getBooleanExtra("quiet", false)
        if (!ii.getBooleanExtra("wake", false) && alarm == null) return
        if (quiet && alarm == null) dim(true)          // quiet look: don't light the screen up
        else if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        ii.removeExtra("wake"); ii.removeExtra("quiet"); ii.removeExtra("alarm")
        if (alarm != null) showAlarmDialog(alarm)
    }

    private var alarmDlg: AlertDialog? = null

    private fun showAlarmDialog(label: String) {
        try { alarmDlg?.dismiss() } catch (_: Exception) {}
        alarmDlg = AlertDialog.Builder(this).setTitle("⏰ منبه من الأهل").setMessage(label)
            .setCancelable(false)
            .setPositiveButton("إيقاف") { _, _ -> Alarms.stopRinging(this) }
            .showStyled()
    }

    private fun AlertDialog.Builder.showStyled(): AlertDialog {
        val d = show()
        Fonts.apply(d.window?.decorView, this@MainActivity)
        return d
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun activateChild() {
        mode = 2
        curName = prefs.getString("cname", "") ?: ""
        curCode = prefs.getString("ccode", "") ?: ""
        Fcm.subscribe(this, curCode)
        Alarms.scheduleAll(this)
        ChildListenService.start(this)
        tickH.postDelayed({ showReadiness(true) }, 800)
        requestUninstallProtection()
        dim(true)
        standbyTitle.text = "هاتف $curName"
        show(childStandby)
        startStandby()
    }

    private fun leaveChild() {
        stopLive()
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, ChildDeviceAdmin::class.java)
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        } catch (_: Exception) {}
        Fcm.unsubscribe(this, prefs.getString("ccode", "") ?: "")
        ChildListenService.stop(this)
        prefs.edit().putBoolean("cactive", false).apply()
        resetCall()
        inSession = false
        stopCallService()
        dim(false)
        show(childSetup)
    }

    /** Child: listen quietly to the mailbox; a valid hello from the parent starts an automatic answer. */
    private fun startStandby() {
        resetCall()
        inSession = false
        myId = newId()
        val r = newMailbox(curCode)
        rv = r
        r.subscribe(System.currentTimeMillis() / 1000 - 6 * 3600)   // also picks up alarms sent while offline
        status("")
    }

    private fun childReturnToStandby() {
        stopCallService()   // call is over: the notification goes away with it
        dim(true)
        show(childStandby)
        startStandby()
    }

    private fun onCmd(c: String) {
        if (mode != 2) return
        if (c.startsWith("msg:")) {
            val text = c.removePrefix("msg:")
            msgView.text = "💬 $text"
            msgView.visibility = View.VISIBLE
            buzz(700)
            notifyChild("رسالة من الأهل", text)
            return
        }
        when (c) {
            "video:off" -> camBanner.visibility = View.GONE
            "video:on", "cam:front", "cam:back" -> camBanner.visibility = View.VISIBLE
        }
    }

    // ---------- parent: start a call ----------
    private fun callKid(k: Kid, type: String) {
        curName = k.name; curCode = k.code; callType = type; wantVideo = type == "v" || type == "q"
        prefs.edit().putString("turn", turnP.text.toString()).putString("wake_url", wakeUrl.text.toString().trim()).apply()
        saveRelay(relayP)
        stopLocWatch(false)
        val go = {
            ensurePerms(listOf(Manifest.permission.RECORD_AUDIO)) {
                startCallService(false)
                startParentCall()
                Fcm.wake(prefs.getString("wake_url", "") ?: "", k.code, type == "q")   // wake-up bell, in parallel with the normal request
            }
        }
        if (Turn.cfConfigured(prefs) && Turn.cfCached(prefs).isEmpty()) {
            toast("جارٍ تجديد بيانات TURN...")
            Turn.cfRefresh(prefs) { _, _ -> go() }
        } else go()
    }

    private fun parentHello() = "P|$myId|parent|$callType"

    private fun startParentCall(retry: Boolean = false) {
        if (!CallCrypto.isStrong(curCode)) {
            toast("رمز الاقتران غير صالح. احذف الطفل وأضفه من جديد."); stopCallService(); return
        }
        if (!retry) attempts = 0
        resetCall()
        auto = true
        waitingPeer = true
        waitStart = System.currentTimeMillis()
        myId = newId()
        val r = newMailbox(curCode)
        rv = r
        r.subscribe(System.currentTimeMillis() / 1000 - 5)
        sendHello(parentHello())
        helloN = 0
        ui.postDelayed(helloTick, 3000)
        status("جارٍ الاتصال بهاتف $curName ...")
    }

    private fun newId() = CallCrypto.newCode().filter { it != '-' }.take(10)

    private fun newMailbox(code: String): Rendezvous =
        Rendezvous(CallCrypto.roomId(code),
            { msg -> runOnUiThread { onRemote(msg) } },
            { m -> runOnUiThread { status(m) } })

    private fun sendHello(payload: String) {
        rv?.publish(CallCrypto.seal(curCode, "hello", null, payload)) { _ -> }
    }

    /** Parent: repeats the call request every 15 s for up to 60 s. */
    private val helloTick = object : Runnable {
        override fun run() {
            if (!waitingPeer) return
            if (System.currentTimeMillis() - waitStart > 60 * 1000L) {
                endCallUi("لم يردّ هاتف $curName. تأكد أنه متصل بالإنترنت وأن التطبيق يعمل بوضع الطفل.")
                return
            }
            if (Relay.limited()) {
                endCallUi("وصلت حد رسائل خدمة ntfy المجانية (٢٥٠ يومياً). انتظر قليلاً أو اضبط خادم وسيط خاصاً من بطاقة «خادم الوسيط».")
                return
            }
            sendHello(parentHello())
            ui.postDelayed(this, helloDelays.getOrElse(helloN++) { 15000L })
        }
    }
    private var helloN = 0
    /** Early retries are quick (the child may still be waking up), later ones are spaced out. */
    private val helloDelays = longArrayOf(3000, 5000, 7000, 10000, 10000, 15000)

    /** Child phone only: every 20 s, checks whether the child has turned the app's notifications
     *  off from system Settings (their right — this never re-enables them or blocks that choice)
     *  and tells the parent right away through the same encrypted mailbox when they do, so the
     *  parent finds out immediately instead of assuming the standby notification is still there.
     *  Resends every 15 min while it stays off, in case the first alert didn't reach them. */
    private val notifWatchTick = object : Runnable {
        override fun run() {
            if (mode == 2 && prefs.getBoolean("cactive", false) && curCode.isNotEmpty()) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val enabled = nm.areNotificationsEnabled()
                val now = System.currentTimeMillis()
                if (!enabled && (notifWasEnabled || now - lastNotifAlertMs > 15 * 60 * 1000L)) {
                    rv?.publish(CallCrypto.seal(curCode, "msg", null, "A|notif_off|${now / 1000}")) { }
                    lastNotifAlertMs = now
                }
                notifWasEnabled = enabled
            }
            if (Turn.cfNeedsRefresh(prefs) && !cfBusy && System.currentTimeMillis() - lastCfTry > 10 * 60 * 1000L) {
                cfBusy = true; lastCfTry = System.currentTimeMillis()
                Turn.cfRefresh(prefs) { _, _ -> cfBusy = false }
            }
            tickH.postDelayed(this, 20000)
        }
    }

    private val pairTimeout = Runnable {
        if (callLayout.visibility == View.VISIBLE) return@Runnable
        if (mode == 2) { stopCallService(); status(""); startStandby(); return@Runnable }
        if (attempts >= 2) {
            endCallUi("تعذّر الاتصال بعد عدة محاولات. جرّب الواي فاي أو أضف خادم TURN على الهاتفين.")
        } else {
            attempts++
            status("لم ينجح الاتصال، أعيد المحاولة تلقائياً...")
            startParentCall(true)
        }
    }

    private fun beginEngine(r: Int, withVideo: Boolean, controller: Boolean, lowRes: Boolean = false): CallEngine? {
        resetCall()
        role = r
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode; savedSpeaker = am.isSpeakerphoneOn
        savedVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        // Child phone: loud speaker so the parent's voice is heard across the room.
        if (mode == 2) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
            am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)

        val e = CallEngine(applicationContext, egl, localView, remoteView,
            { s -> status(s) }, { showCall() }, { d -> diagView.text = d },
            { c -> onCmd(c) },
            { if (mode == 2) childReturnToStandby() else endCallUi() },
            { l -> onLevel(l) })
        engine = e
        try { e.start(iceServers(), withVideo, controller, lowRes) } catch (ex: Exception) {
            status("تعذّر تشغيل الاتصال: ${ex.message}"); return null
        }
        return e
    }

    /** Sends a setup code; retries twice (2.5 s apart) before giving up, so one lost request doesn't kill the call. */
    private fun publishRetry(blob: String, tries: Int = 3, fail: () -> Unit) {
        val r = rv ?: return
        r.publish(blob) { ok ->
            if (ok) return@publish
            if (tries > 1) ui.postDelayed({ publishRetry(blob, tries - 1, fail) }, 2500)
            else runOnUiThread { fail() }
        }
    }

    private fun becomeCaller() {
        status("وجدت هاتف $curName، جارٍ تجهيز المكالمة...")
        val keep = rv
        rv = null                       // resetCall() inside beginEngine must not stop the mailbox
        val e = beginEngine(1, false, true)
        rv = keep
        if (e == null) return
        auto = true
        ui.postDelayed(pairTimeout, 45000)
        e.createOffer({ sdp ->
            val blob = CallCrypto.seal(curCode, "offer", null, sdp)
            offerBlob = blob
            if (blob.length > 4000) {
                status("رمز الإعداد أطول من الحد المسموح. أضف خادم TURN وجرّب مرة أخرى.")
            } else {
                publishRetry(blob) { status("تعذّر الإرسال عبر الإنترنت. تحقق من الاتصال.") }
            }
        }, { m -> status("خطأ: $m") })
    }

    private fun answerOffer(t: String, sdp: String) {
        val bind = CallCrypto.digest(t)
        val keep = rv
        rv = null
        val e = beginEngine(2, wantVideo, false, callType == "l" || callType == "q")
        rv = keep
        if (e == null) return
        auto = true
        lastHandled = t
        ui.postDelayed(pairTimeout, 45000)
        status("الأهل يتصلون، جارٍ الرد تلقائياً...")
        e.acceptOffer(sdp, { ans ->
            val blob = CallCrypto.seal(curCode, "answer", bind, ans)
            if (blob.length > 4000) {
                status("الرد أطول من الحد المسموح. أضف خادم TURN على الهاتفين.")
            } else {
                publishRetry(blob) { status("تعذّر إرسال الرد. تحقق من الاتصال بالإنترنت.") }
            }
        }, { m -> status("خطأ: $m") })
    }

    /** A message arrived from the mailbox. Anything that does not decrypt with the pairing code is ignored. */
    private fun onRemote(msg: String) {
        val t = Regex("SKC[12]:[A-Za-z0-9_-]+").find(CallCrypto.clean(msg))?.value ?: return
        if (mode == 2 && !ChildListenService.running && handleLocReq(t)) return
        if (mode == 2 && !ChildListenService.running && handleAlarmMsg(t)) return
        if (callLayout.visibility == View.VISIBLE) return
        val code = curCode

        if (mode == 1) {
            if (waitingPeer) {
                // the child answers the call request with: C|childId|childName|parentId
                val p = try { CallCrypto.open(code, "hello", null, t, 90 * 1000L) } catch (_: Exception) { return }
                val f = p.split("|")
                if (f.size < 4 || f[0] != "C" || f[3] != myId) return
                if (!seen.add(t)) return
                waitingPeer = false
                ui.removeCallbacks(helloTick)
                peerName = f[2]
                becomeCaller()
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
            }
            return
        }

        if (mode == 2) {
            if (!inSession) {
                // standby: wait for a call request from the parent: P|parentId|label|v/a
                val p = try { CallCrypto.open(code, "hello", null, t, 90 * 1000L) } catch (_: Exception) {
                    // not a call request: maybe a text message from the parent
                    val tm = try { CallCrypto.open(code, "msg", null, t, 90 * 1000L) } catch (_: Exception) { return }
                    val mf = tm.split("|", limit = 2)
                    if (mf.size == 2 && mf[0] == "M" && seen.add(t)) showParentMessage(mf[1])
                    return
                }
                val f = p.split("|")
                if (f.size < 4 || f[0] != "P") return
                if (!seen.add(t)) return
                inSession = true
                waitingOffer = true
                parentId = f[1]
                callType = f[3]
                wantVideo = callType == "v" || callType == "q"
                startCallService(true)   // notification appears only now, for the real call
                sendHello("C|$myId|$curName|$parentId")
                ui.postDelayed(pairTimeout, 45000)
                status("الأهل يتصلون...")
                return
            }
            if (waitingOffer) {
                if (t == lastHandled) return
                // the parent repeats its request when our answer was lost: answer again instead of ignoring it
                val hp = try { CallCrypto.open(code, "hello", null, t, 90 * 1000L) } catch (_: Exception) { null }
                if (hp != null) {
                    val hf = hp.split("|")
                    if (hf.size >= 4 && hf[0] == "P" && hf[1] == parentId && seen.add(t)) sendHello("C|$myId|$curName|$parentId")
                    return
                }
                val sdp = try { CallCrypto.open(code, "offer", null, t, 3 * 60 * 1000L) } catch (_: Exception) { return }
                waitingOffer = false
                answerOffer(t, sdp)
            }
        }
    }

    // ---------- call screen / end ----------
    private fun showCall() {
        ui.removeCallbacksAndMessages(null)
        if (mode == 1) { rv?.stop(); rv = null }
        show(callLayout)
        dim(mode == 2 && callType == "q")   // quiet look: the child's screen stays dark
        loudCount = 0
        msgView.visibility = View.GONE
        if (mode == 1) {
            bar.visibility = View.VISIBLE
            baseTitle = when (callType) { "l" -> "👂 استماع لهاتف $peerName"; "q" -> "🌙 نظرة هادئة على $peerName"; else -> "📱 هاتف $peerName" }
            titleView.text = baseTitle
            camBanner.visibility = View.GONE
            localView.visibility = View.GONE
            diagView.visibility = View.VISIBLE
            levelView.visibility = View.VISIBLE
            levelView.text = "🔊 ▯▯▯▯▯▯▯▯"
            // call mode: parent's microphone open; listen/watch modes: muted until the mic button is pressed
            muted = callType != "a"
            engine?.setMuted(muted)
            micBtn.text = if (muted) "🔇" else "🎤"
            remoteVideoOn = wantVideo
            remoteBack = false
            vidBtn.text = if (wantVideo) "🎥" else "🚫"
            if (callType == "q") ui.postDelayed({ if (callType == "q" && callLayout.visibility == View.VISIBLE) hangup() }, 30000)
        } else {
            bar.visibility = View.GONE
            baseTitle = when (callType) { "l" -> "👂 الأهل يستمعون إليك"; "q" -> "🌙 الأهل يلقون نظرة عليك"; else -> "👨‍👩‍👧 الأهل على الخط" }
            titleView.text = baseTitle
            camBanner.visibility = if (wantVideo) View.VISIBLE else View.GONE
            localView.visibility = if (wantVideo) View.VISIBLE else View.GONE
            diagView.visibility = View.GONE
            levelView.visibility = View.GONE
            if (callType != "q") {   // quiet look: no sound/vibration; the banner + notification stay visible
                buzz(400)
                notifyChild(baseTitle, if (wantVideo) "الكاميرا تعمل الآن" else "الميكروفون يعمل")
            }
        }
    }

    // ---------- glance (quick look, auto-off), level alert, messages ----------
    private val glanceOff = Runnable {
        if (remoteVideoOn) {
            remoteVideoOn = false
            engine?.sendCmd("video:off")
            vidBtn.text = "🚫"
            toast("انتهت النظرة (توفيراً للبطارية)")
        }
    }

    /** In listen mode the camera is only a quick glance: it switches itself off after 15 s. */
    private fun scheduleGlance() {
        ui.removeCallbacks(glanceOff)
        if (remoteVideoOn && callType == "l") ui.postDelayed(glanceOff, 15000)
    }

    /** Parent: loudness of the child's microphone (bars) + vibration when crying/noise lasts in listen mode. */
    private fun onLevel(l: Double) {
        if (mode != 1) return
        val bars = (Math.sqrt(l.coerceIn(0.0, 1.0)) * 8).toInt().coerceIn(0, 8)
        levelView.text = "🔊 " + "▮".repeat(bars) + "▯".repeat(8 - bars)
        if (callType != "l") return
        if (l > 0.04) loudCount++ else {
            if (loudCount != 0) titleView.text = baseTitle
            loudCount = 0
        }
        if (loudCount >= 2 && System.currentTimeMillis() - lastAlert > 30000) {
            lastAlert = System.currentTimeMillis()
            buzz(800)
            titleView.text = "🔔 صوت مرتفع عند $peerName"
        }
    }


    // ---------- location: only on request; the child phone always shows a notification ----------
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    private fun mailboxPublish(kind: String, payload: String, done: (Boolean) -> Unit = {}) {
        Rendezvous(CallCrypto.roomId(curCode), { }, { }).publish(CallCrypto.seal(curCode, kind, null, payload), done)
    }

    // parent side
    private fun requestLocation(k: Kid, live: Boolean) {
        stopLocWatch(false)
        locKid = k
        locReqId = newId()
        val r = Rendezvous(CallCrypto.roomId(k.code), { msg -> runOnUiThread { onLocMsg(msg) } }, { })
        locRv = r
        r.subscribe(System.currentTimeMillis() / 1000 - 5)
        val ok = { _: Boolean -> }
        r.publish(CallCrypto.seal(k.code, "lreq", null, "Q|$locReqId|" + (if (live) "live" else "once")), ok)
        locView.text = "📍 جارٍ طلب موقع ${k.name}... (يصل إن كان هاتفه متصلاً بالإنترنت)"
        locCard.visibility = View.VISIBLE
        bg.postDelayed({ stopLocWatch(false); if (lastLat == 0.0 && lastLon == 0.0) locView.text = "لم يصل موقع ${k.name}. " + (if (Relay.limited()) "وصلت حد رسائل ntfy المجاني (٢٥٠/يوم)." else "تأكد أن هاتفه متصل بالإنترنت وأن وضع الطفل مفعّل (واضغط «فحص الجاهزية» عنده).") },
            if (live) 11 * 60 * 1000L else 60 * 1000L)
    }

    private fun stopLocWatch(sendStop: Boolean) {
        val k = locKid
        if (sendStop && k != null) {
            Rendezvous(CallCrypto.roomId(k.code), { }, { })
                .publish(CallCrypto.seal(k.code, "lreq", null, "Q|$locReqId|stop")) { }
            locView.text = locView.text.toString() + "\n⏹ تم إيقاف التتبع"
        }
        locRv?.stop(); locRv = null
        bg.removeCallbacksAndMessages(null)
    }

    private fun onLocMsg(msg: String) {
        val t = Regex("SKC[12]:[A-Za-z0-9_-]+").find(CallCrypto.clean(msg))?.value ?: return
        val k = locKid ?: return
        val p = try { CallCrypto.open(k.code, "lres", null, t, 5 * 60 * 1000L) } catch (_: Exception) { return }
        if (!seen.add(t)) return
        val f = p.split("|")
        if (f.size < 3 || f[1] != locReqId) return
        if (f[0] == "X") {
            locView.text = when (f[2]) {
                "off" -> "خدمة الموقع (GPS) مغلقة على هاتف ${k.name}."
                else -> "هاتف ${k.name} لم يمنح إذن الموقع. من وضع الطفل: أوقفه بالـ PIN ثم فعّله من جديد واسمح بالموقع."
            }
            return
        }
        if (f[0] != "L" || f.size < 7) return
        lastLat = f[2].toDoubleOrNull() ?: return
        lastLon = f[3].toDoubleOrNull() ?: return
        val acc = f[4]
        val time = timeFmt.format(java.util.Date(f[5].toLongOrNull() ?: System.currentTimeMillis()))
        locView.text = "📍 ${k.name}\nالدقة: ±$acc م\nالوقت: $time\nبطارية هاتفه: ${f[6]}%"
        locCard.visibility = View.VISIBLE
        if (locRv != null && f.size >= 8 && f[7] == "once") stopLocWatch(false)
    }

    private fun openMap() {
        if (lastLat == 0.0 && lastLon == 0.0) { toast("لا يوجد موقع بعد"); return }
        val name = Uri.encode(locKid?.name ?: "الطفل")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lastLat,$lastLon?q=$lastLat,$lastLon($name)")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lastLat,$lastLon")))
            } catch (_: Exception) { toast("لا يوجد تطبيق خرائط. انسخ الإحداثيات.") }
        }
    }

    // child side
    private fun handleLocReq(t: String): Boolean {
        val p = try { CallCrypto.open(curCode, "lreq", null, t, 90 * 1000L) } catch (_: Exception) { return false }
        if (!seen.add(t)) return true
        val f = p.split("|")
        if (f.size < 3 || f[0] != "Q") return true
        when (f[2]) {
            "stop" -> stopLive()
            "live" -> {
                liveReq = f[1]
                liveUntil = System.currentTimeMillis() + 10 * 60 * 1000L
                buzz(500)
                notifyChild("📍 تتبع الموقع", "الأهل يتتبعون موقعك لمدة 10 دقائق")
                bg.removeCallbacks(liveTick)
                liveTick.run()
            }
            else -> {
                buzz(400)
                notifyChild("📍 موقعك", "تم إرسال موقعك إلى الأهل")
                locateAndSend(f[1], "once")
            }
        }
        return true
    }

    private val liveTick = object : Runnable {
        override fun run() {
            if (liveReq.isEmpty() || System.currentTimeMillis() > liveUntil) { stopLive(); return }
            locateAndSend(liveReq, "live")
            bg.postDelayed(this, 20000)
        }
    }

    private fun stopLive() {
        if (liveReq.isNotEmpty()) notifyChild("📍 تتبع الموقع", "توقف تتبع الموقع")
        liveReq = ""
        bg.removeCallbacks(liveTick)
    }

    private fun battery(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun locateAndSend(rid: String, kind: String) {
        if (locating) return
        if (!hasPerms(listOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
            mailboxPublish("lres", "X|$rid|perm"); return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        var best: Location? = null
        for (pr in providers) try {
            val l = lm.getLastKnownLocation(pr)
            if (l != null && (best == null || l.time > best!!.time)) best = l
        } catch (_: Exception) {}
        if (providers.isEmpty()) {
            if (best == null) { mailboxPublish("lres", "X|$rid|off"); return }
            send(rid, best!!, kind); return
        }
        locating = true
        var finished = false
        lateinit var listener: LocationListener
        fun finish() {
            if (finished) return
            finished = true
            locating = false
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            val b = best
            if (b != null) send(rid, b, kind) else mailboxPublish("lres", "X|$rid|off")
        }
        listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val cur = best
                if (cur == null || l.accuracy < cur.accuracy) best = l
                if (l.accuracy <= 40f) finish()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            for (pr in providers) lm.requestLocationUpdates(pr, 0L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) { finish(); return }
        bg.postDelayed({ finish() }, 25000)
    }

    private fun send(rid: String, l: Location, kind: String) {
        val acc = Math.round(l.accuracy)
        mailboxPublish("lres", "L|$rid|${l.latitude}|${l.longitude}|$acc|${l.time}|${battery()}|$kind")
        runOnUiThread { status("📍 آخر مشاركة للموقع: " + timeFmt.format(java.util.Date())) }
    }

    private val presets = listOf(
        "انتبه لأخيك الصغير 👶", "تعال إليّ من فضلك", "حان وقت النوم 🌙",
        "حان وقت الطعام 🍽", "اتصل بي عندما تقرأ هذه الرسالة"
    )

    private fun askMessage(onSend: (String) -> Unit) {
        val items = (presets + "✏️ رسالة مخصصة…").toTypedArray()
        AlertDialog.Builder(this).setTitle("إرسال رسالة").setItems(items) { _, i ->
            if (i < presets.size) onSend(presets[i]) else {
                val et = EditText(this)
                et.hint = "اكتب الرسالة"
                AlertDialog.Builder(this).setTitle("رسالة مخصصة").setView(et)
                    .setPositiveButton("إرسال") { _, _ ->
                        val t = et.text.toString().trim().replace("|", " ").take(200)
                        if (t.isNotEmpty()) onSend(t)
                    }
                    .setNegativeButton("إلغاء", null).showStyled()
            }
        }.showStyled()
    }

    /** Parent, no call running: the message goes through the encrypted mailbox to the waiting child phone. */
    private fun sendMailboxMsg(k: Kid, text: String) {
        val r = Rendezvous(CallCrypto.roomId(k.code), { }, { })
        r.publish(CallCrypto.seal(k.code, "msg", null, "M|$text")) { ok ->
            runOnUiThread {
                toast(if (ok) "أُرسلت الرسالة (تصل إن كان هاتف ${k.name} متصلاً بالإنترنت)" else "تعذّر الإرسال، تحقق من الإنترنت")
            }
        }
    }

    private fun showParentMessage(text: String) {
        standbyMsg.text = "💬 رسالة من الأهل:\n$text"
        standbyMsg.visibility = View.VISIBLE
        buzz(700)
        notifyChild("رسالة من الأهل", text)
    }

    @Suppress("DEPRECATION")
    private fun buzz(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Exception) {}
    }

    /** Heads-up notification on the child phone (wakes the screen on most phones, rings/vibrates). */
    @Suppress("DEPRECATION")
    private fun notifyChild(title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("securecall_msg", "رسائل الأهل", android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(
                this, 3,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "securecall_msg")
            else android.app.Notification.Builder(this).setPriority(android.app.Notification.PRIORITY_HIGH)
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
            nm.notify(100 + (System.currentTimeMillis() % 1000).toInt(), b.build())
        } catch (_: Exception) {}
    }

    private fun hangup() {
        if (mode == 2) return           // the child phone cannot end the call
        val e = engine
        if (e == null) { endCallUi(); return }
        e.sendCmd("bye")
        ui.postDelayed({ endCallUi() }, 300)
    }

    private fun endCallUi(msg: String = "انتهت المكالمة.") {
        resetCall()
        stopCallService()
        refreshParent()
        show(parentScreen)
        status(msg)
    }

    private fun resetCall() {
        engine?.close(); engine = null
        ui.removeCallbacksAndMessages(null)
        rv?.stop(); rv = null; auto = false; waitingOffer = false; waitingPeer = false
        remoteView.clearImage(); localView.clearImage()
        role = 0; offerBlob = ""
        muted = false
        diagView.text = ""
        lastHandled = ""
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            am.mode = savedAudioMode; am.isSpeakerphoneOn = savedSpeaker
            if (mode == 2 && savedVol >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVol, 0)
        }
    }

    private fun status(s: String) {
        if (mode == 2) {
            standbyStatus.text = if (s.isEmpty()) "✅ جاهز لاستقبال نداء الأهل" else s
            return
        }
        statusP.text = s
        statusP.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun copy(t: String) {
        if (t.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("code", t))
        toast("تم النسخ")
    }

    override fun onPause() {
        if (!svcOn) engine?.pauseCamera()
        if (this::wakeUrl.isInitialized) prefs.edit().putString("wake_url", wakeUrl.text.toString().trim()).apply()
        if (this::relayP.isInitialized) saveRelay(relayP)
        childVisible = false
        stopParentWatchers()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        engine?.resumeCamera()
        if (mode == 1) startParentWatchers()
        if (mode == 2) { childVisible = true; updateReadyLabel() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            mode == 2 && (childStandby.visibility == View.VISIBLE || callLayout.visibility == View.VISIBLE) ->
                askPin { leaveChild() }
            callLayout.visibility == View.VISIBLE -> hangup()
            parentScreen.visibility == View.VISIBLE || childSetup.visibility == View.VISIBLE -> show(modeScreen)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopCallService()
        ui.removeCallbacksAndMessages(null)
        tickH.removeCallbacksAndMessages(null)
        stopParentWatchers()
        rv?.stop(); rv = null
        engine?.close(); engine = null
        try { remoteView.release(); localView.release() } catch (_: Exception) {}
        try { egl.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

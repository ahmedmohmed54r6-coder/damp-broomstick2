package com.findphone.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MatR

class MainActivity : Activity() {
    private lateinit var s: Store
    private var statusBox: LinearLayout? = null
    private var sendStatusTv: TextView? = null
    private var sendJob: SmsJob? = null

    private var unlocked = false
    private var current = ""
    private var stoppedAt = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // يمنع لقطات الشاشة وإظهار المحتوى في قائمة التطبيقات الأخيرة
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        s = Store(this)
        s.cleanLegacy()
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    override fun onResume() {
        super.onResume()
        // يُقفل تلقائيًا إذا غادرت التطبيق أكثر من 30 ثانية
        if (unlocked && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 30_000) unlocked = false
        val want = when {
            !s.hasPin -> "setup"
            !unlocked -> "lock"
            else -> "settings"
        }
        if (want != current) {
            current = want
            when (want) { "setup" -> renderSetup(); "lock" -> renderLock(); else -> renderSettings() }
        } else if (want == "setup" || want == "settings") refresh() else showStatus()
    }

    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }

    override fun onDestroy() { sendJob?.cancel(); super.onDestroy() }

    // ---------- أدوات مساعدة ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(col(R.color.text_sub))
        setPadding(dp(4), 0, dp(4), dp(10))
        letterSpacing = 0.02f
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(col(R.color.text_sub))
        setPadding(dp(2), dp(4), dp(2), dp(6))
    }

    private fun card(vararg children: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(16) }
        }
        children.forEach { c.addView(it) }
        return c
    }

    private fun et(h: String, v: String = "", phone: Boolean = false, pin: Boolean = false) = EditText(this).apply {
        hint = h; setText(v); textSize = 15f
        setTextColor(col(R.color.text_main)); setHintTextColor(col(R.color.text_sub))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
        if (phone) inputType = InputType.TYPE_CLASS_PHONE
        if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun mbtn(t: String, filled: Boolean = true, f: () -> Unit): MaterialButton {
        val style = if (filled) MatR.attr.materialButtonStyle else MatR.attr.materialButtonOutlinedStyle
        return MaterialButton(this, null, style).apply {
            text = t; textSize = 14f; isAllCaps = false
            cornerRadius = dp(14)
            insetTop = 0; insetBottom = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
            if (filled) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(col(R.color.primary))
                setTextColor(col(R.color.on_primary))
            } else {
                strokeColor = android.content.res.ColorStateList.valueOf(col(R.color.stroke))
                strokeWidth = dp(1)
                setTextColor(col(R.color.primary))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            setOnClickListener { f() }
        }
    }

    private fun iconBtn(t: String, bg: Int, f: () -> Unit) = MaterialButton(this, null, MatR.attr.materialButtonStyle).apply {
        text = t; textSize = 13f; isAllCaps = false; cornerRadius = dp(12)
        insetTop = 0; insetBottom = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
        backgroundTintList = android.content.res.ColorStateList.valueOf(col(bg))
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        setOnClickListener { f() }
    }

    private fun header(sub: String): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(col(R.color.accent), col(R.color.primary_dark))).apply { cornerRadius = dp(22).toFloat() }
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(18) }
        }
        wrap.addView(TextView(this).apply {
            text = "Find Phone"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        wrap.addView(TextView(this).apply {
            text = sub; textSize = 13.5f; setTextColor(Color.parseColor("#D8E6FF")); setPadding(0, dp(4), 0, 0)
        })
        return wrap
    }

    private fun newRoot(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(col(R.color.bg))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(col(R.color.bg))
            isFillViewport = true
            addView(root)
        })
        statusBox = null
        sendStatusTv = null
        return root
    }

    // ---------- شاشة الإعداد الأولي ----------
    private fun renderSetup() {
        val root = newRoot()
        root.addView(header("إعداد لمرة واحدة، ثم يُقفل التطبيق برمز"))

        val kw = et("مثال: 123456789", s.keyword)
        root.addView(card(
            sectionTitle("١) الكلمة السرية"),
            label("أي رسالة نصية تحمل هذه الكلمة فقط، من أي هاتف، يردّ عليها هاتفك تلقائيًا بموقعه (6 أحرف فأكثر). أضف « ring » بعدها لتشغيل الرنين."),
            kw
        ))

        val pin1 = et("رمز القفل (4 أرقام فأكثر)", pin = true)
        val pin2 = et("أعد كتابة الرمز", pin = true)
        root.addView(card(
            sectionTitle("٢) رمز قفل التطبيق"),
            label("بعد الحفظ لا يُفتح التطبيق ولا تتغير الإعدادات إلا بهذا الرمز."),
            pin1, pin2
        ))

        root.addView(permsCard(title = "٣) الصلاحيات"))
        root.addView(adminCard())

        root.addView(mbtn("حفظ وقفل التطبيق") {
            val k = kw.text.toString().trim()
            val a = pin1.text.toString(); val b = pin2.text.toString()
            when {
                k.length < 6 -> toast("الكلمة السرية 6 أحرف فأكثر")
                a.length < 4 -> toast("رمز القفل 4 أرقام على الأقل")
                a != b -> toast("الرمزان غير متطابقين")
                else -> {
                    s.keyword = k; s.setPin(a); s.failCount = 0; s.lockUntil = 0
                    unlocked = false; current = "lock"
                    toast("تم الحفظ، التطبيق مقفل الآن")
                    renderLock()
                }
            }
        })
        showStatus()
    }

    // ---------- شاشة القفل ----------
    private fun renderLock() {
        val root = newRoot()
        root.addView(header("التطبيق مقفل"))
        val pin = et("رمز القفل", pin = true)
        val msg = TextView(this).apply { textSize = 13.5f; setTextColor(col(R.color.danger)); setPadding(dp(2), dp(4), dp(2), dp(4)) }
        root.addView(card(
            sectionTitle("أدخل الرمز لفتح الإعدادات"),
            pin, msg,
            mbtn("فتح") { tryUnlock(pin, msg) }
        ))
        val hint = label("الرد التلقائي على الرسائل يعمل في الخلفية حتى والتطبيق مقفل.")
        root.addView(hint)
    }

    private fun tryUnlock(pin: EditText, msg: TextView) {
        val now = System.currentTimeMillis()
        val wait = s.lockUntil - now
        if (wait > 0) { msg.text = "محاولات كثيرة، انتظر ${wait / 1000 + 1} ثانية"; return }
        if (s.checkPin(pin.text.toString())) {
            s.failCount = 0
            unlocked = true; current = "settings"
            renderSettings()
        } else {
            val f = s.failCount + 1
            s.failCount = f
            pin.setText("")
            if (f >= 5) {
                s.lockUntil = now + minOf(60_000L * (f - 4), 600_000L)
                msg.text = "رمز خاطئ. تم إيقاف المحاولات مؤقتًا"
            } else msg.text = "الرمز غير صحيح (${f} من 5)"
        }
    }

    // ---------- شاشة الإعدادات (بعد فتح القفل) ----------
    private fun renderSettings() {
        val root = newRoot()
        root.addView(header("الإعدادات"))

        val kw = et("مثال: 123456789", s.keyword)
        root.addView(card(
            sectionTitle("الكلمة السرية"),
            label("يردّ الهاتف على أي رسالة تحمل هذه الكلمة. أضف « ring » للرنين."),
            kw,
            mbtn("حفظ الكلمة") {
                val k = kw.text.toString().trim()
                if (k.length < 6) toast("الكلمة 6 أحرف فأكثر") else { s.keyword = k; toast("تم الحفظ") }
            }
        ))

        root.addView(permsCard(title = "الصلاحيات والاستقرار"))
        root.addView(adminCard())

        // إرسال طلب لهاتف آخر عليه التطبيق (اختياري)
        val num = et("رقم الهاتف المراد تحديده", phone = true)
        val st = TextView(this).apply { textSize = 13f; setTextColor(col(R.color.text_sub)); setPadding(dp(2), dp(8), dp(2), 0) }
        sendStatusTv = st
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        row.addView(iconBtn("طلب الموقع", R.color.primary) { sendRequest(num.text.toString(), false) })
        row.addView(iconBtn("رنين", R.color.accent) { sendRequest(num.text.toString(), true) })
        root.addView(card(
            sectionTitle("طلب موقع هاتف آخر (اختياري)"),
            label("يرسل الكلمة السرية إلى هاتف عليه التطبيق بنفس الكلمة، ويصلك الرد برسالة نصية فيها رابط الخريطة."),
            num, row, st
        ))

        val n1 = et("رمز جديد (4 أرقام فأكثر)", pin = true)
        val n2 = et("أعد كتابة الرمز الجديد", pin = true)
        root.addView(card(
            sectionTitle("تغيير رمز القفل"),
            n1, n2,
            mbtn("تغيير الرمز", filled = false) {
                val a = n1.text.toString()
                when {
                    a.length < 4 -> toast("رمز القفل 4 أرقام على الأقل")
                    a != n2.text.toString() -> toast("الرمزان غير متطابقين")
                    else -> { s.setPin(a); n1.setText(""); n2.setText(""); toast("تم تغيير الرمز") }
                }
            }
        ))

        root.addView(mbtn("قفل التطبيق الآن") {
            unlocked = false; current = "lock"; renderLock()
        })
        showStatus()
    }

    private fun sendRequest(num: String, ring: Boolean) {
        val k = s.keyword
        if (k.length < 6) { toast("احفظ الكلمة السرية أولاً"); return }
        if (Store.key(num).length < 7) { toast("أدخل رقمًا صحيحًا"); return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { askPerms(); return }
        sendJob?.cancel()
        sendJob = SmsJob(this, num.trim(), if (ring) "$k ring" else k,
            backoffsMs = longArrayOf(5_000, 15_000),
            onStatus = { sendStatusTv?.text = it },
            onFinished = { ok ->
                sendStatusTv?.text = if (ok) "تم إرسال الطلب. انتظر الرد برسالة نصية" else "فشل الإرسال: لا توجد تغطية على أي شريحة"
            }).also { it.start() }
    }

    // ---------- بطاقة الصلاحيات ----------
    private fun permsCard(title: String): LinearLayout {
        val ch = mutableListOf<View>(
            sectionTitle(title),
            mbtn("إعطاء الصلاحيات", filled = false) { askPerms() },
            mbtn("إيقاف تحسين البطارية", filled = false) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { toast("افتح إعدادات البطارية يدويًا") }
            }
        )
        val oem = oemAutostartIntent()
        if (oem != null) ch += mbtn("إعدادات «${Build.MANUFACTURER}» (تشغيل تلقائي)", filled = false) {
            try { startActivity(oem) } catch (e: Exception) {
                toast("تعذّر فتح الصفحة تلقائيًا، افتحها يدويًا من إعدادات التطبيقات")
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) {}
            }
        }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        ch += statusBox!!
        ch += mbtn("تحقّق من الإعدادات", filled = false) { showStatus() }
        return card(*ch.toTypedArray())
    }

    private fun devicePolicyManager() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun adminCard(): LinearLayout {
        val dpm = devicePolicyManager()
        val active = dpm.isAdminActive(adminComponent())
        val isOwner = try { dpm.isDeviceOwnerApp(packageName) } catch (e: Exception) { false }
        return if (active) {
            val rows = mutableListOf<View>(
                sectionTitle("حماية الحذف"),
                TextView(this).apply { text = "✓ مفعّلة — لا يمكن حذف التطبيق قبل إلغاء هذه الحماية"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) },
                mbtn("إلغاء حماية الحذف", filled = false) {
                    devicePolicyManager().removeActiveAdmin(adminComponent())
                    toast("أُلغيت الحماية"); refresh()
                }
            )
            rows += View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)) }
            rows += sectionTitle("منع إيقاف خدمة الموقع")
            if (isOwner) {
                val restricted = try { dpm.getUserRestrictions(adminComponent()).getBoolean(android.os.UserManager.DISALLOW_CONFIG_LOCATION) } catch (e: Exception) { false }
                if (restricted) {
                    rows += TextView(this).apply { text = "✓ مفعّل — لا يمكن إيقاف الموقع من الإعدادات"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) }
                    rows += mbtn("إلغاء المنع", filled = false) {
                        try { dpm.clearUserRestriction(adminComponent(), android.os.UserManager.DISALLOW_CONFIG_LOCATION); toast("أُلغي المنع") }
                        catch (e: Exception) { toast("تعذّر: ${e.message}") }
                        refresh()
                    }
                } else {
                    rows += label("يمنع المستخدم من فتح إعدادات الموقع أو إيقافه نهائيًا.")
                    rows += mbtn("تفعيل المنع", filled = false) {
                        try { dpm.addUserRestriction(adminComponent(), android.os.UserManager.DISALLOW_CONFIG_LOCATION); toast("تم المنع") }
                        catch (e: Exception) { toast("تعذّر: ${e.message}") }
                        refresh()
                    }
                }
            } else {
                rows += label("هذا غير متاح بالصلاحية الحالية. يتطلب أن يكون التطبيق «مالك الجهاز» (Device Owner)، وهذا أقوى من «مسؤول الجهاز» العادي ولا يمكن منحه إلا عبر أمر من كمبيوتر، وعادة قبل إضافة أي حساب جوجل على الهاتف (أو بعد ضبط المصنع).")
                rows += mbtn("عرض خطوات التفعيل (ADB)", filled = false) { showOwnerInstructions() }
            }
            card(*rows.toTypedArray())
        } else {
            card(
                sectionTitle("حماية الحذف"),
                label("تمنع حذف التطبيق مباشرة من الإعدادات؛ يلزم إلغاء هذه الحماية أولاً من هنا."),
                mbtn("تفعيل حماية الحذف", filled = false) {
                    val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "لمنع حذف التطبيق إذا سُرق الهاتف")
                    try { startActivity(i) } catch (e: Exception) { toast("غير مدعوم على هذا الجهاز") }
                }
            )
        }
    }

    private fun refresh() { when (current) { "setup" -> renderSetup(); "settings" -> renderSettings(); else -> {} } }

    private fun showOwnerInstructions() {
        val cmd = "adb shell dpm set-device-owner com.findphone.app/.AdminReceiver"
        AlertDialog.Builder(this)
            .setTitle("تفعيل «مالك الجهاز»")
            .setMessage(
                "الشروط: لا يوجد أي حساب جوجل أو حساب آخر مُضاف على الهاتف (أو اعمل ضبط مصنع أولًا)، " +
                "وتفعيل «تصحيح أخطاء USB» من إعدادات المطوّر، وتثبيت أدوات Android (adb) على كمبيوتر.\n\n" +
                "الخطوات:\n" +
                "1) ثبّت التطبيق على الهاتف (هذا الملف نفسه).\n" +
                "2) لا تفتحه ولا تضف أي حساب على الهاتف بعد.\n" +
                "3) وصّل الهاتف بالكمبيوتر وفعّل تصحيح USB.\n" +
                "4) نفّذ هذا الأمر من الكمبيوتر:\n\n$cmd\n\n" +
                "5) افتح التطبيق بعدها، ستجد زر «تفعيل المنع» متاحًا.\n\n" +
                "إن كان على الهاتف حساب بالفعل، الطريقة الوحيدة هي ضبط المصنع أولًا ثم تكرار الخطوات قبل إعادة إضافة أي حساب."
            )
            .setPositiveButton("نسخ الأمر") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmd))
                toast("نُسخ الأمر")
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun oemAutostartIntent(): Intent? {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity")
            "oppo", "realme" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity")
            "vivo" -> listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            else -> emptyList()
        }
        for ((pkg, cls) in candidates) {
            val i = Intent().apply { component = ComponentName(pkg, cls); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (packageManager.queryIntentActivities(i, 0).isNotEmpty()) return i
        }
        return null
    }

    private fun showStatus() {
        val box = statusBox ?: return
        val problems = mutableListOf<String>()
        val granted = PackageManager.PERMISSION_GRANTED
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != granted)
            problems += "صلاحية الموقع غير ممنوحة (اختر «دقيق»)"
        else if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != granted)
            problems += "صلاحية الموقع ليست «مسموح طوال الوقت»"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != granted || checkSelfPermission(Manifest.permission.RECEIVE_SMS) != granted)
            problems += "صلاحية الرسائل غير ممنوحة"
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != granted)
            problems += "صلاحية الهاتف غير ممنوحة (تلزم للتبديل التلقائي بين الشريحتين)"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) problems += "توفير البطارية لم يُستثنَ للتطبيق"
        try {
            val lmgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lmgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) problems += "الـGPS مغلق: فعّل «الموقع» من إعدادات الهاتف (ضروري بدون إنترنت)"
        } catch (e: Exception) { }

        box.removeAllViews()
        box.background = ContextCompat.getDrawable(this, if (problems.isEmpty()) R.drawable.bg_status_ok else R.drawable.bg_status_warn)
        if (problems.isEmpty()) {
            box.addView(TextView(this).apply { text = "✓ كل الإعدادات سليمة"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) })
        } else {
            box.addView(TextView(this).apply {
                text = "أكمل هذه النقاط حتى يعمل التطبيق بثبات:"
                setTextColor(col(R.color.warn)); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(4))
            })
            problems.forEach { p ->
                box.addView(TextView(this).apply { text = "•  $p"; setTextColor(col(R.color.warn)); textSize = 13f })
            }
        }
    }

    private fun askPerms() {
        val l = mutableListOf(
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(l.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        if (rc == 1 && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("الآن اختر «السماح طوال الوقت» للموقع")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
        showStatus()
    }
}

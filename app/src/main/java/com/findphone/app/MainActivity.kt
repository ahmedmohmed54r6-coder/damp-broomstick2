package com.findphone.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MatR
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var s: Store
    private var resultTv: TextView? = null
    private var statusBox: LinearLayout? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Store(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        build()
    }

    override fun onResume() { super.onResume(); showResult(); showStatus() }

    // ---------- أدوات مساعدة للقياسات والألوان ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

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

    private fun et(h: String, v: String = "", phone: Boolean = false) = EditText(this).apply {
        hint = h; setText(v); textSize = 15f
        setTextColor(col(R.color.text_main)); setHintTextColor(col(R.color.text_sub))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
        if (phone) inputType = android.text.InputType.TYPE_CLASS_PHONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    // زر معبّأ (إجراء رئيسي) أو محدَّد الإطار (إجراء ثانوي)
    private fun mbtn(t: String, filled: Boolean = true, full: Boolean = true, f: () -> Unit): MaterialButton {
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
            if (full) layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- شريط الترويسة ----------
    private fun header(): View {
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
            text = "تحديد موقع هاتفك عبر رسالة نصية عند الضياع"
            textSize = 13.5f; setTextColor(Color.parseColor("#D8E6FF")); setPadding(0, dp(4), 0, 0)
        })
        return wrap
    }

    private fun build() {
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

        root.addView(header())

        // --- الإعداد الأساسي ---
        val name = et("اسمك", s.myName); val group = et("كود المجموعة (سري)", s.group)
        root.addView(card(
            sectionTitle("الإعداد الأساسي"),
            label("اسمك"), name,
            label("كود المجموعة (سري، 6 أحرف فأكثر)"), group,
            mbtn("حفظ") {
                if (group.text.length < 6) toast("اجعل الكود 6 أحرف على الأقل")
                else { s.myName = name.text.toString().trim(); s.group = group.text.toString(); toast("تم الحفظ") }
            }
        ))

        // --- الرد التلقائي بالرمز ---
        val kw = et("مثال: 123456789", s.keyword)
        root.addView(card(
            sectionTitle("الرد التلقائي بكلمة السر"),
            label("يرد تلقائيًا على أي رسالة تحمل هذه الكلمة، من أي هاتف"),
            kw,
            mbtn("حفظ الكلمة") {
                val k = kw.text.toString().trim()
                if (k.isNotEmpty() && k.length < 6) toast("الكلمة 6 أحرف فأكثر")
                else { s.keyword = k; toast(if (k.isEmpty()) "أُلغي الرد التلقائي" else "تم الحفظ") }
            }
        ))

        // --- الصلاحيات والاستقرار ---
        val permsChildren = mutableListOf<View>(
            sectionTitle("الصلاحيات والاستقرار"),
            mbtn("إعطاء الصلاحيات", filled = false) { askPerms() },
            mbtn("إيقاف تحسين البطارية", filled = false) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { toast("افتح إعدادات البطارية يدويًا") }
            }
        )
        val oem = oemAutostartIntent()
        if (oem != null) permsChildren += mbtn("إعدادات «${Build.MANUFACTURER}» (تشغيل تلقائي)", filled = false) {
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
        permsChildren += statusBox!!
        permsChildren += mbtn("تحقّق من الإعدادات", filled = false) { showStatus() }
        root.addView(card(*permsChildren.toTypedArray()))

        // --- أعضاء المجموعة ---
        val memberChildren = mutableListOf<View>(sectionTitle("أعضاء المجموعة"))
        val members = s.members()
        if (members.isEmpty()) memberChildren += label("لا يوجد أعضاء بعد")
        members.forEach { (n, p) ->
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(col(R.color.bg)); cornerRadius = dp(14).toFloat() }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            }
            rowCard.addView(TextView(this).apply {
                text = n; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(col(R.color.text_main))
            })
            rowCard.addView(TextView(this).apply {
                text = p; textSize = 13f; setTextColor(col(R.color.text_sub)); setPadding(0, 0, 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(iconBtn("الموقع", R.color.primary) { send(p, "L") })
            row.addView(iconBtn("رنين", R.color.accent) { send(p, "B") })
            row.addView(iconBtn("حذف", R.color.danger) { s.saveMembers(s.members().filter { it.second != p }); build() })
            rowCard.addView(row)
            memberChildren += rowCard
        }
        val mn = et("اسم العضو"); val mp = et("رقم هاتفه", phone = true)
        memberChildren += label("إضافة عضو جديد")
        memberChildren += mn; memberChildren += mp
        memberChildren += mbtn("إضافة عضو") {
            val n = mn.text.toString().trim(); val p = mp.text.toString().trim()
            if (n.isEmpty() || Store.key(p).length < 7) toast("أدخل اسماً ورقماً صحيحاً")
            else { s.saveMembers(s.members() + (n to p)); build() }
        }
        root.addView(card(*memberChildren.toTypedArray()))

        // --- آخر نتيجة ---
        resultTv = TextView(this).apply { textSize = 14f; setTextColor(col(R.color.text_main)) }
        root.addView(card(
            sectionTitle("آخر نتيجة"),
            resultTv!!,
            mbtn("فتح الخريطة") {
                s.result()?.let {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${it.getString("lat")},${it.getString("lon")}(${Uri.encode(it.getString("name"))})")))
                }
            }
        ))

        showResult()
        showStatus()
    }

    // أهم روابط إعدادات التشغيل التلقائي/الخلفية المعروفة لكل شركة. قد لا تعمل على كل الإصدارات
    // (الشركات تغيّرها باستمرار)؛ عند الفشل نفتح صفحة تفاصيل التطبيق كبديل.
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
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            problems += "صلاحية الموقع ليست «مسموح طوال الوقت»"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) problems += "توفير البطارية لم يُستثنَ للتطبيق"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            problems += "صلاحية الرسائل غير ممنوحة"
        if (s.keyword.isBlank()) problems += "لم تحفظ كلمة السر بعد"

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

    private fun showResult() {
        val r = s.result()
        resultTv?.text = if (r == null) "لا توجد نتيجة بعد" else {
            val ok = r.getString("acc").toFloatOrNull()?.let { it >= 0 } == true
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(r.getLong("ts") * 1000))
            "${r.getString("name")} — $t\n" + if (ok) "الدقة ${r.getString("acc")} م — البطارية ${r.getString("bat")}%" else "رد الهاتف لكن بلا موقع — البطارية ${r.getString("bat")}%"
        }
    }

    private fun send(number: String, type: String) {
        if (s.group.length < 6) { toast("احفظ كود المجموعة أولاً"); return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { askPerms(); return }
        try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendTextMessage(number, null, Crypto.enc(s.group, "$type|${System.currentTimeMillis() / 1000}"), null, null)
            toast("أُرسل الطلب. انتظر الرد بالإشعار")
        } catch (e: Exception) { toast("فشل الإرسال: ${e.message}") }
    }

    private fun askPerms() {
        val l = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(l.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        if (rc == 1 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("الآن اختر «السماح طوال الوقت» للموقع")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
        showStatus()
    }
}

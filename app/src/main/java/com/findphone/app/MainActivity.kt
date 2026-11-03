package com.findphone.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var s: Store
    private var resultTv: TextView? = null
    private var statusTv: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Store(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        build()
    }

    override fun onResume() { super.onResume(); showResult(); showStatus() }

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
        val problems = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            problems += "صلاحية الموقع ليست «مسموح طوال الوقت»"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) problems += "توفير البطارية لم يُستثنَ للتطبيق"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            problems += "صلاحية الرسائل غير ممنوحة"
        if (s.keyword.isBlank()) problems += "لم تحفظ كلمة السر بعد"
        statusTv?.apply {
            if (problems.isEmpty()) { text = "✓ كل الإعدادات سليمة"; setTextColor(Color.parseColor("#2E7D32")) }
            else { text = "تنبيه، أكمل هذه النقاط حتى يعمل التطبيق بثبات:\n" + problems.joinToString("\n") { "• $it" }
                setTextColor(Color.parseColor("#C62828")) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, size: Float = 15f) = TextView(this).apply { text = t; textSize = size; setPadding(0, dp(8), 0, dp(4)) }
    private fun et(h: String, v: String = "", phone: Boolean = false) = EditText(this).apply {
        hint = h; setText(v); if (phone) inputType = android.text.InputType.TYPE_CLASS_PHONE
    }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(tv("العثور على الهاتف (SMS)", 22f))

        val name = et("اسمك", s.myName); val group = et("كود المجموعة (سري)", s.group)
        root.addView(name); root.addView(group)
        root.addView(btn("حفظ") {
            if (group.text.length < 6) toast("اجعل الكود 6 أحرف على الأقل")
            else { s.myName = name.text.toString().trim(); s.group = group.text.toString(); toast("تم الحفظ") }
        })
        root.addView(tv("كلمة السر للرد التلقائي (يرد على أي رقم يرسلها، حتى من هاتف بلا التطبيق)"))
        val kw = et("مثال: 123456789", s.keyword)
        root.addView(kw)
        root.addView(btn("حفظ الكلمة") {
            val k = kw.text.toString().trim()
            if (k.isNotEmpty() && k.length < 6) toast("الكلمة 6 أحرف فأكثر")
            else { s.keyword = k; toast(if (k.isEmpty()) "أُلغي الرد التلقائي" else "تم الحفظ") }
        })
        root.addView(btn("إعطاء الصلاحيات") { askPerms() })
        root.addView(btn("إيقاف تحسين البطارية") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { toast("افتح إعدادات البطارية يدويًا") }
        })

        val oem = oemAutostartIntent()
        if (oem != null) root.addView(btn("فتح إعدادات «${Build.MANUFACTURER}» (تشغيل تلقائي/خلفية)") {
            try { startActivity(oem) } catch (e: Exception) {
                toast("تعذّر فتح الصفحة تلقائيًا، افتحها يدويًا من إعدادات التطبيقات")
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) {}
            }
        })

        statusTv = tv("")
        root.addView(statusTv)
        root.addView(btn("تحقّق من الإعدادات") { showStatus() })

        root.addView(tv("أعضاء المجموعة", 18f))
        s.members().forEach { (n, p) ->
            root.addView(tv("$n  —  $p"))
            val row = LinearLayout(this)
            row.addView(btn("تحديد الموقع") { send(p, "L") })
            row.addView(btn("رنين") { send(p, "B") })
            row.addView(btn("حذف") { s.saveMembers(s.members().filter { it.second != p }); build() })
            root.addView(row)
        }
        val mn = et("اسم العضو"); val mp = et("رقم هاتفه", phone = true)
        root.addView(mn); root.addView(mp)
        root.addView(btn("إضافة عضو") {
            val n = mn.text.toString().trim(); val p = mp.text.toString().trim()
            if (n.isEmpty() || Store.key(p).length < 7) toast("أدخل اسماً ورقماً صحيحاً")
            else { s.saveMembers(s.members() + (n to p)); build() }
        })

        root.addView(tv("آخر نتيجة", 18f))
        resultTv = tv("")
        root.addView(resultTv)
        root.addView(btn("فتح الخريطة") {
            s.result()?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${it.getString("lat")},${it.getString("lon")}(${Uri.encode(it.getString("name"))})")))
            }
        })
        showResult()
        showStatus()
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
    }
}

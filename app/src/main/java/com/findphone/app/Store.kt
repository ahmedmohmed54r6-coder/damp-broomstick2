package com.findphone.app

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Store(c: Context) {
    private val p = c.getSharedPreferences("fp", Context.MODE_PRIVATE)

    // الكلمة السرية التي يردّ عليها الهاتف تلقائيًا
    var keyword: String
        get() = p.getString("kw", "") ?: ""
        set(v) { p.edit().putString("kw", v).apply() }

    fun lastPlain() = p.getLong("lastplain", 0)
    fun setLastPlain(v: Long) { p.edit().putLong("lastplain", v).apply() }

    // ---------- قفل التطبيق (رمز PIN) ----------
    val hasPin: Boolean get() = p.getString("pin_h", null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit().putString("pin_s", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_h", hash(pin, salt)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val h = p.getString("pin_h", null) ?: return false
        val salt = Base64.decode(p.getString("pin_s", "") ?: "", Base64.NO_WRAP)
        return MessageDigest.isEqual(h.toByteArray(), hash(pin, salt).toByteArray())
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, 50_000, 256)).encoded
        return Base64.encodeToString(k, Base64.NO_WRAP)
    }

    var failCount: Int
        get() = p.getInt("fails", 0)
        set(v) { p.edit().putInt("fails", v).apply() }
    var lockUntil: Long
        get() = p.getLong("lock_until", 0)
        set(v) { p.edit().putLong("lock_until", v).apply() }

    var adminActive: Boolean
        get() = p.getBoolean("admin", false)
        set(v) { p.edit().putBoolean("admin", v).apply() }

    // حذف بيانات النسخة القديمة (المجموعة والأعضاء)
    fun cleanLegacy() {
        p.edit().remove("group").remove("members").remove("result").remove("name").apply()
    }

    companion object {
        fun key(n: String) = n.filter { it.isDigit() }.takeLast(8)
    }
}

package com.securecall.app

import android.content.Context
import android.content.SharedPreferences

/** One shared (encrypted-at-rest) settings file, usable from the Activity and from services. */
object Store {
    @Volatile private var cached: SharedPreferences? = null

    fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        val app = ctx.applicationContext
        val p = try {
            val mk = androidx.security.crypto.MasterKey.Builder(app)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                app, "cfg_enc", mk,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        }
        cached = p
        return p
    }
}

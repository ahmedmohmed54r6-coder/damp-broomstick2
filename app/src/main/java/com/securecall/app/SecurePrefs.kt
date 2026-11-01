package com.securecall.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The one place that opens the encrypted "cfg_enc" store (pairing code, child name, PIN hash —
 *  see MainActivity). Used both by the UI and by ChildFcmService, which can run without any
 *  Activity ever having started in this process. */
object SecurePrefs {
    @Volatile private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = try {
                val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, "cfg_enc", mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            }
            cached = p
            return p
        }
    }
}

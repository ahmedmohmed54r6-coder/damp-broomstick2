package com.securecall.app

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Stores this phone's FCM token under fcm_tokens/{roomId} — roomId is the same code-derived hash
 * already used for the ntfy.sh mailbox, never the child's name or any identifying detail. A
 * parent phone can only look this document up if it independently holds the same pairing code
 * (see firestore.rules in functions/), exactly like the mailbox itself.
 */
object TokenRegistrar {
    fun register(context: Context, token: String? = null) {
        val prefs = SecurePrefs.get(context)
        if (prefs.getInt("mode", 0) != 2 || !prefs.getBoolean("cactive", false)) return
        val code = prefs.getString("ccode", "") ?: return
        if (code.isEmpty()) return
        val room = CallCrypto.roomId(code)
        // Firebase is optional: without google-services.json it is not initialised, so skip silently.
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) return
            fun write(tok: String) {
                try {
                    FirebaseFirestore.getInstance().collection("fcm_tokens").document(room)
                        .set(mapOf("token" to tok, "updatedAt" to System.currentTimeMillis()))
                } catch (_: Throwable) {}
            }
            if (token != null) { write(token); return }
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { write(it) }
        } catch (_: Throwable) {}
    }
}

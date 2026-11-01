package com.securecall.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls your deployed Cloud Function (see functions/) with only roomId — the same code-derived
 * hash the mailbox already uses, never the pairing code itself or the child's name. The function
 * looks up that room's FCM token in Firestore and sends a bare "ring" signal — see
 * ChildFcmService for what the child phone does with it. Best-effort: on any failure (not
 * deployed yet, no internet to Firebase, etc.) the existing mailbox call request still goes
 * through as before — this is purely an extra wake-up path, not a dependency.
 */
object WakePusher {
    /** Fill in after you deploy the Cloud Function (functions/README.md gives you this exact
     *  URL at the end of `firebase deploy --only functions`). Left blank = feature quietly skipped. */
    private const val WAKE_URL = "" // e.g. "https://REGION-PROJECT.cloudfunctions.net/sendWake"

    fun send(code: String) {
        if (WAKE_URL.isBlank()) return
        Thread {
            try {
                val room = CallCrypto.roomId(code)
                val c = URL(WAKE_URL).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("roomId", room).toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}

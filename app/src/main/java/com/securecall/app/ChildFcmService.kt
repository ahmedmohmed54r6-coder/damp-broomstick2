package com.securecall.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Carries no call content and nothing sensitive — only a bare "ring" signal with no payload.
 * Its only job is to make Android launch this process right now, on phones/ROMs where our own
 * long-poll thread alone is not reliably woken from deep sleep. The actual call request (who's
 * calling, the encrypted WebRTC offer) still travels the same way as always: through the
 * code-derived mailbox, sealed with CallCrypto. This service never reads or stores call content.
 */
class ChildFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        TokenRegistrar.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != "ring") return
        // Promote the process right away so the system is far less likely to freeze it again
        // before the real (encrypted) call request — already in flight over the mailbox — is read.
        CallWake.kick(applicationContext)
    }
}

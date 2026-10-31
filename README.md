# SecureCall

Android 1-to-1 video/voice call, end-to-end encrypted (WebRTC DTLS-SRTP, direct between phones), no signaling server.
Setup codes (offer/answer) are encrypted with a shared random code (AES-256-GCM, see CallCrypto.kt).

Build: push to GitHub, run the "Build APK" workflow, download artifact SecureCall-apk (app-debug.apk).
Use: both enter the same shared code -> caller presses (1) and sends the code -> callee presses (2), pastes it,
presses "create reply" and sends the reply back -> caller pastes it and presses connect.

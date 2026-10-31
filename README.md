# SecureCall

Android 1-to-1 video/voice call, end-to-end encrypted (WebRTC DTLS-SRTP). No server of your own is needed.

Build: push to GitHub, run the "Build APK" workflow, download artifact SecureCall-apk (app-debug.apk).

Use (automatic): both phones enter the same shared code (generate it once and give it to your friend
through a different channel). One presses "Start call", the other presses "Join call". The encrypted setup
codes travel through a mailbox on the public ntfy.sh service (room name = hash of the shared code), so no
copy/paste is needed. The mailbox only ever sees AES-256-GCM ciphertext, never audio/video or the shared code.
A manual copy/paste mode is kept as a fallback.

Recommended: a TURN server (turn:host:3478,user,pass) on both phones for mobile data / strict NATs.

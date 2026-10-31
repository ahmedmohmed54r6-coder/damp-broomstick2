# SecureCall

Android 1-to-1 video/voice call, end-to-end encrypted (WebRTC DTLS-SRTP). No server of your own is needed.

Build: push to GitHub, run the "Build APK" workflow, download artifact SecureCall-apk (app-debug.apk).

Use: both phones enter the same shared code (generate it once and give it to your friend through a
different channel). Both press "Call" - it does not matter who goes first. The first one waits (up to 5
minutes), the second joins automatically and the app decides who is the caller. The encrypted setup
messages travel through a mailbox on the public ntfy.sh service (room name = hash of the shared code), so
no copy/paste is needed. The mailbox only ever sees AES-256-GCM ciphertext, never audio/video or the shared
code. A manual copy/paste mode is kept as a fallback.

Recommended: a TURN server (turn:host:3478,user,pass) on both phones for mobile data / strict NATs.

Background: while a call (or the wait for the friend) is active, a foreground service with a visible
notification keeps the camera, microphone and network alive when you switch to another app or lock the screen.
Use the notification's "End" button, or the in-app end button, to stop it.

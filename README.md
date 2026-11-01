# SecureCall Family

Android app for parents: call a child's phone, which answers by itself, and talk or watch (voice + video, front or
rear camera). End-to-end encrypted (WebRTC DTLS-SRTP). No server of your own is needed.

Build: push to GitHub, run the "Build APK" workflow, download artifact SecureCall-apk (app-debug.apk).

## Setup
1. Parent phone: choose "هاتف الأهل", type the child's name, press "إنشاء رمز اقتران" (one code per child).
2. Child phone: choose "هاتف الطفل", enter the child's name, the pairing code, and a secret PIN, press "تفعيل".
   Allow camera/microphone/notifications, accept screen pinning, and exclude the app from battery optimization.
   Keep the app open and the phone on a charger.
3. Parent: for each child there are 4 buttons: "نداء صوتي" (call, parent mic open), "استماع" (baby-monitor: listen only,
   parent mic muted, loudness bar + vibration alert when crying/noise lasts; the 🎥 button gives a 15-second low-power
   glance without opening the mic), "صوت وصورة" (watch) and "رسالة" (preset or custom text, works even with no call).
   Older instructions: press "نداء صوتي" (voice, parent mic open) or "صوت وصورة" (watch + listen, parent mic muted until pressed).
   Buttons in the call: mic, video on/off, front/rear camera of the child's phone, end.

## Location (on request only)
- Parent: "📍 موقعه الآن" (one fix) or "📡 تتبع مباشر" (every 20 s for 10 minutes, then stops by itself). The result shows
  accuracy, time, the child phone's battery level, and opens in any maps app.
- Child phone: every request shows a notification + vibration ("your location was sent to the parents"). Requires the
  location permission (asked at child setup) and GPS switched on. Nothing is tracked in the background otherwise.
- Works with or without a running call. Choose "precise" location when Android asks on the child phone.

## Battery
- Child phone in standby: screen may switch off, camera/mic are idle, one light network connection only (backs off up
  to 60 s when offline). Camera/mic run only during a call. Listen mode uses audio only; glance/low-power video is
  320x240 at 10 fps and switches itself off after 15 s. Exclude the app from battery optimization on both phones.

## Transparency / safety design
- The child phone shows a persistent notification and an on-screen "parent on the line" screen during every call;
  a red banner is shown whenever its camera is live. Android also shows its own camera/mic indicators.
- Only the holder of the pairing code can start a call (setup messages are AES-256-GCM encrypted with it).
- Leaving child mode needs the parent's PIN; there is no End button on the child's notification.
- Tell your children that the phone is a family communication/monitoring device.

## Limits
- Android does not allow a camera/microphone service to start by itself after a reboot: after restarting the child
  phone, open the app once (it re-enters child mode by itself).
- Some phone makers (Xiaomi, Huawei, Samsung...) kill background apps aggressively: use the battery exclusion and
  leave the app open on screen.
- A TURN server on both phones is recommended for mobile data / strict networks. No free public TURN works without an
  account anymore (the old openrelayproject credentials are dead). One-tap setup: in the TURN card press "Metered" or
  "Cloudflare", create a free account, copy the iceServers config (JSON or url,user,pass), then press "لصق" (paste).
  Press "نسخ" (copy) and paste the same config on the other phone. Several servers (one per line) are supported.
- The public ntfy.sh service only carries encrypted setup messages, never audio/video or the pairing code.

## V14 additions
- TURN card: paste (iceServers JSON or url,user,pass), Metered/Cloudflare sign-up, copy to the other phone, real relay test.
- Cloudflare TURN auto-renewal: enter Turn Token ID + API token once; credentials (24 h) renew themselves on both phones.
- QR pairing: parent "📷 QR" on a child card; child setup "مسح QR" fills name, pairing code and TURN. The QR contains the secret code (screenshots are blocked while it is shown).
- Child heartbeat (every ~5 min, encrypted): parent card shows online/offline, battery, and a notification when the child phone has been silent 15+ min (while the parent app is open).
- FCM wake-up bell (optional): see SETUP_FCM.md.

## V16 additions
- 🌙 Quiet look (child asleep): the child phone opens the camera with no sound and no vibration, the screen stays dark, the parent's mic is muted, and it ends by itself after 30 s. The child's status notification and the on-screen camera banner are NOT hidden (that is by design).
- ⏰ Wake-up alarm: parent sets time (+ optional daily repeat) per child. It travels once through the encrypted mailbox (valid 12 h, picked up even if the child phone was offline when sent); then it lives in Android's AlarmManager, so it rings with no internet, in Doze, and after a reboot. The child can stop it.
- Font: Noto Naskh Arabic (SIL OFL 1.1) is downloaded at build time to app/src/main/assets/fonts/naskh.ttf. Offline build? Put the file there yourself; otherwise the system serif is used.

## V17 — reliability + privacy
- Root causes found and fixed:
  * ntfy.sh's free tier allows only 250 messages/day per device. The V14 heartbeat (every 5 min) alone used ~288/day, which made calls/locations fail after "many attempts". The heartbeat is gone; presence is now a live ping/pong (the parent taps the status or opens the screen).
  * The child's listener only lived inside the open app. New always-on `ChildListenService` (foreground, quiet notification) answers calls, alarms, locations and pings with the app closed, and launches the app for a call ("display over other apps" / full-screen notification / FCM).
  * A bug cleared the periodic timers after every call (`resetCall()` wiped them): they now run on their own handler.
  * The child ignored the parent's repeated call request once a session had started, so one lost reply meant a failed attempt: it now answers again. Offer/answer sends retry, and ICE gathering finishes 1.5 s after the first usable candidate (was up to 7 s per side).
  * Location in background needs "allow all the time"; the readiness check (🩺) lists every missing setting, including notifications off.
- "Online" status is now real: 🟢 only if the child answered a live ping in the last 3 min; 🔴 when it did not answer.
- Optional private ntfy server (card "خادم الوسيط") removes the daily cap; it is carried by the pairing QR.
- Crypto: messages are now SKC2: HKDF-SHA256 with a fresh random salt per message (one key per message), AES-256-GCM, kind/binding/salt authenticated, payload padded to 256-byte blocks, bounded decompression. Media stays DTLS-SRTP (forward secret). SKC1 is still readable so old apps don't break the transition.

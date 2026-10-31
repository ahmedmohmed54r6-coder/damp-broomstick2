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
- A TURN server (turn:host:3478,user,pass) on both phones is recommended for mobile data / strict networks.
- The public ntfy.sh service only carries encrypted setup messages, never audio/video or the pairing code.

# SecureCall

Encrypted two-person video/voice call app for Android. Both sides are equal: whoever calls, the
other side gets a normal ringing incoming-call screen (Accept/Decline) — nothing answers itself,
nothing tracks location, nothing hides its own notification.

## Pairing
One device: "إنشاء رمز اقتران جديد" → shows a QR code + text code.
Other device: "مسح رمز QR" (or paste the text code) → both devices now share the same secret code.

## Calling
From the home screen: "اتصال فيديو" or "اتصال صوتي". The other side rings and chooses to accept
or decline. In-call text chat is available via the 💬 button.

## TURN / relay servers
Optional but recommended for calls across mobile data — see the in-app settings cards.

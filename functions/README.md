# تفعيل نبضة الإيقاظ الفورية (Firebase) — خطوات لمرة واحدة

هذا الجزء لازم تسويه إنت بحسابك الشخصي؛ أنا جهّزت كل الكود وخليته جاهز يشتغل فور ما تخلّص الخطوات.

## 1) أنشئ مشروع Firebase
1. روح https://console.firebase.google.com → "Add project" → اختر اسم (مثلاً SecureCallFamily).
2. مو لازم تفعّل Google Analytics، اختياري.

## 2) أضف تطبيق أندرويد للمشروع
1. بصفحة المشروع: "Add app" → أندرويد.
2. Package name لازم يكون بالضبط: `com.securecall.app`
3. نزّل ملف `google-services.json` اللي يطلع لك، وحطّه داخل مجلد `app/` بمشروع SecureCall (جنب `build.gradle` الخاص بالتطبيق، نفس مستوى `AndroidManifest.xml`'s parent).

## 3) فعّل Firestore
1. من القائمة الجانبية: Firestore Database → Create database → اختر "Production mode" → أي موقع قريب منك.
2. القواعد (`firestore.rules`) جاهزة بالمشروع، بترفعها بالخطوة 5.

## 4) رقّي الخطة لـ Blaze (ادفع حسب الاستخدام)
Cloud Functions يحتاج خطة Blaze حتى للنشر (مو بالضرورة يكلفك شيء — الاستخدام هنا بسيط جدًا ويقع غالبًا ضمن الحد المجاني الشهري). من Console: ⚙️ → Usage and billing → Modify plan → Blaze.

## 5) انشر الكود (Cloud Function + قواعد Firestore)
على جهاز الكمبيوتر عندك (يحتاج Node.js مثبّت):
```bash
npm install -g firebase-tools
firebase login
cd السعر-الى-مجلد-SecureCall      # نفس المجلد اللي فيه firebase.json
firebase use --add                 # اختر مشروعك اللي أنشأته بالخطوة 1
cd functions && npm install && cd ..
firebase deploy --only functions,firestore:rules
```
بآخر السطر بيطلع لك رابط شبيه بـ:
```
https://us-central1-securecallfamily.cloudfunctions.net/sendWake
```
انسخه.

## 6) حط الرابط بالكود
افتح `app/src/main/java/com/securecall/app/WakePusher.kt` وحط الرابط مكان:
```kotlin
private const val WAKE_URL = "" // ← هنا
```

## 7) ابنِ التطبيق من جديد
بعد ما تحط `google-services.json` بمكانه و`WAKE_URL`، ابني الـ APK عادي على الهاتفين (الأهل والطفل) — ما فيه أي خطوة ثانية، التسجيل والإرسال يصيرون تلقائيًا.

---

### ملاحظات أمان مهمة
- `WAKE_URL` ما فيه أي سر — أي حد يعرفه بس ما يقدر يسوي شيء غير يرسل نبضة فاضية لغرفة ما يعرف الـ roomId تبعها أصلاً (والـ roomId نفسه مشتق من كود الاقتران السري). أسوأ حالة لو تسرب الرابط: إزعاج بسيط (نبضة فاضية بدون محتوى)، مو اختراق.
- `firestore.rules` تمنع أي تطبيق يقرأ توكنات غيره — بس Cloud Function (صلاحيات Admin) تقدر تقرأ.
- لو تبي حماية إضافية من إساءة الاستخدام (حد يرسل آلاف النبضات)، أضف Rate Limiting بسيط داخل `functions/index.js` (مثلاً تتبع آخر وقت إرسال لكل roomId برصيد Firestore) — قول لي لو تبيها أضيفها.

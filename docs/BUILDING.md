# 🛠️ بناء الإضافات (Build Guide)

## المتطلبات
- **JDK 17+** (مفضّل OpenJDK 17 أو 21).
- **Android SDK** (compileSdk 35).
- **Gradle Wrapper** (مرفق مع المشروع — لا تحتاج تثبيت Gradle محلياً).

## إعداد سريع

1. عدّل `local.properties` ليحوي مسار Android SDK لديك:

   ```properties
   sdk.dir=/home/USER/Android/Sdk
   ```

2. شغّل أحد أوامر البناء:

   ```bash
   # بناء كل الإضافات
   ./gradlew make

   # بناء إضافة واحدة (Animezid)
   ./gradlew :Animezid:make

   # تنظيف
   ./gradlew clean
   ```

3. ستجد ملفات `.cs3` الناتجة داخل المجلد `build/` في جذر المشروع
   (يضعها هناك Cloudstream Gradle Plugin تلقائياً)، مثلاً:

   ```
   build/Animezid.cs3
   ```

## كيف يتعرف عليها التطبيق؟

تطبيق Cloudstream يقرأ:

1. **رابط الـ repo** الذي يضعه المستخدم في الإعدادات (محتوى الملف `repo` في الجذر):
   ```
   https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/repo
   ```
2. هذا الملف يحتوي على `pluginLists` يشير إلى:
   ```
   https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/plugins.json
   ```
3. ملف `plugins.json` يحوي لكل إضافة حقل `url` يشير لملف `.cs3` الفعلي على GitHub:
   ```
   https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/Animezid.cs3
   ```

## أتمتة عبر GitHub Actions

أضف ملف workflow بسيط لتنفيذ `./gradlew make` تلقائياً عند كل push على فرع `main`
ودفع الملفات المُولّدة إلى `build/`. (راجع المستودع الأصلي لاستلهام التكوين.)

## إضافة موقع جديد لاحقاً

1. أنشئ مجلداً جديداً في الجذر باسم الموقع، مثلاً `MySite/`.
2. أضف `MySite/build.gradle.kts` بنفس نمط `Animezid/build.gradle.kts`.
3. أضف `MySite/src/main/AndroidManifest.xml` و الكود في
   `MySite/src/main/kotlin/com/mysite/`.
4. `settings.gradle.kts` يُدرج المجلد تلقائياً.
5. حدّث `plugins.json` بإضافة عنصر جديد للإضافة.

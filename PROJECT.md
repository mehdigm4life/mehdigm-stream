# 📺 mehdigm-stream — مشروع إضافات Cloudstream

**mehdigm-stream** هو مستودع شخصي لإضافات (plugins/extensions) تطبيق **Cloudstream3** لأنظمة Android و Android TV.  
يقوم هذا المشروع بتوفير إضافات سكرابينغ (scraping) تجلب محتوى الفيديو من مواقع بث عربية بشكل مباشر عبر واجهة Cloudstream.

---

## 📋 قائمة الإضافات

| الإضافة | الموقع | النوع | الكود المصدري |
|---------|--------|-------|---------------|
| **Animezid (انمي زد)** | [animezid.cam](https://animezid.cam) | أنمي + كرتون مدبلج ومترجم | ✅ متوفر |
| **FaselHD** | FaselHD | أفلام + مسلسلات | ❌ ملف .cs3 فقط |
| **ArabSeed (عرب سيد)** | [arabseed.show](https://arabseed.show) | أفلام + مسلسلات عربية وأجنبية | ❌ ملف .cs3 فقط |

---

## 🏗️ هيكل المشروع

```
mehdigm-stream/
│
├── Animezid/                          # كود إضافة Animezid (المصدر الأساسي)
│   ├── build.gradle.kts               # إعدادات Gradle للوحدة
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/animezid/
│           ├── Animezid.kt            # ⭐ منطق السكرابينغ الرئيسي (MainAPI)
│           └── AnimezidPlugin.kt      # نقطة دخول الإضافة (CloudstreamPlugin)
│
├── build/                             # 📦 ملفات .cs3 الجاهزة (يستهلكها Cloudstream)
│   ├── Animezid.cs3
│   ├── ArabSeed.cs3
│   └── FaselHD.cs3
│
├── images/                            # صور الأيقونات
│   └── fav.png                        # أيقونة إضافة FaselHD
│
├── docs/
│   └── BUILDING.md                    # دليل البناء (بالعربية)
│
├── .github/workflows/
│   ├── build.yml                      # CI: بناء تلقائي للإضافات عند كل push
│   └── opencode.yml                   # تشغيل OpenCode AI عبر Cloudflare tunnel
│
├── build.gradle.kts                   # ⚙️ الإعدادات العامة لـ Gradle (كل الوحدات)
├── settings.gradle.kts                # يُدرج المجلدات كوحدات بشكل تلقائي
├── gradle.properties                  # إعدادات JVM و Android
├── plugins.json                       # 📑 فهرس الإضافات (يقرؤه Cloudstream)
├── repo                               # رابط قصير يشير إلى plugins.json
├── opencode.json                      # إعدادات OpenCode AI
├── gradlew / gradlew.bat             # مشغّل Gradle
├── README.md                          # README الرئيسي (تعليمات التثبيت)
└── PROJECT.md                         # 📄 هذا الملف — شرح المشروع
```

---

## ⚙️ التقنيات المستخدمة (Tech Stack)

| التقنية | الغرض |
|---------|-------|
| **Kotlin** | لغة البرمجة الأساسية |
| **Cloudstream3 SDK** (`com.lagradost:cloudstream3:pre-release`) | إطار العمل الذي تبنى عليه الإضافات |
| **Gradle 8.12** | نظام البناء |
| **Cloudstream Gradle Plugin** (`com.github.recloudstream:gradle:2.2.4`) | ينتج ملفات `.cs3` القابلة للتثبيت |
| **Android SDK** (compileSdk 35, minSdk 21) | منصة Android المستهدفة |
| **jsoup** (`1.19.1`) | تحليل وصفح HTML واستخراج البيانات |
| **NiceHttp** (`0.4.13`) | مكتبة HTTP لإجراء الطلبات |
| **Jackson / Gson / kotlinx-serialization** | تحليل JSON |
| **kotlinx-coroutines** | البرمجة غير المتزامنة |
| **GitHub Actions** | CI/CD — بناء تلقائي ونشر |

---

## 🧠 كيف تعمل الإضافة؟ (معمارية السكرابينغ)

### تدفق العمل

```
Cloudstream App
      │
      ├── يقرأ repo → plugins.json → يجد Animezid.cs3
      │
      ├── installs .cs3 → تسجل الإضافة في التطبيق
      │
      ├── المستخدم يفتح الإضافة
      │     │
      │     ├── getMainPage()    → يعرض الصفحة الرئيسية (أقسام: أنمي، أفلام، إلخ)
      │     ├── search(query)    → يبحث في الموقع
      │     ├── load(url)        → يحمّل تفاصيل مسلسل/فيلم + قائمة الحلقات
      │     └── loadLinks(url)   → يستخرج روابط السيرفرات لتشغيل الفيديو
      │
      └── loadExtractor()       → Cloudstream يتعامل مع مشغّلات الفيديو الخارجية
```

### 1. الصفحة الرئيسية (`getMainPage`)

يجلب الإضافة محتوى من 6 أقسام على موقع animezid.cam:

| القسم | الرابط |
|-------|--------|
| الأنمي | `/category.php?cat=anime` |
| الأفلام | `/category.php?cat=movies` |
| المسلسلات | `/category.php?cat=series` |
| ديزني بالمصري | `/category.php?cat=disney-masr` |
| سبيستون | `/category.php?cat=spacetoon` |
| الأكثر مشاهدة | `/topvideos.php` |

يستخرج الكروت (صورة + عنوان + رابط) من عناصر `div#movies a.movie` و `div.movies a.movie`.

### 2. البحث (`search`)

يرسل طلب إلى `search.php?keywords=<query>` و يحلل النتائج بنفس طريقة الصفحة الرئيسية.

### 3. تحميل التفاصيل (`load`)

تتعامل الدالة مع حالتين:

#### أ. مسلسل (متعدد الحلقات)
إذا كان الرابط يبدأ بالبادئة `SERIES::` أو كان رابط تصنيف (`category.php?cat=...`):
- تجلب الإضافة الصفحة الأولى من الحلقات
- تستمر في التصفح عبر الصفحات اللاحقة (حتى 100 صفحة)
- تستخرج رقم الحلقة من النص العربي مثل **"الحلقة 31"**
- ترتب الحلقات تصاعدياً (الموقع يعرض الأحدث أولاً)
- تعيد كائن `AnimeLoadResponse` مع قائمة الحلقات

#### ب. فيلم / حلقة واحدة
إذا كان الرابط من نوع `watch.php?vid=...`:
- تستخرج العنوان، الملصق، القصة، السنة، التقييم، والتصنيفات (tags)
- تحدد إذا كان المحتوى فيلم أم حلقة أنمي بناءً على العنوان والتصنيفات
- تعيد `MovieLoadResponse` أو `AnimeLoadResponse` حسب النوع

### 4. تحميل روابط التشغيل (`loadLinks`)

- تحوّل رابط `watch.php?vid=XXX` إلى `play.php?vid=XXX`
- تبحث عن قائمة السيرفرات في `<ul id="xservers"> <button data-embed="...">`
- تستخدم `loadExtractor()` من Cloudstream للتعامل مع مشغّلات الفيديو المختلفة (Google Drive, Upstream, إلخ)

---

## 📥 كيفية التثبيت في Cloudstream

1. افتح تطبيق Cloudstream
2. اذهب إلى **Settings → Extensions ← الزر "+" → Add Repository**
3. الصق الرابط التالي:

```
https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/repo
```

أو مباشرة:

```
https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/plugins.json
```

4. ستظهر الإضافات المتاحة — اضغط **Install** بجانب الإضافة التي تريدها.

---

## 🛠️ البناء من المصدر (Build)

### المتطلبات
- **JDK 17**
- **Android SDK** (API 35)
- متغير بيئة `ANDROID_HOME` أو `local.properties` فيه مسار SDK

### الأوامر

```bash
# بناء كل الإضافات
./gradlew make

# بناء إضافة واحدة (Animezid)
./gradlew :Animezid:make
```

الملفات الناتجة `.cs3` توضع في مجلد `build/`.

### بنية Gradle

- **`build.gradle.kts`** (الجذر): يطبق إعدادات مشتركة على كل الوحدات (Android Library, Kotlin, Cloudstream Plugin) ويضيف dependencies عامة (jsoup, NiceHttp, Jackson, Gson, kotlinx-serialization, kotlinx-coroutines).
- **`settings.gradle.kts`**: يدوّر على كل المجلدات ويدرج تلقائياً أي مجلد يحتوي على `build.gradle.kts` كوحدة Gradle.
- **`Animezid/build.gradle.kts`**: إعدادات خاصة بإضافة Animezid (namespace, dependencies إضافية).

### CI/CD — GitHub Actions

عند كل push إلى الفرع `main`، يعمل workflow الـ build:
1. يسحب الكود
2. ينصب JDK 17 + Android SDK
3. يشغّل `./gradlew make`
4. يجمع ملفات `.cs3` من كل وحدة
5. يضغطها ويرفعها إلى المستودع

---

## 📝 شرح مفصل لملف Animezid.kt

الملف الرئيسي: `Animezid/src/main/kotlin/com/animezid/Animezid.kt`

### الكلاس `Animezid`

يمتد `MainAPI()` من Cloudstream SDK ويطبّق الدوال الأساسية:

| الخاصية/الدالة | الوصف |
|----------------|-------|
| `mainUrl` | رابط الموقع `https://animezid.cam` |
| `name` | اسم الإضافة `"Animezid"` |
| `lang` | اللغة `"ar"` |
| `supportedTypes` | أنواع المحتوى المدعومة (Anime, AnimeMovie, Cartoon, TvSeries, Movie, OVA) |
| `mainPage` | تعريف أقسام الصفحة الرئيسية الستة |
| `getMainPage()` | يجلب ويعرض محتوى القسم المطلوب مع دعم التصفح |
| `search()` | يبحث في الموقع ويعيد النتائج |
| `load()` | يحمّل التفاصيل (مسلسل أو فيلم/حلقة منفردة) |
| `loadLinks()` | يستخرج روابط السيرفرات للتشغيل |

### آلية التمييز بين المسلسل والفيلم

تستخدم الإضافة بادئة `SERIES::` لتمييز روابط التصنيفات (التي تمثل مسلسلات متعددة الحلقات) عن روابط المشاهدة المباشرة:

```kotlin
// في toSearchResponse()
val loadUrl = if (isSeriesLink) "SERIES::$absHref" else absHref

// في load()
val isSeries = url.startsWith("SERIES::") || (...)
```

### استخراج رقم الحلقة

تستخدم الـ Regex لاستخراج رقم الحلقة من النص العربي:

```kotlin
val epNum = Regex("الحلقة\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
```

### دعم السيرفرات المتعددة

كل حلقة يمكن أن تحتوي على عدة سيرفرات. الإضافة تسحب كل `data-embed` من `ul#xservers` وتمررها إلى `loadExtractor()`.

---

## 🔗 آلية النشر (Deployment)

```
المطور ← يعدل الكود ← git push
                            │
                    GitHub Actions (build.yml)
                            │
                    ./gradlew make → *.cs3
                            │
                    build/Animezid.cs3 ← محدث
                            │
                    المستخدم ← Cloudstream
                            │
            يقرأ repo → plugins.json → يجد الإضافة
                            │
                        تنزيل .cs3 → تثبيت → استخدام
```

---

## 📄 ملفات الإعدادات المهمة

| الملف | الدور |
|-------|-------|
| **`plugins.json`** | فهرس جميع الإضافات المتاحة. يقرؤه Cloudstream ليعرضها للمستخدم. يحتوي على الاسم، الوصف، الرابط، الإصدار، اللغة، أنواع المحتوى. |
| **`repo`** | رابط قصير يشير إلى `plugins.json`. يُستخدم كرابط المستودع في Cloudstream. |
| **`opencode.json`** | إعدادات OpenCode AI للمساعدة في التطوير. |

---

## ⚠️ إخلاء مسؤولية (DMCA)

- هذه الإضافات تعمل مثل متصفح ويب عادي: تجلب محتوى فيديو متاح للعموم من مواقع طرف ثالث.
- ❌ لا يتم استضافة أي محتوى في هذا المستودع أو في تطبيق Cloudstream.
- 🌐 كل المحتوى مستضاف على مواقع طرف ثالث.
- 👤 المستخدمون وحدهم المسؤولون عن استخدامهم ويجب عليهم الامتثال للقوانين المحلية.
- 📩 للاستفسارات المتعلقة بحقوق النشر، يرجى التواصل مع مستضيفي الملفات الفعليين.

---

<p align="center">
<b>mehdigm-stream</b> — مشروع مفتوح المصدر لأغراض تعليمية.
</p>

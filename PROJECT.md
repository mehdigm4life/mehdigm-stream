# 📺 mehdigm-stream — مشروع إضافات Cloudstream

**mehdigm-stream** هو مستودع شخصي لإضافات (plugins/extensions) تطبيق **Cloudstream3** لأنظمة Android و Android TV.  
يقوم هذا المشروع بتوفير إضافات سكرابينغ (scraping) تجلب محتوى الفيديو من مواقع بث عربية بشكل مباشر عبر واجهة Cloudstream.

---

## 📋 قائمة الإضافات

| الإضافة | الموقع | النوع | الكود المصدري |
|---------|--------|-------|---------------|
| **Animhq (أنمي إتش كيو)** | [animhq.com](https://animhq.com) | أنمي مترجم | ✅ متوفر |
| **Animezid (انمي زد)** | [animezid.cam](https://animezid.cam) | أنمي + كرتون مدبلج ومترجم | ✅ متوفر |
| **FaselHD** | FaselHD | أفلام + مسلسلات | ❌ ملف .cs3 فقط |
| **ArabSeed (عرب سيد)** | [arabseed.show](https://arabseed.show) | أفلام + مسلسلات عربية وأجنبية | ❌ ملف .cs3 فقط |

> **ملاحظة حول Animhq:** الموقع يمنح المشاهدة المجانية (رابط MP4 مباشر موقّع) للحلقة الأولى فقط من كل مسلسل، وما بعدها وكل الأفلام خاصة بالمشتركين (بوسترات الإضافة تعمل كاملة).

---

## 🏗️ هيكل المشروع

```
mehdigm-stream/
│
├── Animhq/                           # كود إضافة Animhq
│   ├── build.gradle.kts              # إعدادات Gradle للوحدة
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/animhq/
│           ├── Animhq.kt             # ⭐ منطق السكرابينغ (MainAPI)
│           └── AnimhqPlugin.kt       # نقطة دخول الإضافة (CloudstreamPlugin)
├── Animezid/                          # كود إضافة Animezid (المصدر الأساسي)
│   ├── build.gradle.kts               # إعدادات Gradle للوحدة
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/animezid/
│           ├── Animezid.kt            # ⭐ منطق السكرابينغ الرئيسي (MainAPI)
│           └── AnimezidPlugin.kt      # نقطة دخول الإضافة (CloudstreamPlugin)
│
├── build/                             # 📦 ملفات .cs3 الجاهزة (يستهلكها Cloudstream)
│   ├── Animhq.cs3
│   ├── Animezid.cs3
│   ├── ArabSeed.cs3
│   └── FaselHD.cs3
│
├── .github/workflows/
│   ├── build-*.yml                    # CI: بناء تلقائي لكل إضافة عند push
│   └── opencode.yml                   # تشغيل OpenCode AI عبر Cloudflare tunnel
│
├── build.gradle.kts                   # ⚙️ الإعدادات العامة لـ Gradle (كل الوحدات)
├── settings.gradle.kts                # يُدرج المجلدات كوحدات بشكل تلقائي
├── gradle.properties                  # إعدادات JVM و Android
├── plugins.json                       # 📑 فهرس الإضافات (يقرؤه Cloudstream)
├── Mehdi.json                         # رابط قصير يشير إلى plugins.json
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
      ├── يقرأ Mehdi.json → plugins.json → يجد Animezid.cs3
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

يجلب الإضافة محتوى من 8 أقسام على موقع animezid.cam:

| القسم | الرابط |
|-------|--------|
| أحدث حلقات الأنمي | `/category.php?cat=new-anime-eps` |
| أحدث الحلقات | `/category.php?cat=new-eps` |
| أفلام الأنمي | `/category.php?cat=anime-movies` |
| أفلام الأنيميشن المدبلجة | `/category.php?cat=dubbed-animation` |
| ديزني بالمصري | `/category.php?cat=disney-masr` |
| سبيستون | `/category.php?cat=spacetoon` |
| أحدث الأفلام | `/category.php?cat=new-movies` |
| الأكثر مشاهدة | `/topvideos.php` |

يستخرج الكروت (صورة + عنوان + رابط) من عناصر:
`a.az-card__link` (الأقسام والبحث) و `a.az-showcase-card__link` (الواجهة الرئيسية).
كل كارت يحمل: `.az-card__title` (العنوان)، `img[src]` (البوستر)، وشارات مثل
`.az-badge--episode strong` (رقم الحلقة) و `.az-badge--rating` (التقييم).

### 2. البحث (`search`)

يرسل طلب إلى `search.php?keywords=<query>` ويحلل النتائج بنفس محددات الكروت.
إذا كان الرابط يحتوي `/series/` فيُعالج كمسلسل، وإلا كفيلم/حلقة. الكروت تُمرَّر
بعنوانها المطلق (`https://animezid.cam/series/...` أو `.../watch.php?vid=...`)
دون أي بادئة — لأن Cloudstream يمرّر روابط `new*SearchResponse` عبر `fixUrl()` الذي
يشوّه أي بادئة غير مبدوءة بـ `http` (فمثلاً `SERIES::https://...` تتحوّل إلى
`https://animezid.cam/SERIES::https://...` → 404 عند الضغط). ولهذا يُعتمد على وجود
`/series/` داخل الرابط نفسه للتمييز.

### 3. تحميل التفاصيل (`load`)

يحدد المسار حسب نوع الرابط:

#### أ. صفحة مسلسل `/series/{slug}/` (معامل `SERIES::`)
- يقرأ العنوان والبوستر والقصة من وسوم `og:title / og:image / og:description`.
- يجمع روابط المواسم من `a.az-card__link[href$=/season/N/]` الموجودة في الصفحة.
- لكل موسم يطحلب صفحات الجزء بلا فوضى عبر `?ajax=episodes&page=N` (يُرسل مع
  الهيدر `X-Requested-With: XMLHttpRequest`) ويستخرج الحلقات من
  `div.az-series-episode-grid-item` (مع `data-episode-number`).
- يتوقف عند تكرار الدفعات (بعد آخر صفحة يعيد الموقع نفس الدفعة) أو عند الوصول
  للحد الأقصى من الصفحات، ثم يرتب النتائج بمفاتيح (الموسم، رقم الحلقة).

#### ب. صفحة مشاهدة `watch.php?vid=...`
- إذا وجدت تبويبات المواسم `nav.az-cinema-season-tabs a[data-season-link]`
  (تحمل `data-season` و `data-season-count` والموسم الحالي) فالمحتوى مسلسل:
  - الحلقات الظاهرة في `div.az-cinema-episode-grid` تُضاف مباشرة.
  - المواسم الأخرى (أو ناقص الموسم النشط) تُستكمل عبر نفس AJAX pagination.
  - يُقرأ العنوان من `h1`، البوستر من `figure.az-cinema-poster img`، القصة من
    `p.az-cinema-summary`، والسنة/النوع/البلد/الترجمة من `ul.az-cinema-meta li`.
- إذا لم توجد تبويبات مواسم فالمحتوى فيلم → `MovieLoadResponse`.

### 4. تحميل روابط التشغيل (`loadLinks`)

الموقع الآن يحمي السيرفرات خلف واجهة برمجية. التدفق:

1. `GET play.php?vid=XXX` → استخراج `data-playback-csrf` و `data-video-uniq`
   مع حفظ **كوكيز الجلسة** من الاستجابة (`PHPSESSID` + `watched_video_list`).
2. `POST https://animezid.cam/web-playback/sessions` (هيدرات
   `Content-Type: application/json` + `X-Playback-CSRF` + `Origin`/`Referer` +
   **`Cookie` صريح** من الخطوة 1، جسم `{"content_id": vid}`)
   → `session_id` + قائمة `sources[]`.

   > ⚠️ كوكيز `play.php` **إلزامي** ولا يوفّرها Cloudstream تلقائياً: كائن
   > `app` في Cloudstream هو `NiceHttp.Requests` بلا CookieJar، لذا بدون تمرير
   > `Cookie` يدوي ستعيد `sessions` و `resolve` كود `403 {"error":"forbidden"}`
   > ويظهر "no link found". تُبنى من `buildCookieHeader()` ثم تمرر عبر
   > `playbackHeaders(csrf, referer, cookies)`.

3. لكل مصدر من نوع `embedded_web`: `POST .../sessions/{sid}/sources/{srcId}/resolve`
   → `launch_url`.
4. `GET launch_url` (تتبع إعادة التوجيه، مع الكوكيز والـ Referer) → عنوان المستضيف
   الحقيقي (Uqload، DoodStream، StreamWish، MegaMax، StreamRuby، إلخ).
5. `normalizeEmbedUrl()` يقرّب المستضيفات إلى نطاقات يدعمها extractor مدمج:
   `uqload.vc` / `uqload.to` → `uqload.com` (نفس الـ embed id على كل نطاقات uqload)،
   ثم يمرّر العنوان لـ `loadExtractor()`، وإن فشل تُجرَّب استخراج من صفحة الـ embed
   عبر `tryProviderExtract()`:
   - **`decodePacker()`**: يفكّ حزم Dean Edwards
     `eval(function(p,a,c,k,e,d)...)` التي تعتمدها عائلات Uqload/StreamRuby/StreamWish
     (الرابط الموقّع `master.m3u8` يظهر فقط بعد الفك)،
   - ثم تعبيرات `m3u8`/`mp4` مباشرة (يغطّي TurboViPlay وكل صفحة فيها رابط تدفق صريح)،
   وإلا تسجيل رابط احتياطي.

> ملاحظة: روابط هذا الجيل موقّعة ومرتبطة بالـ IP/ASN؛ CDN مثل `streamruby.net`
> يرد `403` على عناوين مراكز البيانات، لذا يتحقق الاختبار المحلي من وجود الرابط
> الموقّع في الصفحة المفكوكة لا من تشغيله على نفس الجهاز.

نقاط قوة التنفيذ: إعادة المحاولة مع تأخير متصاعد عند `403`/`429` (الموقع يفرض
rate-limit)،
تجاهل مصادر التحميل (`download`) واكتفاء بـ `embedded_web`، وتجاهل الروابط التي
تعيد التوجيه إلى animezid نفسه. وإذا لم تتوفر توكنات `data-playback-*` فتُستخدم
محاولة قديمة عبر `button[data-embed]` و `iframe[src]`.

---

## 📥 كيفية التثبيت في Cloudstream

1. افتح تطبيق Cloudstream
2. اذهب إلى **Settings → Extensions ← الزر "+" → Add Repository**
3. الصق الرابط التالي:

```
https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/Mehdi.json
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

في نتائج البحث والقوائم يُمرَّر الرابط المطلق مباشرة (بدون بادئة) ويتم التمييز
بفحص محتوى الرابط: وجود `/series/` يعني مسلسلاً، ووجود `watch.php` يعني صفة
مشاهدة مباشرة. كما تُفحص صفحة `watch.php` نفسها: وجود تبويبات المواسم
`a[data-season-link]` يعني مسلسلاً، وغيابها يعني فيلماً.

> ملاحظة: لا تُستخدم بادئة مثل `SERIES::` أبداً في نتائج البحث — Cloudstream يمرّر
> روابط `new*SearchResponse` عبر `fixUrl()` الذي يضيف `mainUrl + "/"` لأي رابط لا
> يبدأ بـ `http`، فيتحول `SERIES::https://...` إلى
> `https://animezid.cam/SERIES::https://...` (رابط مكسور يعطي 404 عند الضغط).

```kotlin
// في toSearchResponse()
val loadUrl = absHref   // URL مطلق مباشرة، بلا بادئة

// في load() — dispatch
when {
    clean.contains("/series/") -> buildSeriesFromSeriesPage(clean)
    clean.contains("watch.php") -> {
        val hasSeasonTabs = doc.selectFirst("nav.az-cinema-season-tabs a[data-season-link]") != null
        if (hasSeasonTabs) buildSeriesFromWatchPage(clean, doc)
        else buildMovieFromWatchPage(clean, doc)
    }
    ...
}
```

### استخراج رقم الحلقة

رقم الحلقة يُقرأ من الخاصية `data-episode-number` على عنصر
`div.az-series-episode-grid-item` (أرقام قد تكون عشرية مثل `1122.5`)، أو من
`strong` داخل `a[role=listitem]` في شبكة الحلقات الظاهرة، مع استخراج فعلي
كبديل عبر regex.

### دعم السيرفرات المتعددة

كل حلقة تحوي عدة سيرفرات تُجلب من واجهة `web-playback` المحمية (POST sessions →
POST resolve لكل مصدر `embedded_web` → تتبع إعادة التوجيه إلى الـ embed). تمرر
الروابط إلى `loadExtractor()` لدعم حاضنات الإضافة التلقائية (Uqload, Dood,
StreamWish, FileMoon ...)، مع استخراج عام للـ m3u8/MP4 كبديل للسيرفرات غير
المعروفة، وإعادة محاولة مع backoff عند أي `403`.

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
            يقرأ Mehdi.json → plugins.json → يجد الإضافة
                            │
                        تنزيل .cs3 → تثبيت → استخدام
```

---

## 📄 ملفات الإعدادات المهمة

| الملف | الدور |
|-------|-------|
| **`plugins.json`** | فهرس جميع الإضافات المتاحة. يقرؤه Cloudstream ليعرضها للمستخدم. يحتوي على الاسم، الوصف، الرابط، الإصدار، اللغة، أنواع المحتوى. |
| **`Mehdi.json`** | رابط قصير يشير إلى `plugins.json`. يُستخدم كرابط المستودع في Cloudstream. |
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

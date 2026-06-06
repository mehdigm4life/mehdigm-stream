<h1 align="center">📺 mehdigm-stream — إضافات Cloudstream</h1>

<h3 align="center">
مستودع إضافات شخصي لتطبيق <b>Cloudstream</b> على أندرويد.
<br>
يحتوي حالياً على إضافة موقع <b>انمي زد (AnimeZid)</b> لمشاهدة الأنمي والكرتون المدبلج والمترجم.
</h3>

<p align="center">
✨ <b>سهلة الاستخدام • خفيفة • ودية للـ Android TV</b><br>
🎬 يدعم: قائمة رئيسية، بحث، تفاصيل، حلقات متعددة، وعدّة سيرفرات لكل حلقة.
</p>

---

## ⬇️ كيفية التثبيت في Cloudstream

انسخ الرابط التالي والصقه داخل تطبيق Cloudstream من خلال:
**Settings → Extensions → الزر "+" → Add Repository**

```text
https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/repo
```

أو استخدم رابط `plugins.json` مباشرة:

```text
https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/plugins.json
```

---

## 🧩 الإضافات المتوفرة

| الإضافة      | الموقع                                              | النوع                |
| ------------ | --------------------------------------------------- | -------------------- |
| **Animezid** | [animezid.cam](https://animezid.cam)                | أنمي + كرتون (عربي)  |

---

## 🛠️ البناء (Build)

المشروع يعتمد على [Cloudstream Gradle Plugin](https://github.com/recloudstream/gradle).

```bash
# بناء كل الإضافات
./gradlew make

# بناء إضافة واحدة فقط (Animezid)
./gradlew :Animezid:make
```

ملفات `.cs3` الناتجة توضع تلقائياً داخل المجلد `build/` في جذر المشروع، وهي الملفات التي يقرأها التطبيق فعلياً.

---

## 📁 هيكل المشروع

```
mehdigm-stream/
├── Animezid/                       # كود إضافة موقع animezid.cam
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/animezid/
│           ├── Animezid.kt         # MainAPI: scraping كامل للموقع
│           └── AnimezidPlugin.kt   # نقطة دخول CloudstreamPlugin
├── build/                          # ملفات .cs3 الجاهزة (يقرؤها التطبيق)
│   └── Animezid.cs3
├── plugins.json                    # فهرس الإضافات (يقرؤه Cloudstream)
├── repo                            # ملف الـ repo (الرابط القصير)
├── build.gradle.kts                # الإعدادات العامة للـ Gradle
├── settings.gradle.kts             # يُدرج كل المجلدات تلقائياً
└── README.md
```

---

## ⚖️ DMCA Disclaimer

These extensions work like a regular web browser: they fetch publicly available video resources from third-party websites.

- ❌ No content is hosted by this repository or by the Cloudstream application.
- 🌐 All content is hosted by third-party websites.
- 👤 Users are solely responsible for their usage and must comply with their local laws.
- 📩 For copyright concerns please contact the actual file hosts, not the developers of this repository.

---

<p align="center">
💖 <b>استمتع بالمشاهدة!</b>
</p>

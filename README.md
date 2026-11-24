# GameLens Translator | مترجم الألعاب الفوري

<p align="center">
  <img src="docs/gamelens_icon.svg" width="128" height="128" alt="GameLens Icon" />
</p>

<p align="center">
  <strong>No-Root • Zero-Injection • Privacy-First • Offline-First</strong><br>
  مترجم ألعاب فوري ومفتوح المصدر لنظام أندرويد يعتمد على الرؤية الحاسوبية والذكاء الاصطناعي دون تعديل ملفات اللعبة وبدون حظر.
</p>

---

## 🌟 Highlights | أبرز المميزات

### 🇸🇦 العربية
- **أمان تام 100% (بدون روت وبدون حظر):** لا يتم حقن أي شفرة في ذاكرة اللعبة (Zero-Memory Injection)، ولا يتم تعديل ملفات APK. قراءة النصوص تتم عبر بث الشاشة المؤقت في الذاكرة الحية فقط (MediaProjection) دون حفظ لقطات شاشة على القرص.
- **تزامن بصري دقيق 1:1 (Visual Presence Sync):** تظهر الترجمة العربية بالتزامن التام مع وجود النص الإنجليزي وتختفي فور اختفائه، مع منع تام للوميض (Zero-Flicker).
- **محرك باتشات الألعاب 0ms (Community Patch Engine):** تحميل ملفات ترجمة مجتمعية مسبقة (`.json` أو `.srt` أو `.txt`) لعرض ترجمة فورية بدون استهلاك للبطارية أو المعالج مع دعم دمج ملفين (إنجليزي + عربي) تلقائياً.
- **ترجمة صوت اللعبة الداخلي (Sherpa-ONNX ASR):** التقاط أصوات اللعبة عبر `AudioPlaybackCapture` وتفريغ الكلام الإنجليزي فورياً وعرضه في شريط سينمائي أسفل الشاشة بالكامل بدون إنترنت.
- **معالج تصحيح الكلمات الإنجليزية (Offline English Repairer):** تصحيح الكلمات الملتصقة والمقطوعة الناتجة عن خطوط الألعاب المزخرفة بدون إنترنت (`en_dict.txt`).
- **واجهة Material 3 ثلاثية التبويبات مع مخصص المظهر الحي:**
  - **الرئيسية:** إدارة أذونات النظام، اختيار محرك الترجمة (محلي أوفلاين، سحابي ذكي، أو خادم ذكاء اصطناعي محلي مع رابط Base URL مخصص)، وتبديل الملف التعريفي للعبة (Game Profile).
  - **المظهر والباتشات:** مخصص مظهر الترجمة مع شريط معاينة حي (تعديل حجم الخط 14sp–28sp، ألوان مسبقة: أبيض، ذهبي، سماوي، شفافية الخلفية 40%–100%، وظلال سينمائية)، ومعايرة منطقة القراءة (ROI).
  - **السجل والتحديثات:** تصفح وتصدير سجل الحوارات (`.json` / `.srt`)، وزر التحقق المباشر من تحديثات التطبيق عبر GitHub، ووضع المطور.
- **محدث داخلي عبر GitHub Releases:** فحص الإصدارات الجديدة وتنزيل وتثبيت ملفات APK بنقرة واحدة داخل التطبيق بأمان.

---

### 🇬🇧 English
- **100% Safe & Anti-Cheat Proof (No-Root, Zero Injection):** Does not read game memory or modify APKs. Operates purely through Android's `MediaProjection` API in volatile RAM without saving screenshots to disk. Completely immune to game anti-cheat bans.
- **1:1 Subtitle Visual Presence Sync:** Subtitle overlays match game dialogues exactly frame-by-frame with zero flicker, Keep-Alive continuity, and dynamic auto-dismissal.
- **0ms Community Patch Engine:** Instant zero-latency subtitle replacement using community JSON, SRT, or TXT translation files, including in-app dual-file alignment.
- **Internal Game Audio Translation (Sherpa-ONNX ASR):** Direct internal audio capture via `AudioPlaybackCapture` with offline speech recognition (Zipformer + Silero VAD) rendered into a cinematic bottom subtitle bar.
- **Universal Offline Word Repairer:** Corrects glued and broken game font OCR artifacts offline via a fast DP-segmentation dictionary engine.
- **Modern 3-Tab Material 3 Interface:**
  - **Home:** Permission status, translation engines (ML Kit Offline, Smart Auto Cloud, Custom AI / Local LLM Base URL), active game profiles.
  - **Appearance & Patches:** Live Subtitle Customizer (font size, color presets, opacity slider, cinematic shadow/border), ROI calibration, community patch loader.
  - **History & Updates:** Dialogue export (JSON / SRT), in-app GitHub releases updater, and collapsible diagnostic console.
- **In-App GitHub Auto-Updater:** Checks GitHub Releases for updates and installs newer APK builds directly with single-tap confirmation.

---

## 📦 Community Patch Format | صيغة ملفات الباتش المجتمعية

GameLens supports instant 0ms translation matching using clean key-value JSON patch files:

```json
{
  "Game": "Hitman: Absolution",
  "Version": "1.0",
  "Author": "Community",
  "Entries": {
    "Diana Burnwood.": "ديانا بيرنوود.",
    "Good luck, 47.": "حظاً موفقاً، يا 47.",
    "Target down. Now find an exit.": "تم القضاء على الهدف. ابحث الآن عن مخرج.",
    "You have been spotted!": "لقد تم اكتشاف أمرك!"
  }
}
```

You can also import standard `.srt` subtitle files or pair two raw `.txt` files (one containing English dialogue lines, one containing matching Arabic lines) directly from the **Appearance & Patches** tab.

---

## 🚀 Installation | التثبيت

### Method 1: Direct APK (GitHub Releases)
1. Download the latest `app-debug.apk` or release build from [GitHub Releases](https://github.com/Zlatan-RDR/GameLensTranslator/releases).
2. Install the APK on your Android device (Android 8.0+ / HyperOS / OneUI / OxygenOS).
3. Grant **Display Over Other Apps** and start the floating translator.

### Method 2: Obtainium (Auto-Updates)
Add the repository URL to [Obtainium](https://github.com/ImranR98/Obtainium):
```text
https://github.com/Zlatan-RDR/GameLensTranslator
```
Obtainium will automatically notify and update GameLens whenever a new release is published.

---

## 🛠️ Building from Source | البناء من المصدر

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17
- Android SDK Platform 35 / Build-Tools 35.0.0

### Command Line Build
```bash
# Clone the repository
git clone https://github.com/Zlatan-RDR/GameLensTranslator.git
cd GameLensTranslator

# Run Unit Tests
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug
```
The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License | الترخيص

This project is licensed under the **GNU General Public License v3.0** (`GPL-3.0`). See the [LICENSE](LICENSE) file for complete terms.

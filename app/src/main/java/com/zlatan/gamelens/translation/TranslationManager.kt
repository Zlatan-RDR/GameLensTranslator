package com.zlatan.gamelens.translation

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.zlatan.gamelens.db.TranslationCacheManager
import com.zlatan.gamelens.glossary.GlossaryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    ERROR
}

class TranslationManager(
    private val context: Context,
    private val cacheManager: TranslationCacheManager,
    private val glossaryManager: GlossaryManager,
    private var config: TranslationConfig = TranslationConfig()
) {

    companion object {
        private const val TAG = "TranslationManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val DANGLING_WORDS = setOf(
            "to", "for", "with", "and", "but", "or", "that", "which", "who", "if", "when", "while",
            "because", "the", "a", "an", "my", "your", "his", "her", "their", "our", "is", "are",
            "was", "were", "will", "would", "can", "could", "should", "must", "have", "has", "had",
            "in", "on", "at", "from", "of", "by", "as", "just", "not"
        )

        private val CONTINUATION_WORDS = setOf(
            "and", "but", "or", "so", "because", "which", "who", "that", "if", "when", "while", "until", "unless"
        )

        private val COMMON_VERBS = setOf(
            "is", "are", "was", "were", "am", "be", "been", "being",
            "do", "does", "did", "done", "have", "has", "had",
            "go", "goes", "went", "gone", "come", "came", "see", "saw", "seen",
            "run", "ran", "stop", "stopped", "wait", "waited", "look", "looked",
            "kill", "killed", "shoot", "shot", "take", "took", "taken",
            "get", "got", "gotten", "help", "helped", "hide", "hid", "move", "moved"
        )

        val DEFAULT_PROTECTED_GLOSSARY = linkedMapOf(
            "Diana Burnwood" to "ديانا بيرنوود",
            "Diana Burn Wood" to "ديانا بيرنوود",
            "Burnwood" to "بيرنوود",
            "Benjamin Travis" to "بنيامين ترافيس",
            "Travis" to "ترافيس",
            "Benjamin" to "بنيامين",
            "Agent 47" to "العميل 47",
            "Chicago" to "شيكاغو"
        )

        /**
         * Quick static check for service startup gate.
         */
        fun verifyArabicModelDownloaded(onResult: (Boolean) -> Unit) {
            val modelManager = RemoteModelManager.getInstance()
            val arabicModel = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
            modelManager.isModelDownloaded(arabicModel)
                .addOnSuccessListener { isDownloaded -> onResult(isDownloaded) }
                .addOnFailureListener { onResult(false) }
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1200L, TimeUnit.MILLISECONDS)
        .readTimeout(1200L, TimeUnit.MILLISECONDS)
        .writeTimeout(1200L, TimeUnit.MILLISECONDS)
        .build()

    private val googleHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(900L, TimeUnit.MILLISECONDS)
        .readTimeout(900L, TimeUnit.MILLISECONDS)
        .writeTimeout(900L, TimeUnit.MILLISECONDS)
        .build()

    private val fastMemoryCache = LruCache<String, String>(256)

    private val modelManager = RemoteModelManager.getInstance()
    private val arabicModel = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()

    private val _modelStatusFlow = MutableStateFlow(ModelStatus.NOT_DOWNLOADED)
    val modelStatusFlow: StateFlow<ModelStatus> = _modelStatusFlow.asStateFlow()

    private var mlKitTranslator: Translator? = null

    @Volatile
    var isMlKitModelReady = false
        private set

    @Volatile
    var modelStatusString = "جاري التحقق..."
        private set

    init {
        initMlKitTranslator()
        checkModelState()
    }

    fun updateConfig(newConfig: TranslationConfig) {
        this.config = newConfig
        Log.d(TAG, "TranslationConfig updated: Mode=${config.mode}, Model=${config.modelName}")
    }

    fun updateOnlineEnhanced(enabled: Boolean) {
        Log.d(TAG, "Online enhanced translation set to: $enabled")
    }

    /**
     * Fast in-memory fuzzy search across recent LRU translation cache entries (>= 0.90 similarity).
     */
    fun findFuzzyMemoryCacheMatch(text: String, threshold: Float = 0.90f): String? {
        val snapshot = fastMemoryCache.snapshot()
        for ((cachedEn, cachedAr) in snapshot) {
            if (com.zlatan.gamelens.ocr.OcrTextCleaner.isSimilar(text, cachedEn, threshold)) {
                return cachedAr
            }
        }
        return null
    }

    /**
     * Enforces correct canon Arabic transliterations for protected character/place names.
     */
    fun applyProtectedGlossary(arabicText: String): String {
        var result = arabicText
        for ((enName, arName) in DEFAULT_PROTECTED_GLOSSARY) {
            result = result.replace(Regex("(?i)\\b$enName\\b"), arName)
        }
        return result
    }

    /**
     * Optional keyless Google Translate endpoint with strict 900ms timeout.
     * Falls back to null immediately on timeout or network errors.
     */
    private suspend fun translateViaGoogleFree(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            googleHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val jsonArray = JSONArray(bodyStr)
                val sentencesArray = jsonArray.optJSONArray(0) ?: return@withContext null
                val sb = java.lang.StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val sArr = sentencesArray.optJSONArray(i)
                    if (sArr != null && sArr.length() > 0) {
                        val transPart = sArr.optString(0, "")
                        sb.append(transPart)
                    }
                }
                val result = sb.toString().trim()
                if (result.isNotEmpty()) result else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Google Free Translate failed/timed out (900ms): ${t.message}, fallback to offline ML Kit")
            null
        }
    }

    fun getStatusString(): String {
        return when (config.mode) {
            TranslationMode.OFFLINE_MLKIT -> {
                if (isMlKitModelReady) "جاهز (محلي)" else modelStatusString
            }
            TranslationMode.CLOUD_OPENAI -> {
                if (config.apiKey.isNotBlank()) "سحابي (${config.modelName})" else "مفتاح API مفقود"
            }
        }
    }

    /**
     * Verifies whether the Arabic offline translation model exists on disk.
     * Does NOT download or perform network operations silently.
     */
    fun checkModelState(onResult: ((ModelStatus) -> Unit)? = null) {
        modelManager.isModelDownloaded(arabicModel)
            .addOnSuccessListener { isDownloaded ->
                val status = if (isDownloaded) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
                _modelStatusFlow.value = status
                isMlKitModelReady = isDownloaded
                modelStatusString = if (isDownloaded) "جاهز للعمل أوفلاين (Ready Offline)" else "غير محمل (Not Downloaded)"
                onResult?.invoke(status)
            }
            .addOnFailureListener { e ->
                _modelStatusFlow.value = ModelStatus.ERROR
                isMlKitModelReady = false
                modelStatusString = "خطأ في فحص النموذج: ${e.message}"
                onResult?.invoke(ModelStatus.ERROR)
            }
    }

    /**
     * Explicitly downloads the offline Arabic translation model.
     * Triggered solely via user interaction in MainActivity.
     */
    fun downloadOfflineModel(onSuccess: () -> Unit = {}, onFailure: (Exception) -> Unit = {}) {
        _modelStatusFlow.value = ModelStatus.DOWNLOADING
        modelStatusString = "جاري تحميل النموذج (Downloading...)"
        val conditions = DownloadConditions.Builder().build()

        modelManager.download(arabicModel, conditions)
            .addOnSuccessListener {
                _modelStatusFlow.value = ModelStatus.READY
                isMlKitModelReady = true
                modelStatusString = "جاهز للعمل أوفلاين (Ready Offline)"
                initMlKitTranslator()
                onSuccess()
            }
            .addOnFailureListener { e ->
                _modelStatusFlow.value = ModelStatus.ERROR
                isMlKitModelReady = false
                modelStatusString = "فشل التحميل: ${e.message}"
                onFailure(e)
            }
    }

    private fun initMlKitTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()

        mlKitTranslator = Translation.getClient(options)
    }

    /**
     * Translates the given English text to Arabic through:
     * 0. Unified 0ms Game Patch Match (.json/.srt/.txt exact or fuzzy Levenshtein >= 0.86)
     * 1. Direct Glossary Match (0ms)
     * 2. L1/L2 Cache Match (0ms)
     * 3. Pre-translation OCR repair & gaming term normalization
     * 4. Selected Engine (ML Kit Offline or OpenAI Cloud REST API with automatic fallback)
     * 5. Post-translation glossary replacements & neural loop de-duplication
     * 6. Session auto-exporter for community patch generation
     */
    suspend fun translate(text: String, isFinal: Boolean = true): String = withContext(Dispatchers.IO) {
        val asrRepaired = normalizeAsrTranscript(text)
        val trimmed = asrRepaired.trim()
        if (trimmed.isEmpty()) return@withContext ""

        // 0. Mandatory 0ms Game Patch Engine Match (Exact or Fuzzy Levenshtein >= 0.86)
        val patchMatch = com.zlatan.gamelens.patch.PatchManager.lookup(trimmed)
        if (patchMatch != null) {
            return@withContext patchMatch
        }

        // Fast In-Memory LRU Cache (0ms)
        val memMatch = fastMemoryCache.get(trimmed)
        if (memMatch != null) {
            return@withContext memMatch
        }

        // Fuzzy In-Memory Cache (0ms, >= 0.90 similarity)
        val fuzzyMatch = findFuzzyMemoryCacheMatch(trimmed, 0.90f)
        if (fuzzyMatch != null) {
            return@withContext fuzzyMatch
        }

        // 1. Direct Game Glossary Exact Match
        val glossaryMatch = glossaryManager.findExactMatch(trimmed)
        if (glossaryMatch != null) {
            return@withContext glossaryMatch
        }

        // 2. Instant Cache (Memory LRU -> Room Persistent DB)
        val cached = cacheManager.get(trimmed)
        if (cached != null) {
            fastMemoryCache.put(trimmed, cached)
            return@withContext cached
        }

        // 3. Pre-translation OCR repair & gaming term normalization
        val preprocessed = preprocessGamingText(trimmed)

        // 4. Translate via configured engine (with optional Online Enhanced fallback)
        val isOnlineEnhanced = context.getSharedPreferences(com.zlatan.gamelens.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(com.zlatan.gamelens.MainActivity.KEY_ONLINE_TRANSLATE_ENHANCED, false)

        val translated = when {
            isOnlineEnhanced -> {
                translateViaGoogleFree(preprocessed) ?: when (config.mode) {
                    TranslationMode.CLOUD_OPENAI -> {
                        if (config.apiKey.isNotBlank()) {
                            translateViaOpenAi(preprocessed) ?: translateViaMlKit(preprocessed)
                        } else {
                            translateViaMlKit(preprocessed)
                        }
                    }
                    TranslationMode.OFFLINE_MLKIT -> translateViaMlKit(preprocessed)
                }
            }
            config.mode == TranslationMode.CLOUD_OPENAI -> {
                if (config.apiKey.isNotBlank()) {
                    translateViaOpenAi(preprocessed) ?: translateViaMlKit(preprocessed)
                } else {
                    translateViaMlKit(preprocessed)
                }
            }
            else -> translateViaMlKit(preprocessed)
        }

        // 5. Post-process with glossary term replacements & protected proper-noun glossary
        val glossaryReplaced = glossaryManager.applyTermReplacements(translated)
        val protectedReplaced = applyProtectedGlossary(glossaryReplaced)

        // 6. Strip ML Kit neural repetition loops & repair glued words
        val deduplicated = deduplicateRepetitions(protectedReplaced)
        val finalResult = repairGluedArabicWords(deduplicated)

        // 7. Save to Cache for subsequent 0ms hits
        fastMemoryCache.put(trimmed, finalResult)
        cacheManager.put(trimmed, finalResult)

        // 8. Log unique pair to Session Exporter (only for completed final sentences, skipping partial chunks)
        if (isFinal) {
            com.zlatan.gamelens.patch.PatchManager.logSessionEntry(context, trimmed, finalResult, isFinal = true)
        }

        finalResult
    }

    /**
     * Pre-translation OCR repair & gaming terminology normalizer.
     * Repairs broken monospace hyphenations, joins split uppercase tokens,
     * and normalizes gaming slang that ML Kit otherwise mistranslates into literal gibberish.
     */
    fun preprocessGamingText(rawText: String): String {
        var processed = rawText.trim()
        if (processed.isEmpty()) return ""

        // 0. Clean Controller & Button Prompts (OPEN ✕, PUT DOWN ○, L1-R3, LB/RB, Press X, Tap Enter)
        processed = normalizeControllerPrompts(processed)

        // 1. Repair broken hyphenation across line breaks: "AVAI- LABLE" -> "AVAILABLE"
        processed = processed.replace(Regex("([a-zA-Z]{2,})-\\s+([a-zA-Z]{2,})"), "$1$2")

        // 2. Repair common monospace split tokens
        processed = processed.replace(Regex("\\b(DIANA)\\s+(BURN\\s*WOOD|BURNE)\\b", RegexOption.IGNORE_CASE), "Diana Burnwood")
        processed = processed.replace(Regex("\\b(BURN)\\s+(WOOD)\\b", RegexOption.IGNORE_CASE), "Burnwood")
        processed = processed.replace(Regex("\\b(AVAI)\\s+(LABLE|LABLE)\\b", RegexOption.IGNORE_CASE), "Available")
        processed = processed.replace(Regex("\\b(OPTI)\\s+(ONS)\\b", RegexOption.IGNORE_CASE), "Options")
        processed = processed.replace(Regex("\\b(DIFFI)\\s+(CULTY)\\b", RegexOption.IGNORE_CASE), "Difficulty")
        processed = processed.replace(Regex("\\b(CONTRO)\\s+(LLER|LS)\\b", RegexOption.IGNORE_CASE), "Controls")
        processed = processed.replace(Regex("\\b(INVEN)\\s+(TORY)\\b", RegexOption.IGNORE_CASE), "Inventory")
        processed = processed.replace(Regex("\\b(CHECK)\\s+(POINT)\\b", RegexOption.IGNORE_CASE), "Checkpoint")

        // 3. Normalize tactical gaming terms for ML Kit neural stability
        processed = processed.replace(Regex("\\bAUTOSAVES?\\b", RegexOption.IGNORE_CASE), "Auto-save")
        processed = processed.replace(Regex("\\bRESPAWNS?\\b", RegexOption.IGNORE_CASE), "reappear at checkpoint")
        processed = processed.replace(Regex("\\b(take|into|behind|in)\\s+cover\\b", RegexOption.IGNORE_CASE), "$1 shielded cover")
        processed = processed.replace(Regex("\\bHITBOX(ES)?\\b", RegexOption.IGNORE_CASE), "damage zone")
        processed = processed.replace(Regex("\\bCOOLDOWNS?\\b", RegexOption.IGNORE_CASE), "recharge duration")

        // 4. Interjection normalization before machine translation
        processed = processed.replace(Regex("\\b[Uu]rgh!?\\b"), "Ugh!")
        processed = processed.replace(Regex("\\b[Aa]rgh!?\\b"), "Ah!")

        return processed
    }

    /**
     * Collapses neural repetition loops (e.g. repeated words or phrases like "أغسطس / أغسطس / أغسطس")
     * into a single clean instance.
     */
    fun deduplicateRepetitions(arabicText: String): String {
        if (arabicText.isBlank()) return ""
        var sanitized = arabicText.trim()
        val phraseRepetitionRegex = Regex("(\\b\\S+(?:\\s+\\S+){0,3})(?:\\s*[/،,-]?\\s*\\1){2,}", RegexOption.IGNORE_CASE)
        while (phraseRepetitionRegex.containsMatchIn(sanitized)) {
            sanitized = sanitized.replace(phraseRepetitionRegex, "$1")
        }
        val singleWordRepeatRegex = Regex("(\\b\\S+)(?:\\s*[/،,]\\s*\\1)+", RegexOption.IGNORE_CASE)
        sanitized = sanitized.replace(singleWordRepeatRegex, "$1")

        // Collapse immediate 1-to-3 word consecutive duplicates without separators on whole-word whitespace boundaries (e.g. "كلمة كلمة" -> "كلمة")
        val consecutiveRepeatRegex = Regex("(^|\\s)(\\S+(?:\\s+\\S+){0,2})\\s+\\2(?=\\s|$)")
        var prevSanitized = ""
        while (prevSanitized != sanitized && consecutiveRepeatRegex.containsMatchIn(sanitized)) {
            prevSanitized = sanitized
            sanitized = sanitized.replace(consecutiveRepeatRegex, "$1$2")
        }

        return sanitized.trim()
    }

    /**
     * Phonetic ASR Error Repair for common Zipformer phonetic slips and controller prompts.
     * Snaps misrecognized terms to canon names and phrases before PatchManager or ML Kit.
     */
    fun normalizeAsrTranscript(englishText: String): String {
        if (englishText.isBlank()) return ""
        var sanitized = englishText.trim()
        sanitized = sanitized.replace(Regex("(?i)\\bmesman\\b"), "man")
        sanitized = sanitized.replace(Regex("(?i)\\bforty servant\\b"), "47")
        sanitized = sanitized.replace(Regex("(?i)\\bbenjamin trous\\b"), "Benjamin Travis")
        sanitized = sanitized.replace(Regex("(?i)\\bdiana burne\\b"), "Diana Burnwood")
        sanitized = sanitized.replace(Regex("(?i)\\bjohnnie has been wired\\b"), "the money has been wired")
        sanitized = sanitized.replace(Regex("(?i)\\btrees and\\b"), "treasonous")
        return normalizeControllerPrompts(sanitized)
    }

    /**
     * Normalizes common controller & button prompts cleanly so symbols and triggers are preserved without garbling.
     * Handles PlayStation (✕, ○, △, □), Xbox (LB, RB, LT, RT), L1-R3, Press X, and Tap Enter.
     */
    fun normalizeControllerPrompts(text: String): String {
        if (text.isBlank()) return ""
        var s = text.trim()
        // Standardize button shapes
        s = s.replace(Regex("(?i)\\bOPEN\\s+[x✕✖⨯]\\b"), "OPEN ✕")
        s = s.replace(Regex("(?i)\\bPUT\\s+DOWN\\s+[o○O]\\b"), "PUT DOWN ○")
        s = s.replace(Regex("(?i)\\bPRESS\\s+[x✕✖⨯]\\b"), "Press X")
        s = s.replace(Regex("(?i)\\bTAP\\s+ENTER\\b"), "Tap Enter")
        // Triggers and Bumpers: L1-R3, LB/RB, LT/RT
        s = s.replace(Regex("(?i)\\b([LR])\\s*([123])\\b"), "$1$2")
        s = s.replace(Regex("(?i)\\b([LR])\\s*([BT])\\b"), "$1$2")
        return s
    }

    /**
     * Returns true if the utterance is short (<= 2 words) and lacks a verb,
     * or ends with a dangling preposition, conjunction, auxiliary verb, or article.
     */
    fun isSyntacticallyIncomplete(englishText: String): Boolean {
        val cleaned = englishText.trim().trimEnd('.', '?', '!', ',', ';', ':')
        if (cleaned.isBlank()) return true
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true

        val lastWord = words.last().lowercase().replace(Regex("[^a-z]"), "")
        if (DANGLING_WORDS.contains(lastWord)) {
            return true
        }

        if (words.size <= 2) {
            val hasVerb = words.any { COMMON_VERBS.contains(it.lowercase().replace(Regex("[^a-z]"), "")) }
            if (!hasVerb) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true if the next clause starts with a coordinating/subordinating conjunction or relative pronoun.
     */
    fun isContinuationClause(nextEnglish: String): Boolean {
        val cleaned = nextEnglish.trim()
        if (cleaned.isBlank()) return false
        val firstWord = cleaned.split(Regex("\\s+")).firstOrNull()?.lowercase()?.replace(Regex("[^a-z]"), "") ?: return false
        return CONTINUATION_WORDS.contains(firstWord)
    }

    /**
     * Unicode-safe spacing sanitizer that repairs glued Arabic words produced by ML Kit neural boundaries.
     * Inserts spaces between non-connecting terminal letters and demonstrative/article prefixes,
     * and handles known frequent ML Kit contraction bugs (e.g. هذهي -> هذه هي, التيجب -> التي يجب).
     */
    fun repairGluedArabicWords(arabicText: String): String {
        if (arabicText.isBlank()) return ""
        var sanitized = arabicText

        // 0. High-frequency specific ML Kit mistranslations & character names
        sanitized = sanitized.replace(Regex("فيو\\s+من\\s+الأيام"), "يوماً ما")
        sanitized = sanitized.replace(Regex("في\\s+يوم\\s+من\\s+الايام"), "يوماً ما")
        sanitized = sanitized.replace(Regex("في\\s+يوم\\s+من\\s+الأيام"), "يوماً ما")
        sanitized = sanitized.replace(Regex("([^\\s\\u0627\\u0623\\u0625\\u0622])\\s*ستهداف\\b"), "$1 استهداف")
        sanitized = sanitized.replace(Regex("([\\u0600-\\u06FF]*\\u064B[\\u0627]?)\\s*ستهداف\\b"), "$1 استهداف")
        sanitized = sanitized.replace(Regex("(?<![\\u0627\\u0623\\u0625\\u0622])ستهداف\\b"), "استهداف")
        sanitized = sanitized.replace(Regex("الأمو\\s+الحسابك"), "الأموال لحسابك")
        sanitized = sanitized.replace(Regex("الامو\\s+الحسابك"), "الأموال لحسابك")
        sanitized = sanitized.replace(Regex("الأموال\\s+الحسابك"), "الأموال لحسابك")
        sanitized = sanitized.replace(Regex("(?i)\\bبن\\s+جيم\\s+في\\s+ترافيس\\b"), "بنيامين ترافيس")
        sanitized = sanitized.replace(Regex("(?i)\\bديانا\\s+بوووود\\b"), "ديانا بيرنوود")
        sanitized = sanitized.replace(Regex("(?i)\\bDiana\\s+Burn\\s*wood\\b"), "ديانا بيرنوود")
        sanitized = sanitized.replace(Regex("(?i)\\bBurnwood\\b"), "بيرنوود")
        sanitized = sanitized.replace("ديانا بيرن وود", "ديانا بيرنوود")
        sanitized = sanitized.replace(Regex("(?i)\\b[ua]rgh\\b"), "آه")
        sanitized = sanitized.replace("ليسهلا", "ليس سهلاً")

        // 1. High-frequency specific ML Kit glued words and contractions
        sanitized = sanitized.replace("\u0645\u0627\u0630 ", "\u0645\u0627\u0630\u0627 ")
        sanitized = sanitized.replace(Regex("\\b\\u0645\\u0627\\u0630\\b"), "\u0645\u0627\u0630\u0627")
        sanitized = sanitized.replace("\u0645\u0627 \u0632 \u0627\u0644\u0648\u0627", "\u0645\u0627 \u0632\u0627\u0644\u0648\u0627")
        sanitized = sanitized.replace(Regex("\\b\\u0644\\u062F\\u064A\\u0647\\u0645([\\u0621-\\u064A]{3,})\\b"), "\u0644\u062F\u064A\u0647\u0645 $1")
        sanitized = sanitized.replace(Regex("\\b\\u0623\\u0628\\u062F\\u0627([\\u0621-\\u064A]{3,})\\b"), "\u0623\u0628\u062F\u0627\u064B $1")
        sanitized = sanitized.replace(Regex("\\b\\u0642\\u0627\\u0644([\\u0621-\\u064A]{2,})\\b"), "\u0642\u0627\u0644 $1")
        sanitized = sanitized.replace(Regex("\\b\\u0645\\u0639\\u062F\\u0648\\u0643\\b"), "\u0645\u0639 \u0639\u062F\u0648\u0643")
        sanitized = sanitized.replace(Regex("\\b\\u0645\\u0627\\u0644\\u0634\\u0631\\u064A\\u0643\\b"), "\u0645\u0627 \u0627\u0644\u0634\u0631\u064A\u0643")
        sanitized = sanitized.replace(Regex("\\b\\u0647\\u0630\\u0647\\u064A\\b"), "\u0647\u0630\u0647 \u0647\u064A")
        sanitized = sanitized.replace(Regex("\\b\\u0627\\u0644\\u062A\\u064A\\u062C\\u0628\\b"), "\u0627\u0644\u062A\u064A \u064A\u062C\u0628")
        sanitized = sanitized.replace(Regex("\\b\\u0627\\u0644\\u0630\\u064A\\u064A\\u062C\\u0628\\b"), "\u0627\u0644\u0630\u064A \u064A\u062C\u0628")
        sanitized = sanitized.replace(Regex("\\b\\u0647\\u0630\\u0627\\u0644\\u0631\\u062C\\u0644\\b"), "\u0647\u0630\u0627 \u0627\u0644\u0631\u062C\u0644")
        sanitized = sanitized.replace(Regex("\\b\\u0647\\u0630\\u0647\\u0627\\u0644\\u0637\\u0631\\u064A\\u0642\\u0629\\b"), "\u0647\u0630\u0647 \u0627\u0644\u0637\u0631\u064A\u0642\u0629")
        sanitized = sanitized.replace(Regex("\\b\\u0645\\u0627\\u0623\\u0642\\u0648\\u0645\\b"), "\u0645\u0627 \u0623\u0642\u0648\u0645")
        sanitized = sanitized.replace(Regex("\\b\\u0647\\u0630\\u0644(\\S+)"), "\u0647\u0630\u0627 \u0627\u0644$1")
        sanitized = sanitized.replace(Regex("\\b\\u062F\\u064A\\u0627\\u0646\\u0627\\u0644(\\S+)"), "\u062F\u064A\u0627\u0646\u0627 \u0627\u0644$1")

        // 2. Insert space between non-connecting terminal letters and definite-article prefix or particles/pronouns
        val gluedPattern = Regex(
            "([\\u0627\\u0649\\u0629\\u0647\\u062F\\u0630\\u0631\\u0632\\u0648])" +
            "(?=\\u0627\\u0644[\\u0600-\\u06FF]|(?:(?:\\u0647\\u064A|\\u0647\\u0648|\\u0647\\u0645|\\u064A\\u062C\\u0628|\\u0623\\u0646|\\u0623\\u0648|\\u0625\\u0644\\u0649|\\u0639\\u0644\\u0649|\\u0645\\u0646|\\u0639\\u0646|\\u0645\\u0639|\\u0647\\u0630\\u0627|\\u0647\\u0630\\u0647|\\u0630\\u0644\\u0643)\\b))"
        )
        sanitized = sanitized.replace(gluedPattern, "$1 ")
        sanitized = sanitized.replace(Regex("\\bهد\\s+في(:|\\b)"), "هدفي$1")

        return sanitized.trim()
    }

    private suspend fun translateViaMlKit(text: String): String = suspendCancellableCoroutine { continuation ->
        val translator = mlKitTranslator
        if (translator == null || !isMlKitModelReady) {
            continuation.resume(text)
            return@suspendCancellableCoroutine
        }

        translator.translate(text)
            .addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit translation failed: ${e.message}", e)
                if (continuation.isActive) {
                    continuation.resume(text) // fallback to original text
                }
            }
    }

    private suspend fun translateViaOpenAi(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("model", config.modelName)
                put("temperature", 0.3)
                put("max_tokens", 100)

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are an expert real-time game translator. Translate the given game text from English into natural, high-quality Arabic suitable for on-screen game UI/dialogue. Return ONLY the Arabic translation without any explanations or quotes.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                }
                put("messages", messagesArray)
            }

            val request = Request.Builder()
                .url(config.apiEndpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "OpenAI translation failed with HTTP code: ${response.code}")
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.optJSONArray("choices") ?: return@withContext null
            if (choices.length() == 0) return@withContext null

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message") ?: return@withContext null
            val arabicContent = message.optString("content", "").trim()

            if (arabicContent.isNotEmpty()) arabicContent else null
        } catch (e: Exception) {
            Log.e(TAG, "Error translating via OpenAI API: ${e.message}", e)
            null
        }
    }

    fun close() {
        try {
            mlKitTranslator?.close()
            mlKitTranslator = null
            Log.d(TAG, "ML Kit Translator closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Translator: ${e.message}", e)
        }
    }
}

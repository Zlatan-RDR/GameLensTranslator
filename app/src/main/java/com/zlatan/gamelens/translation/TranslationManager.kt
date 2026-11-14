package com.zlatan.gamelens.translation

import android.content.Context
import android.util.Log
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
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

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
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""

        // 0. Unified 0ms Game Patch Engine Match (Exact or Fuzzy Levenshtein >= 0.86)
        val patchMatch = com.zlatan.gamelens.patch.PatchManager.findOcrMatch(trimmed)
        if (patchMatch != null) {
            return@withContext patchMatch
        }

        // 1. Direct Game Glossary Exact Match
        val glossaryMatch = glossaryManager.findExactMatch(trimmed)
        if (glossaryMatch != null) {
            return@withContext glossaryMatch
        }

        // 2. Instant Cache (Memory LRU -> Room Persistent DB)
        val cached = cacheManager.get(trimmed)
        if (cached != null) {
            return@withContext cached
        }

        // 3. Pre-translation OCR repair & gaming term normalization
        val preprocessed = preprocessGamingText(trimmed)

        // 4. Translate via configured engine
        val translated = when (config.mode) {
            TranslationMode.CLOUD_OPENAI -> {
                if (config.apiKey.isNotBlank()) {
                    translateViaOpenAi(preprocessed) ?: translateViaMlKit(preprocessed)
                } else {
                    translateViaMlKit(preprocessed)
                }
            }
            TranslationMode.OFFLINE_MLKIT -> {
                translateViaMlKit(preprocessed)
            }
        }

        // 5. Post-process with glossary term replacements
        val glossaryReplaced = glossaryManager.applyTermReplacements(translated)

        // 6. Strip ML Kit neural repetition loops
        val finalResult = deduplicateRepetitions(glossaryReplaced)

        // 7. Save to Cache for subsequent 0ms hits
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

        // 1. Repair broken hyphenation across line breaks: "AVAI- LABLE" -> "AVAILABLE"
        processed = processed.replace(Regex("([a-zA-Z]{2,})-\\s+([a-zA-Z]{2,})"), "$1$2")

        // 2. Repair common monospace split tokens
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

        // Collapse immediate 1-to-3 word consecutive duplicates without separators (e.g. "كلمة كلمة" -> "كلمة")
        val consecutiveRepeatRegex = Regex("(\\S+(?:\\s+\\S+){0,2})\\s+\\1")
        var prevSanitized = ""
        while (prevSanitized != sanitized && consecutiveRepeatRegex.containsMatchIn(sanitized)) {
            prevSanitized = sanitized
            sanitized = sanitized.replace(consecutiveRepeatRegex, "$1")
        }

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

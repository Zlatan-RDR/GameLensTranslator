package com.zlatan.gamelens.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.zlatan.gamelens.db.TranslationCacheManager
import com.zlatan.gamelens.glossary.GlossaryManager
import kotlinx.coroutines.Dispatchers
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

class TranslationManager(
    private val context: Context,
    private val cacheManager: TranslationCacheManager,
    private val glossaryManager: GlossaryManager,
    private var config: TranslationConfig = TranslationConfig()
) {

    companion object {
        private const val TAG = "TranslationManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private var mlKitTranslator: Translator? = null
    private var isMlKitModelReady = false

    init {
        initMlKitTranslator()
    }

    fun updateConfig(newConfig: TranslationConfig) {
        this.config = newConfig
        Log.d(TAG, "TranslationConfig updated: Mode=${config.mode}, Model=${config.modelName}")
    }

    private fun initMlKitTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()

        val translator = Translation.getClient(options)
        mlKitTranslator = translator

        val conditions = DownloadConditions.Builder()
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isMlKitModelReady = true
                Log.d(TAG, "ML Kit English-to-Arabic translation model downloaded and ready.")
            }
            .addOnFailureListener { e ->
                isMlKitModelReady = false
                Log.e(TAG, "Failed to download ML Kit translation model: ${e.message}", e)
            }
    }

    /**
     * Translates the given English text to Arabic through:
     * 1. Direct Glossary Match (0ms)
     * 2. L1/L2 Cache Match (0ms)
     * 3. Selected Engine (ML Kit Offline or OpenAI Cloud REST API with automatic fallback)
     */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""

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

        // 3. Translate via configured engine
        val translated = when (config.mode) {
            TranslationMode.CLOUD_OPENAI -> {
                if (config.apiKey.isNotBlank()) {
                    translateViaOpenAi(trimmed) ?: translateViaMlKit(trimmed)
                } else {
                    translateViaMlKit(trimmed)
                }
            }
            TranslationMode.OFFLINE_MLKIT -> {
                translateViaMlKit(trimmed)
            }
        }

        // 4. Post-process with glossary term replacements
        val finalResult = glossaryManager.applyTermReplacements(translated)

        // 5. Save to Cache for subsequent 0ms hits
        cacheManager.put(trimmed, finalResult)

        finalResult
    }

    private suspend fun translateViaMlKit(text: String): String = suspendCancellableCoroutine { continuation ->
        val translator = mlKitTranslator
        if (translator == null) {
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

    private fun translateViaOpenAi(text: String): String? {
        return try {
            val jsonBody = JSONObject().apply {
                put("model", config.modelName)
                put("temperature", 0.2)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "system")
                                put(
                                    "content",
                                    "You are a professional video game localizer. Translate the provided English in-game text into clear, modern Arabic (RTL). Return ONLY the direct translation with no explanations or punctuation wrapping."
                                )
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", text)
                            }
                        )
                    }
                )
            }

            val request = Request.Builder()
                .url(config.apiEndpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null

            if (!response.isSuccessful) {
                Log.w(TAG, "Cloud API call failed with code ${response.code}: $responseBody")
                return null
            }

            val respJson = JSONObject(responseBody)
            val choices = respJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                val content = message?.optString("content")?.trim()
                if (!content.isNullOrBlank()) {
                    return content
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Cloud API translation: ${e.message}", e)
            null
        }
    }

    fun close() {
        try {
            mlKitTranslator?.close()
            mlKitTranslator = null
            Log.d(TAG, "ML Kit Translator closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing translator: ${e.message}", e)
        }
    }
}

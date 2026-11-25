package com.zlatan.gamelens.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Two-Tier Persistent Disk Cache for real-time game translations.
 * Tier 1: In-memory LRU cache (0ms lookup).
 * Tier 2: Atomic JSON persistent disk storage per game profile across sessions.
 * Key Isolation: engineMode + glossaryHash + normalizedSourceText.
 */
class PersistentTranslationCache(
    private val cacheDir: File,
    private val maxEntries: Int = 2000
) {
    constructor(context: Context, maxEntries: Int = 2000) : this(
        File(context.filesDir, "translation_cache"),
        maxEntries
    )

    companion object {
        private const val TAG = "PersistentCache"
        private const val DEBOUNCE_MS = 5000L

        fun computeGlossaryHash(glossaryText: String?): String {
            if (glossaryText.isNullOrBlank()) return "noglossary"
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(glossaryText.trim().toByteArray(Charsets.UTF_8))
                digest.take(8).joinToString("") { "%02x".format(it) }
            } catch (_: Throwable) {
                glossaryText.trim().hashCode().toString()
            }
        }

        fun buildKey(engineMode: String, glossaryHash: String, text: String): String {
            return "$engineMode:$glossaryHash:${text.trim()}"
        }

        fun sanitizeProfileId(profileId: String): String {
            return profileId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
        }
    }

    private val lock = Any()
    private val memCache = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxEntries
        }
    }

    @Volatile
    private var currentProfileId: String = "default"
    @Volatile
    private var isDirty = false

    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    fun get(engineMode: String, glossaryHash: String, text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val key = buildKey(engineMode, glossaryHash, trimmed)
        synchronized(lock) {
            val direct = memCache[key]
            if (direct != null) return direct
        }
        return findFuzzy(engineMode, glossaryHash, trimmed, 0.90f)
    }

    fun findFuzzy(engineMode: String, glossaryHash: String, text: String, threshold: Float = 0.90f): String? {
        val keyPrefix = "$engineMode:$glossaryHash:"
        synchronized(lock) {
            for ((cachedKey, cachedTranslation) in memCache) {
                if (cachedKey.startsWith(keyPrefix)) {
                    val cachedEn = cachedKey.substring(keyPrefix.length)
                    if (com.zlatan.gamelens.ocr.OcrTextCleaner.isSimilarForCache(text, cachedEn, threshold)) {
                        return cachedTranslation
                    }
                }
            }
        }
        return null
    }

    fun put(engineMode: String, glossaryHash: String, text: String, translation: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || translation.isBlank()) return
        val key = buildKey(engineMode, glossaryHash, trimmed)
        synchronized(lock) {
            memCache[key] = translation.trim()
            isDirty = true
        }
        scheduleDebouncedFlush()
    }

    fun switchProfile(profileId: String, customScope: CoroutineScope? = null) {
        val targetProfile = sanitizeProfileId(profileId)
        if (currentProfileId == targetProfile) return

        flushDirty()
        val s = customScope ?: scope
        s.launch {
            loadProfile(targetProfile)
        }
    }

    suspend fun loadProfile(profileId: String) = withContext(Dispatchers.IO) {
        val sanitized = sanitizeProfileId(profileId)
        val file = File(cacheDir, "$sanitized.json")
        val loaded = mutableMapOf<String, String>()

        if (file.exists()) {
            try {
                val jsonStr = file.readText(Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                val arr = json.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val k = obj.optString("k")
                    val v = obj.optString("v")
                    if (k.isNotBlank() && v.isNotBlank()) {
                        loaded[k] = v
                    }
                }
                Log.d(TAG, "Loaded ${loaded.size} cached translations for profile '$sanitized'")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to read cache for profile '$sanitized': ${t.message}")
            }
        }

        synchronized(lock) {
            currentProfileId = sanitized
            memCache.clear()
            memCache.putAll(loaded)
            isDirty = false
        }
    }

    private fun scheduleDebouncedFlush() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            flushDirty()
        }
    }

    fun flushDirty() {
        synchronized(lock) {
            if (!isDirty) return
            isDirty = false
        }

        val snapshot: List<Pair<String, String>>
        val profile: String
        synchronized(lock) {
            snapshot = memCache.entries.map { it.key to it.value }
            profile = currentProfileId
        }

        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val targetFile = File(cacheDir, "$profile.json")
            val tmpFile = File(cacheDir, "$profile.json.tmp")

            val json = JSONObject()
            val arr = JSONArray()
            for ((k, v) in snapshot) {
                val entryObj = JSONObject().apply {
                    put("k", k)
                    put("v", v)
                }
                arr.put(entryObj)
            }
            json.put("entries", arr)

            tmpFile.writeText(json.toString(), Charsets.UTF_8)
            if (tmpFile.renameTo(targetFile)) {
                Log.d(TAG, "Persisted ${snapshot.size} translations for profile '$profile'")
            } else {
                // If rename fails (e.g. cross-filesystem), copy and delete tmp
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to flush cache to disk: ${t.message}")
        }
    }

    fun clear() {
        synchronized(lock) {
            memCache.clear()
            isDirty = false
        }
        val targetFile = File(cacheDir, "$currentProfileId.json")
        try {
            if (targetFile.exists()) targetFile.delete()
        } catch (_: Throwable) {}
    }

    fun size(): Int = synchronized(lock) { memCache.size }
}

package com.zlatan.gamelens.db

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class TranslationCacheManager(context: Context) {

    companion object {
        private const val TAG = "TranslationCache"
    }

    private var dao: TranslationDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // L1 Cache: In-memory LRU Cache (Fastest 0ms lookup)
    private val memoryCache = LruCache<String, String>(500)
    // Safe persistent in-memory cache if Room DB cannot be instantiated
    private val inMemoryFallbackMap = ConcurrentHashMap<String, String>()

    init {
        try {
            val db = TranslationDatabase.getInstance(context)
            dao = db.translationDao()
            Log.d(TAG, "Room Database initialized successfully.")
        } catch (e: Throwable) {
            Log.w(TAG, "Room Database unavailable (${e.message}). Falling back to in-memory cache safely.")
            dao = null
        }
    }

    /**
     * Looks up translated text in L1 in-memory cache, then fallback map, then L2 Room database.
     */
    suspend fun get(originalText: String): String? {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty()) return ""

        // Check L1 cache
        synchronized(memoryCache) {
            val cached = memoryCache.get(trimmed)
            if (cached != null) return cached
        }

        // Check in-memory fallback map if Room is unavailable
        val fallback = inMemoryFallbackMap[trimmed]
        if (fallback != null) {
            synchronized(memoryCache) {
                memoryCache.put(trimmed, fallback)
            }
            return fallback
        }

        // Check L2 Room persistent cache if available
        val currentDao = dao ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val entity = currentDao.getTranslation(trimmed)
                if (entity != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(trimmed, entity.translatedText)
                    }
                    entity.translatedText
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Stores the translated string in L1 memory cache and persists it into Room database asynchronously.
     */
    fun put(originalText: String, translatedText: String) {
        val trimmedOriginal = originalText.trim()
        val trimmedTranslated = translatedText.trim()
        if (trimmedOriginal.isEmpty() || trimmedTranslated.isEmpty()) return

        synchronized(memoryCache) {
            memoryCache.put(trimmedOriginal, trimmedTranslated)
        }
        inMemoryFallbackMap[trimmedOriginal] = trimmedTranslated

        val currentDao = dao ?: return
        scope.launch {
            try {
                currentDao.insertTranslation(
                    TranslationEntity(
                        originalText = trimmedOriginal,
                        translatedText = trimmedTranslated
                    )
                )
            } catch (_: Throwable) {}
        }
    }

    fun clear() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
        inMemoryFallbackMap.clear()
        val currentDao = dao ?: return
        scope.launch {
            try {
                currentDao.clearAll()
            } catch (_: Throwable) {}
        }
    }
}

package com.zlatan.gamelens.db

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationCacheManager(context: Context) {

    private val db = TranslationDatabase.getInstance(context)
    private val dao = db.translationDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    // L1 Cache: In-memory LRU Cache (Fastest 0ms lookup)
    private val memoryCache = LruCache<String, String>(500)

    /**
     * Looks up translated text in L1 in-memory cache, then falls back to L2 Room database.
     */
    suspend fun get(originalText: String): String? {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty()) return ""

        // Check L1 cache
        synchronized(memoryCache) {
            val cached = memoryCache.get(trimmed)
            if (cached != null) return cached
        }

        // Check L2 Room persistent cache
        return withContext(Dispatchers.IO) {
            try {
                val entity = dao.getTranslation(trimmed)
                if (entity != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(trimmed, entity.translatedText)
                    }
                    entity.translatedText
                } else {
                    null
                }
            } catch (_: Exception) {
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

        scope.launch {
            try {
                dao.insertTranslation(
                    TranslationEntity(
                        originalText = trimmedOriginal,
                        translatedText = trimmedTranslated
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun clear() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
        scope.launch {
            try {
                dao.clearAll()
            } catch (_: Exception) {}
        }
    }
}

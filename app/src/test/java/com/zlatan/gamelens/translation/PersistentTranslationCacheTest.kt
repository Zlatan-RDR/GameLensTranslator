package com.zlatan.gamelens.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentTranslationCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testKeyIsolationByEngineAndGlossary() {
        val cache = PersistentTranslationCache(tempFolder.root, maxEntries = 100)
        val text = "Good morning, agent."
        val translation = "صباح الخير، أيها العميل."

        cache.put("FREE", "hashA", text, translation)

        // Exact match
        assertEquals(translation, cache.get("FREE", "hashA", text))

        // Different engine -> null (automatic invalidation)
        assertNull(cache.get("OFFLINE", "hashA", text))
        assertNull(cache.get("AI:gpt-4o", "hashA", text))

        // Different glossary hash -> null (automatic invalidation)
        assertNull(cache.get("FREE", "hashB", text))
    }

    @Test
    fun testRoundTripDiskPersistence() = runBlocking {
        val rootDir = tempFolder.root
        val cache1 = PersistentTranslationCache(rootDir, maxEntries = 100)
        cache1.put("FREE", "hash1", "Hello", "مرحباً")
        cache1.put("FREE", "hash1", "Goodbye", "وداعاً")
        cache1.flushDirty()

        // Create fresh second cache instance
        val cache2 = PersistentTranslationCache(rootDir, maxEntries = 100)
        cache2.loadProfile("default")

        assertEquals("مرحباً", cache2.get("FREE", "hash1", "Hello"))
        assertEquals("وداعاً", cache2.get("FREE", "hash1", "Goodbye"))
    }

    @Test
    fun testLruEviction() {
        val cache = PersistentTranslationCache(tempFolder.root, maxEntries = 3)
        cache.put("FREE", "h", "one", "واحد")
        cache.put("FREE", "h", "two", "اثنان")
        cache.put("FREE", "h", "three", "ثلاثة")
        cache.put("FREE", "h", "four", "أربعة") // should evict "one"

        assertEquals(3, cache.size())
        assertNull(cache.get("FREE", "h", "one"))
        assertNotNull(cache.get("FREE", "h", "two"))
        assertNotNull(cache.get("FREE", "h", "three"))
        assertNotNull(cache.get("FREE", "h", "four"))
    }

    @Test
    fun testProfileSwitchIsolation() = runBlocking {
        val rootDir = tempFolder.root
        val cache = PersistentTranslationCache(rootDir, maxEntries = 100)

        // Profile A
        cache.switchProfile("profileA")
        cache.loadProfile("profileA")
        cache.put("FREE", "h", "Target", "الهدف")
        cache.flushDirty()

        // Switch to Profile B
        cache.switchProfile("profileB")
        cache.loadProfile("profileB")
        assertNull(cache.get("FREE", "h", "Target"))

        cache.put("FREE", "h", "Target", "نيشان")
        cache.flushDirty()

        // Reload Profile A
        cache.loadProfile("profileA")
        assertEquals("الهدف", cache.get("FREE", "h", "Target"))

        // Reload Profile B
        cache.loadProfile("profileB")
        assertEquals("نيشان", cache.get("FREE", "h", "Target"))
    }
}

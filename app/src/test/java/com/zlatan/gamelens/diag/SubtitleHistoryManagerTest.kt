package com.zlatan.gamelens.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleHistoryManagerTest {

    @Test
    fun testFormatSrtTime() {
        assertEquals("00:00:00,000", SubtitleHistoryManager.formatSrtTime(0L))
        assertEquals("00:00:01,500", SubtitleHistoryManager.formatSrtTime(1500L))
        assertEquals("00:01:05,025", SubtitleHistoryManager.formatSrtTime(65025L))
        assertEquals("01:01:01,005", SubtitleHistoryManager.formatSrtTime(3661005L))
    }

    @Test
    fun testGenerateSrtRelativeAndClamped() {
        val baseTime = 100_000L
        val entries = listOf(
            SubtitleHistoryManager.HistoryEntry(
                timestamp = "12:00:00",
                source = "Hello Arthur",
                arabic = "مرحباً آرثر",
                recordTimeMs = baseTime
            ),
            SubtitleHistoryManager.HistoryEntry(
                timestamp = "12:00:01",
                source = "We need more money",
                arabic = "نحتاج إلى المزيد من المال",
                recordTimeMs = baseTime + 1200L // 1.2s after first
            ),
            SubtitleHistoryManager.HistoryEntry(
                timestamp = "12:00:10",
                source = "One last job",
                arabic = "مهمة أخيرة فقط",
                recordTimeMs = baseTime + 10_000L // 10s after first
            )
        )

        val srt = SubtitleHistoryManager.generateSrt(entries)
        val blocks = srt.trim().split("\n\n")
        assertEquals(3, blocks.size)

        // Block 1: starts at 00:00:00,000, next starts at 1200ms -> end is min(3000, 1200 - 50) = 1150ms
        assertTrue(blocks[0].startsWith("1\n00:00:00,000 --> 00:00:01,150\nمرحباً آرثر"))

        // Block 2: starts at 1200ms (00:00:01,200), next starts at 10000ms -> end is 1200 + 3000 = 4200ms
        assertTrue(blocks[1].startsWith("2\n00:00:01,200 --> 00:00:04,200\nنحتاج إلى المزيد من المال"))

        // Block 3: starts at 10000ms (00:00:10,000), last block -> end is 10000 + 3000 = 13000ms
        assertTrue(blocks[2].startsWith("3\n00:00:10,000 --> 00:00:13,000\nمهمة أخيرة فقط"))
    }

    @Test
    fun testGenerateSrtMinDurationClamp() {
        val baseTime = 50_000L
        val entries = listOf(
            SubtitleHistoryManager.HistoryEntry(
                timestamp = "10:00:00",
                source = "Quick",
                arabic = "سريع",
                recordTimeMs = baseTime
            ),
            SubtitleHistoryManager.HistoryEntry(
                timestamp = "10:00:00",
                source = "Next",
                arabic = "التالي",
                recordTimeMs = baseTime + 200L // Only 200ms later -> min clamp should guarantee at least 500ms
            )
        )

        val srt = SubtitleHistoryManager.generateSrt(entries)
        val blocks = srt.trim().split("\n\n")
        assertEquals(2, blocks.size)

        // Block 1: start 0ms, nextStart 200ms. clamped is max(start + 500, nextStart - 50) = 500ms
        assertTrue(blocks[0].startsWith("1\n00:00:00,000 --> 00:00:00,500\nسريع"))
    }
}

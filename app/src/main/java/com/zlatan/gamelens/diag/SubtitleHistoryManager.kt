package com.zlatan.gamelens.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Dedicated Subtitle History System inspired by Sefflex/GameTranslator SubtitleHistory.
 * Records translated in-game dialogue lines with timestamps and source/translation pairs.
 * Provides one-tap clipboard copy, duplicate suppression, and reactive StateFlow observation.
 */
object SubtitleHistoryManager {
    private const val MAX_ENTRIES = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    data class HistoryEntry(
        val timestamp: String,
        val source: String,
        val arabic: String,
        var recordTimeMs: Long = System.currentTimeMillis()
    )

    private val entries = ConcurrentLinkedDeque<HistoryEntry>()

    private val _historyFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val historyFlow: StateFlow<List<HistoryEntry>> = _historyFlow.asStateFlow()

    @Synchronized
    fun recordOrUpgradeStreaming(source: String, arabic: String) {
        val cleanAr = arabic.trim()
        val cleanSrc = source.trim()
        if (cleanAr.isBlank()) return

        val now = System.currentTimeMillis()
        val last = entries.peekLast()
        if (last != null && (now - last.recordTimeMs <= 6000L)) {
            val verdict = com.zlatan.gamelens.ocr.OcrEngine.classifySupersetExpansion(last.source, cleanSrc)
            if (verdict == com.zlatan.gamelens.ocr.OcrEngine.SupersetVerdict.CLEAN_PROGRESSIVE_EXPANSION) {
                entries.pollLast()
                val updated = HistoryEntry(last.timestamp, cleanSrc, cleanAr, now)
                entries.add(updated)
                _historyFlow.value = entries.toList()
                return
            }
        }

        addEntry(cleanSrc, cleanAr)
    }

    @Synchronized
    fun addEntry(source: String, arabic: String) {
        val cleanAr = arabic.trim()
        if (cleanAr.isBlank()) return

        // Deduplicate consecutive identical lines
        val last = entries.peekLast()
        if (last != null && last.arabic == cleanAr) {
            return
        }

        val time = timeFormat.format(Date())
        val entry = HistoryEntry(time, source.trim(), cleanAr)
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }
        _historyFlow.value = entries.toList()
    }

    fun getFormattedHistory(): String {
        val list = entries.toList()
        if (list.isEmpty()) return "لا توجد حوارات مترجمة مسجلة بعد"
        return list.joinToString("\n\n") { entry ->
            if (entry.source.isNotBlank()) {
                "[${entry.timestamp}]\nEN: ${entry.source}\nAR: ${entry.arabic}"
            } else {
                "[${entry.timestamp}] ${entry.arabic}"
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _historyFlow.value = emptyList()
    }

    fun flush(context: android.content.Context) {
        com.zlatan.gamelens.patch.PatchManager.flushPendingSession(context)
    }
}

package com.zlatan.gamelens.diag

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    enum class ExportFormat { JSON, SRT }

    /**
     * Exports the current translated dialogue session to Downloads/GameLens/Exports/
     * via MediaStore (Scoped-Storage compliant on Android 10-16, zero runtime permissions needed).
     */
    fun exportSessionToDownloads(context: Context, format: ExportFormat): String? {
        val list = entries.toList()
        if (list.isEmpty()) return null

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = when (format) {
            ExportFormat.JSON -> "gamelens_patch_$timeStamp.json"
            ExportFormat.SRT -> "gamelens_subs_$timeStamp.srt"
        }

        val contentBytes = when (format) {
            ExportFormat.JSON -> {
                val jsonObject = JSONObject()
                for (entry in list) {
                    if (entry.source.isNotBlank() && entry.arabic.isNotBlank()) {
                        jsonObject.put(entry.source.trim(), entry.arabic.trim())
                    }
                }
                jsonObject.toString(2).toByteArray(Charsets.UTF_8)
            }
            ExportFormat.SRT -> {
                generateSrt(list).toByteArray(Charsets.UTF_8)
            }
        }

        return saveToMediaStore(context, fileName, contentBytes)
    }

    internal fun generateSrt(list: List<HistoryEntry>): String {
        val sb = StringBuilder()
        var index = 1
        val firstMs = if (list.isNotEmpty()) list.first().recordTimeMs else 0L
        for (i in list.indices) {
            val entry = list[i]
            val startMs = maxOf(0L, entry.recordTimeMs - firstMs)
            val nextStartMs = if (i + 1 < list.size) maxOf(startMs, list[i + 1].recordTimeMs - firstMs) else Long.MAX_VALUE
            val targetEndMs = startMs + 3000L
            val clampedEndMs = if (nextStartMs != Long.MAX_VALUE) minOf(targetEndMs, nextStartMs - 50L) else targetEndMs
            val endMs = maxOf(startMs + 500L, clampedEndMs)

            val startTimeStr = formatSrtTime(startMs)
            val endTimeStr = formatSrtTime(endMs)
            sb.append(index).append("\n")
            sb.append(startTimeStr).append(" --> ").append(endTimeStr).append("\n")
            sb.append(entry.arabic.trim()).append("\n\n")
            index++
        }
        return sb.toString()
    }

    internal fun formatSrtTime(timeMs: Long): String {
        val totalMs = maxOf(0L, timeMs)
        val hours = totalMs / (3600 * 1000)
        val minutes = (totalMs / (60 * 1000)) % 60
        val seconds = (totalMs / 1000) % 60
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun saveToMediaStore(context: Context, fileName: String, bytes: ByteArray): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (fileName.endsWith(".json")) "application/json" else "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/GameLens/Exports")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null
                resolver.openOutputStream(uri, "wt")?.use { os ->
                    os.write(bytes)
                }
                DiagnosticLogger.log("Exported session to MediaStore: $fileName")
                fileName
            } else {
                val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (pubDownloads != null) {
                    val exportDir = File(pubDownloads, "GameLens/Exports")
                    exportDir.mkdirs()
                    val targetFile = File(exportDir, fileName)
                    FileOutputStream(targetFile).use { fos ->
                        fos.write(bytes)
                    }
                    DiagnosticLogger.log("Exported session to legacy Downloads: $fileName")
                    fileName
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.e("SubtitleHistoryManager", "Failed to export: ${t.message}", t)
            DiagnosticLogger.log("Export failed: ${t.message}")
            null
        }
    }
}

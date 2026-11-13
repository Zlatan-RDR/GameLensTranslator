package com.zlatan.gamelens.patch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.zlatan.gamelens.diag.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified 0ms Game Patch Engine & Fuzzy ASR Error Corrector.
 * Parses .json, .srt, and .txt translation scripts into an indexed lookup table.
 * Corrects OCR and Voice ASR text with exact and fuzzy Levenshtein snapping,
 * and automatically exports gameplay translations into reusable session JSON patches.
 */
object PatchManager {

    private const val TAG = "PatchManager"
    private const val OCR_FUZZY_THRESHOLD = 0.86f
    private const val ASR_FUZZY_THRESHOLD = 0.80f

    private val patchMap = ConcurrentHashMap<String, String>() // normalized lowercase English -> Arabic
    private val rawEntries = mutableListOf<Pair<String, String>>() // original pairs for fuzzy matching

    private val _activePatchNameFlow = MutableStateFlow<String?>(null)
    val activePatchNameFlow: StateFlow<String?> = _activePatchNameFlow.asStateFlow()

    private val _patchCountFlow = MutableStateFlow(0)
    val patchCountFlow: StateFlow<Int> = _patchCountFlow.asStateFlow()

    // Session Logger: logs unique (English -> Arabic) pairs
    private val sessionPairs = ConcurrentHashMap<String, String>()
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionDateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun normalizeText(text: String): String {
        return text.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Loads a game patch from an SAF content Uri (.json, .srt, .txt).
     */
    suspend fun loadPatchFromUri(context: Context, uri: Uri, fileName: String): Int = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext 0
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            stream.close()

            val count = parsePatchContent(content, fileName)
            if (count > 0) {
                _activePatchNameFlow.value = fileName
                _patchCountFlow.value = count
                DiagnosticLogger.log("PatchManager: Loaded '$fileName' ($count entries)")
                Log.i(TAG, "Successfully loaded $count translation patch entries from $fileName")
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading patch from $fileName: ${e.message}", e)
            DiagnosticLogger.log("PatchManager Error: ${e.message}")
            0
        }
    }

    private fun parsePatchContent(content: String, fileName: String): Int {
        patchMap.clear()
        synchronized(rawEntries) {
            rawEntries.clear()
        }

        val lowerName = fileName.lowercase(Locale.US)
        when {
            lowerName.endsWith(".json") -> parseJson(content)
            lowerName.endsWith(".srt") -> parseSrt(content)
            lowerName.endsWith(".csv") -> parseCsv(content)
            else -> parseDelimitedText(content)
        }

        return patchMap.size
    }

    private fun parseCsv(content: String) {
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                val delimiter = if (trimmed.contains(";")) ";" else ","
                val parts = trimmed.split(delimiter, limit = 2)
                if (parts.size == 2) {
                    val en = parts[0].trim().removeSurrounding("\"").removeSurrounding("'")
                    val ar = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                    if (en.isNotBlank() && ar.isNotBlank()) {
                        addEntry(en, ar)
                    }
                }
            }
        }
    }

    private fun parseJson(content: String) {
        try {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                val jsonObject = JSONObject(trimmed)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObject.optString(key, "")
                    if (key.isNotBlank() && value.isNotBlank()) {
                        addEntry(key, value)
                    }
                }
            } else if (trimmed.startsWith("[")) {
                val jsonArray = JSONArray(trimmed)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val en = item.optString("en").takeIf { it.isNotBlank() }
                        ?: item.optString("original").takeIf { it.isNotBlank() }
                        ?: item.optString("text")
                    val ar = item.optString("ar").takeIf { it.isNotBlank() }
                        ?: item.optString("translated").takeIf { it.isNotBlank() }
                        ?: item.optString("arabic")
                    if (!en.isNullOrBlank() && !ar.isNullOrBlank()) {
                        addEntry(en, ar)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse fallback to text: ${e.message}")
            parseDelimitedText(content)
        }
    }

    private fun parseSrt(content: String) {
        // SRT format: blocks separated by blank lines
        // 1\n00:00:01,000 --> 00:00:04,000\nEnglish line\nArabic line\n\n
        val blocks = content.split(Regex("(\\r?\\n){2,}"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size >= 3) {
                // Skip number and timestamp
                val textLines = lines.filterNot { it.matches(Regex("^\\d+$")) || it.contains("-->") }
                if (textLines.size >= 2) {
                    val en = textLines[0]
                    val ar = textLines[1]
                    addEntry(en, ar)
                }
            }
        }
    }

    private fun parseDelimitedText(content: String) {
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                val delimiter = when {
                    trimmed.contains("|") -> "|"
                    trimmed.contains("\t") -> "\t"
                    trimmed.contains("=") -> "="
                    trimmed.contains("::") -> "::"
                    trimmed.contains("->") -> "->"
                    trimmed.contains(",") -> ","
                    else -> null
                }
                if (delimiter != null) {
                    val parts = trimmed.split(delimiter, limit = 2)
                    if (parts.size == 2) {
                        val en = parts[0].trim()
                        val ar = parts[1].trim()
                        if (en.isNotBlank() && ar.isNotBlank()) {
                            addEntry(en, ar)
                        }
                    }
                }
            }
        }
    }

    private fun addEntry(english: String, arabic: String) {
        val normalized = normalizeText(english)
        if (normalized.isNotBlank()) {
            patchMap[normalized] = arabic
            synchronized(rawEntries) {
                rawEntries.add(Pair(english, arabic))
            }
        }
    }

    /**
     * Instant 0ms exact or fuzzy OCR match (>= 0.86 similarity).
     * Returns matching human-grade Arabic translation if found.
     */
    fun findOcrMatch(rawOcrText: String): String? {
        if (patchMap.isEmpty()) return null
        val normalized = normalizeText(rawOcrText)
        if (normalized.isBlank()) return null

        // 1. Instant 0ms Exact Hash Lookup
        val exactMatch = patchMap[normalized]
        if (exactMatch != null) {
            return exactMatch
        }

        // 2. Fuzzy Levenshtein Match if length >= 4
        if (normalized.length >= 4) {
            var bestArabic: String? = null
            var bestScore = 0.0f

            synchronized(rawEntries) {
                for ((en, ar) in rawEntries) {
                    val score = calculateSimilarity(normalized, normalizeText(en))
                    if (score >= OCR_FUZZY_THRESHOLD && score > bestScore) {
                        bestScore = score
                        bestArabic = ar
                        if (score >= 0.96f) break // Early break on near-identical match
                    }
                }
            }
            if (bestArabic != null) {
                DiagnosticLogger.log("Patch OCR Snap ($bestScore): \"$rawOcrText\" -> Patch")
                return bestArabic
            }
        }
        return null
    }

    /**
     * Fuzzy Voice ASR Auto-Correction (>= 0.80 similarity).
     * Snaps phonetic ASR typos (e.g. "caver" -> "cover") to patch script translation.
     * Returns Pair(correctedEnglish, arabicTranslation) or null.
     */
    fun findAsrMatch(rawAsrText: String): Pair<String, String>? {
        if (patchMap.isEmpty()) return null
        val normalized = normalizeText(rawAsrText)
        if (normalized.isBlank()) return null

        // 1. Instant 0ms Exact Hash Lookup
        val exactMatch = patchMap[normalized]
        if (exactMatch != null) {
            return Pair(rawAsrText, exactMatch)
        }

        // 2. Fuzzy Phonetic & Levenshtein Snapping
        if (normalized.length >= 4) {
            var bestPair: Pair<String, String>? = null
            var bestScore = 0.0f

            synchronized(rawEntries) {
                for ((en, ar) in rawEntries) {
                    val score = calculateSimilarity(normalized, normalizeText(en))
                    if (score >= ASR_FUZZY_THRESHOLD && score > bestScore) {
                        bestScore = score
                        bestPair = Pair(en, ar)
                        if (score >= 0.95f) break
                    }
                }
            }
            if (bestPair != null) {
                DiagnosticLogger.log("Patch ASR Snap ($bestScore): \"$rawAsrText\" -> \"${bestPair.first}\"")
                return bestPair
            }
        }
        return null
    }

    /**
     * Calculates Levenshtein string similarity score in [0.0, 1.0].
     * Optimized with single-row dynamic programming for zero memory thrashing.
     */
    fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0f
        if (Math.abs(len1 - len2) > Math.max(len1, len2) * 0.35f) return 0.0f // Early exit

        var prev = IntArray(len2 + 1) { it }
        var curr = IntArray(len2 + 1)

        for (i in 1..len1) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..len2) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        val dist = prev[len2]
        val maxLen = maxOf(len1, len2)
        return 1.0f - (dist.toFloat() / maxLen.toFloat())
    }

    /**
     * Automatically logs captured gameplay translations to export as a reusable patch.
     */
    fun logSessionEntry(context: Context, english: String, arabic: String) {
        val enTrimmed = english.trim()
        val arTrimmed = arabic.trim()
        if (enTrimmed.isBlank() || arTrimmed.isBlank()) return

        sessionPairs[enTrimmed] = arTrimmed

        // Debounced flush to storage
        sessionScope.launch {
            try {
                flushSessionToDownloads(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Session export error: ${t.message}")
            }
        }
    }

    private fun flushSessionToDownloads(context: Context) {
        if (sessionPairs.isEmpty()) return

        val fileName = "session_${sessionDateStr}.json"

        val json = JSONObject()
        for ((k, v) in sessionPairs) {
            json.put(k, v)
        }
        val jsonBytes = json.toString(2).toByteArray(Charsets.UTF_8)

        // 1. Always save into internal app storage sessions directory
        try {
            val sessionsDir = File(context.filesDir, "GameLens_Sessions")
            sessionsDir.mkdirs()
            val sessionFile = File(sessionsDir, fileName)
            FileOutputStream(sessionFile).use { fos ->
                fos.write(jsonBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing internal session file: ${e.message}")
        }

        // 2. Export to public Downloads/GameLens_Sessions via MediaStore on Android 10+ (API 29+) / Android 16
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/GameLens_Sessions")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri, "wt")?.use { os ->
                        os.write(jsonBytes)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                    DiagnosticLogger.log("PatchManager: Saved session via MediaStore ($fileName)")
                }
            } else {
                val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (pubDownloads != null && pubDownloads.exists()) {
                    val pubDir = File(pubDownloads, "GameLens_Sessions")
                    pubDir.mkdirs()
                    val pubFile = File(pubDir, fileName)
                    FileOutputStream(pubFile).use { fos ->
                        fos.write(jsonBytes)
                    }
                    DiagnosticLogger.log("PatchManager: Saved legacy session ($fileName)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore session export failed: ${t.message}")
            DiagnosticLogger.log("Session export warning: ${t.message}")
        }
    }

    fun clearPatch() {
        patchMap.clear()
        synchronized(rawEntries) {
            rawEntries.clear()
        }
        _activePatchNameFlow.value = null
        _patchCountFlow.value = 0
    }
}

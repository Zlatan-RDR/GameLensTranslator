package com.zlatan.gamelens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zlatan.gamelens.patch.PatchManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrEngine {

    companion object {
        private const val TAG = "OcrEngine"
        private const val MIN_CONFIDENCE_THRESHOLD = 0.78f
        private val STANDALONE_NUMBER_OR_SYMBOL_REGEX = Regex("^[0-9\\s.,/:\\-+%$#@!&*()_=\\[\\]{}|<>?~^]+$")
        private val SHORT_VALID_WORDS = setOf(
            "a", "i", "to", "in", "on", "at", "by", "of", "or", "is", "it",
            "no", "go", "up", "we", "he", "me", "my", "do", "so", "ok", "am", "an", "as"
        )
        private val CREDIT_KEYWORDS = setOf(
            "PRODUCED BY", "DIRECTED BY", "DESIGNER", "PROGRAMMER", "MUSIC BY", "WRITTEN BY"
        )
    }

    fun isCreditOrTitleLine(text: String): Boolean {
        val upper = text.uppercase(java.util.Locale.US)
        for (kw in CREDIT_KEYWORDS) {
            if (upper.contains(kw)) return true
        }
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 4) {
            val upperCount = letters.count { it.isUpperCase() }
            val upperRatio = upperCount.toFloat() / letters.length.toFloat()
            if (upperRatio >= 0.75f && PatchManager.lookup(text.trim()) == null) {
                return true
            }
        }
        return false
    }

    private fun isMostlyUpper(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val upperCount = letters.count { it.isUpperCase() }
        return (upperCount.toFloat() / letters.length.toFloat()) >= 0.60f
    }

    /**
     * Fast (<1ms) heuristic scoring of OCR text cleanliness.
     * Rewards valid English words and penalizes detached letters, mid-word caps, underscores, and diacritics.
     */
    fun scoreOcrQuality(text: String): Float {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0.0f
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return 0.0f

        var score = 1.0f

        // Penalize underscores (e.g. cut off_)
        if (trimmed.contains("_")) score -= 0.25f

        // Penalize European diacritics
        val diacriticCount = trimmed.count { it in OcrTextCleaner.EUROPEAN_DIACRITICS }
        score -= (diacriticCount * 0.20f)

        // Evaluate tokens
        var validEnglishTokens = 0
        var detachedSingleLetters = 0
        var erraticCapitals = 0

        for (token in tokens) {
            val letters = token.filter { it.isLetter() }
            if (letters.isEmpty()) continue

            if (letters.length == 1) {
                if (!letters.equals("a", ignoreCase = true) && !letters.equals("i", ignoreCase = true)) {
                    detachedSingleLetters++
                }
            }

            // Check erratic mid-word uppercase (e.g. "DESsiGNER", "accOunt", "deaLt")
            if (letters.length >= 3) {
                val hasInnerUpper = letters.drop(1).dropLast(1).any { it.isUpperCase() } && letters.any { it.isLowerCase() }
                if (hasInnerUpper) {
                    erraticCapitals++
                }
            }

            if (letters.length >= 3) {
                val hasVowel = letters.any { it in "aeiouyAEIOUY" }
                val hasConsonant = letters.any { it in "bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ" }
                if (hasVowel && hasConsonant) {
                    validEnglishTokens++
                }
            } else if (letters.isNotEmpty() && SHORT_VALID_WORDS.contains(letters.lowercase(java.util.Locale.US))) {
                validEnglishTokens++
            }
        }

        val tokenCount = tokens.size.toFloat()
        score -= (detachedSingleLetters.toFloat() / tokenCount) * 0.40f
        score -= (erraticCapitals.toFloat() / tokenCount) * 0.35f

        val englishRatio = validEnglishTokens.toFloat() / tokenCount
        score *= englishRatio.coerceIn(0.1f, 1.0f)

        return score.coerceIn(0.0f, 1.0f)
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Applies high-contrast ColorMatrix boost (contrast = 2.1f, brightness = -35f)
     * and 1.5x bilinear upscale whenever crop height < 260px so thin white/yellow subtitles
     * are isolated cleanly from complex bright backgrounds (< 8ms on Snapdragon 8s Gen 4).
     */
    fun preprocessBitmapForOcr(src: Bitmap): Pair<Bitmap, Float> {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) {
            return Pair(src, 1.0f)
        }
        val needsUpscale = src.height < 260
        val scale = if (needsUpscale) 1.5f else 1.0f
        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val cm = ColorMatrix(floatArrayOf(
            2.1f, 0f,   0f,   0f, -35f,
            0f,   2.1f, 0f,   0f, -35f,
            0f,   0f,   2.1f, 0f, -35f,
            0f,   0f,   0f,   1f,   0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        val srcRect = Rect(0, 0, src.width, src.height)
        val dstRect = Rect(0, 0, targetW, targetH)
        canvas.drawBitmap(src, srcRect, dstRect, paint)

        return Pair(output, scale)
    }

    fun isHudPrompt(text: String): Boolean {
        val lower = text.lowercase(java.util.Locale.US).trim()
        return lower.contains("tap and hold to skip") ||
               lower.contains("hold to skip") ||
               lower.contains("press to skip") ||
               lower.contains("to skip") ||
               lower.contains("autosaves") ||
               lower.contains("autosave") ||
               lower.contains("saving...") ||
               lower.contains("loading...")
    }

    fun isNoiseOrSymbol(text: String): Boolean {
        if (text.isEmpty()) return true
        val trimmed = text.trim()
        val lettersOnly = trimmed.filter { it.isLetter() }
        if (lettersOnly.length < 4) {
            val isAllCaps = lettersOnly.length >= 2 && lettersOnly == lettersOnly.uppercase(java.util.Locale.US)
            val lower = lettersOnly.lowercase(java.util.Locale.US)
            if (isAllCaps || !SHORT_VALID_WORDS.contains(lower)) {
                if (PatchManager.lookup(trimmed) == null) {
                    return true
                }
            }
        }
        return STANDALONE_NUMBER_OR_SYMBOL_REGEX.matches(trimmed)
    }

    fun isAllCapsNonDialogue(text: String): Boolean {
        val trimmed = text.trim()
        val isAllCaps = trimmed.length >= 4 && trimmed == trimmed.uppercase(java.util.Locale.US) && trimmed.any { it.isLetter() }
        if (!isAllCaps) return false
        // Allow intentional uppercase game terms defined in loaded patches (e.g., game menu items or special terms)
        return PatchManager.lookup(trimmed) == null
    }

    /**
     * Processes full-screen game bitmap, detects English text blocks,
     * filters out noise, HUD numbers, and text intersecting the floating bubble.
     * Enforces strict confidence (>= 0.78) and English token linguistic validity (>= 60%).
     * Maps all coordinates back to full-screen display space and clamps within display boundaries.
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        excludedBounds: Rect? = null,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        scaleFactor: Float = 1.0f,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): List<OcrBlock> = suspendCancellableCoroutine { continuation ->
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        // Ensure ARGB_8888 format
        val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                bitmap
            }
        } else {
            bitmap
        }

        try {
            val inputImage = InputImage.fromBitmap(argbBitmap, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val resultBlocks = mutableListOf<OcrBlock>()
                    val s = if (scaleFactor > 0f) scaleFactor else 1.0f

                    for (block in visionText.textBlocks) {
                        val rawBox = block.boundingBox ?: continue
                        val textTrimmed = block.text.trim()

                        // 1. Filter out empty, standalone numbers, pure symbols, HUD prompts, or all-caps non-dialogue background textures
                        if (isNoiseOrSymbol(textTrimmed) || isHudPrompt(textTrimmed) || isAllCapsNonDialogue(textTrimmed)) {
                            continue
                        }

                        // 2. Strict Linguistic Pattern Filter: reject blocks where < 60% of tokens are valid English words
                        if (!passesEnglishTokenRatioFilter(textTrimmed)) {
                            continue
                        }

                        // Map bounding box back to full screen display coordinates and clamp
                        val realLeft = (cropLeft + (rawBox.left / s)).toInt().coerceIn(0, if (screenWidth > 0) screenWidth else Int.MAX_VALUE)
                        val realTop = (cropTop + (rawBox.top / s)).toInt().coerceIn(0, if (screenHeight > 0) screenHeight else Int.MAX_VALUE)
                        val realRight = (cropLeft + (rawBox.right / s)).toInt().coerceIn(realLeft, if (screenWidth > 0) screenWidth else Int.MAX_VALUE)
                        val realBottom = (cropTop + (rawBox.bottom / s)).toInt().coerceIn(realTop, if (screenHeight > 0) screenHeight else Int.MAX_VALUE)
                        val mappedBlockBox = Rect(realLeft, realTop, realRight, realBottom)

                        // 3. Filter out elements strictly intersecting the floating control bubble
                        if (excludedBounds != null && (excludedBounds.contains(mappedBlockBox.centerX(), mappedBlockBox.centerY()) || Rect.intersects(mappedBlockBox, excludedBounds))) {
                            continue
                        }

                        // 4. Minimum size threshold
                        if (mappedBlockBox.width() < 10 || mappedBlockBox.height() < 10) {
                            continue
                        }

                        // Calculate average line confidence if provided by ML Kit
                        var totalConf = 0.0f
                        var confCount = 0

                        val lines = block.lines.mapNotNull { line ->
                            val lineBox = line.boundingBox ?: return@mapNotNull null
                            val lineText = line.text.trim()
                            if (isNoiseOrSymbol(lineText) || isHudPrompt(lineText) || isAllCapsNonDialogue(lineText)) return@mapNotNull null

                            // ML Kit Line confidence check
                            line.confidence?.let { conf ->
                                totalConf += conf
                                confCount++
                            }

                            val lineRealLeft = (cropLeft + (lineBox.left / s)).toInt().coerceIn(0, if (screenWidth > 0) screenWidth else Int.MAX_VALUE)
                            val lineRealTop = (cropTop + (lineBox.top / s)).toInt().coerceIn(0, if (screenHeight > 0) screenHeight else Int.MAX_VALUE)
                            val lineRealRight = (cropLeft + (lineBox.right / s)).toInt().coerceIn(lineRealLeft, if (screenWidth > 0) screenWidth else Int.MAX_VALUE)
                            val lineRealBottom = (cropTop + (lineBox.bottom / s)).toInt().coerceIn(lineRealTop, if (screenHeight > 0) screenHeight else Int.MAX_VALUE)

                            OcrLine(
                                text = lineText,
                                boundingBox = Rect(lineRealLeft, lineRealTop, lineRealRight, lineRealBottom)
                            )
                        }

                        if (lines.isEmpty()) {
                            continue
                        }

                        val blockConfidence = if (confCount > 0) totalConf / confCount else null

                        // 5. Strict Confidence Filter: Reject if ML Kit confidence < 0.78
                        if (blockConfidence != null && blockConfidence < MIN_CONFIDENCE_THRESHOLD) {
                            continue
                        }

                        resultBlocks.add(
                            OcrBlock(
                                text = textTrimmed,
                                boundingBox = mappedBlockBox,
                                confidence = blockConfidence,
                                lines = lines
                            )
                        )
                    }

                    if (continuation.isActive) {
                        continuation.resume(resultBlocks)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit text recognition failed: ${e.message}", e)
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit InputImage error: ${t.message}", t)
            if (continuation.isActive) {
                continuation.resume(emptyList())
            }
        }
    }


    /**
     * Rejects gibberish strings and HUD noise by requiring at least 60% of tokens to have
     * valid English vowel/consonant letter patterns (>= 3 letters) or known short English words.
     */
    private fun passesEnglishTokenRatioFilter(text: String): Boolean {
        val tokens = text.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        var validCount = 0
        for (token in tokens) {
            val letters = token.filter { it.isLetter() }
            if (letters.length >= 3) {
                val hasVowel = letters.any { it in "aeiouyAEIOUY" }
                val hasConsonant = letters.any { it in "bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ" }
                if (hasVowel && hasConsonant) {
                    validCount++
                }
            } else if (letters.isNotEmpty() && SHORT_VALID_WORDS.contains(letters.lowercase())) {
                validCount++
            }
        }

        val ratio = validCount.toDouble() / tokens.size.toDouble()
        return ratio >= 0.60
    }

    /**
     * Rapidly recognizes text from a cropped ROI or MORT-filtered bitmap.
     * Extracts all valid lines, filters HUD prompts and noise, and sorts lines top-to-bottom.
     */
    suspend fun recognizeRoi(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { continuation ->
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val roiWidth = bitmap.width.toFloat()

        val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                bitmap
            }
        } else {
            bitmap
        }

        try {
            val inputImage = InputImage.fromBitmap(argbBitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val allRawLines = visionText.textBlocks.flatMap { it.lines }

                    val hasCenteredLine = allRawLines.any { line ->
                        val box = line.boundingBox
                        if (box != null && roiWidth > 0f) {
                            val cxRatio = box.centerX() / roiWidth
                            cxRatio in 0.22f..0.78f && line.text.trim().any { it.isLetter() }
                        } else false
                    }

                    val lines = allRawLines
                        .filter { line ->
                            val text = line.text.trim()
                            val box = line.boundingBox

                            // 1. Single character rejection: alphabetic count must be >= 2
                            val letterCount = text.count { it.isLetter() }
                            if (letterCount < 2) return@filter false

                            // 2. Reject Opening Credits & Title lines (>=75% uppercase or credit keywords)
                            if (isCreditOrTitleLine(text)) return@filter false

                            // 3. Horizontal Center-Band Filter (0.22f..0.78f)
                            if (box != null && roiWidth > 0f) {
                                val cxRatio = box.centerX() / roiWidth
                                val isOutsideCenter = cxRatio !in 0.22f..0.78f
                                if (isOutsideCenter) {
                                    val isUpperOrShort = isMostlyUpper(text) || text.length < 15
                                    if (hasCenteredLine || isUpperOrShort) {
                                        return@filter false
                                    }
                                }
                            }

                            !isNoiseOrSymbol(text) &&
                            !isHudPrompt(text) &&
                            !isAllCapsNonDialogue(text) &&
                            !OcrTextCleaner.isNoiseOrPrompt(text) &&
                            !OcrTextCleaner.isGarbledDocumentNoise(text) &&
                            !OcrTextCleaner.isDiacriticSelfOcrGarble(text)
                        }
                        .sortedBy { it.boundingBox?.top ?: 0 }
                        .map { OcrTextCleaner.normalizeOcrArtifacts(it.text.trim()) }

                    val validLines = if (lines.isNotEmpty()) {
                        val joined = lines.joinToString(" ")
                        val normalizedJoined = OcrTextCleaner.normalizeOcrArtifacts(joined)
                        val totalLetters = normalizedJoined.count { it.isLetter() }
                        if (totalLetters < 2 ||
                            !passesEnglishTokenRatioFilter(normalizedJoined) ||
                            OcrTextCleaner.isGarbledDocumentNoise(normalizedJoined) ||
                            OcrTextCleaner.isDiacriticSelfOcrGarble(normalizedJoined) ||
                            isCreditOrTitleLine(normalizedJoined)
                        ) {
                            emptyList()
                        } else {
                            lines
                        }
                    } else {
                        emptyList()
                    }

                    if (continuation.isActive) {
                        continuation.resume(validLines)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit recognizeRoi error: ${e.message}", e)
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit recognizeRoi exception: ${t.message}", t)
            if (continuation.isActive) {
                continuation.resume(emptyList())
            }
        }
    }

    fun close() {
        try {
            recognizer.close()
            Log.d(TAG, "ML Kit TextRecognizer closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TextRecognizer: ${e.message}", e)
        }
    }
}

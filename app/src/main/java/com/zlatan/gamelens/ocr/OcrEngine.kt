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

    data class MenuTextBlock(
        val text: String,
        val boundingBox: Rect,
        var translatedText: String? = null,
        val isHeader: Boolean = false
    )

    enum class SupersetVerdict {
        NOT_SUPERSET,
        CLEAN_PROGRESSIVE_EXPANSION,
        CREDIT_POLLUTION
    }

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

        fun stripBackgroundTextureGarbage(text: String): String {
            if (text.isBlank()) return ""
            val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val cleanTokens = tokens.filterNot { token ->
                val clean = token.trimEnd('.', ',', '!', '?', ';', ':', '"', '\'')
                // 3+ identical consecutive letters (e.g. sss, SsS)
                val has3Identical = Regex("(?i)([a-z])\\1{2,}").containsMatchIn(clean)
                // Letter+digit+letter corruption (e.g. s8t8, tet8a)
                val hasLetterDigitLetter = Regex("(?i)[a-z]+\\d+[a-z]+").containsMatchIn(clean)
                // Dot-glued nonsense tokens with 3+ repeated characters (e.g. sss.sste, sss.s8t8) excluding decimal numbers
                val isDotGluedGarbage = Regex("(?i)[a-z]{2,}\\.[a-z0-9]{3,}").matches(clean) &&
                        Regex("(?i)([a-z0-9])\\1{2,}").containsMatchIn(clean)
                has3Identical || hasLetterDigitLetter || isDotGluedGarbage
            }
            return cleanTokens.joinToString(" ")
        }

        fun uppercaseRatio(text: String): Float {
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return 0.0f
            return letters.count { it.isUpperCase() }.toFloat() / letters.length.toFloat()
        }

        private fun isAllCapsShoutedDialogue(text: String): Boolean {
            val trimmed = text.trim()
            return (trimmed.endsWith("!") || trimmed.endsWith("!\"")) &&
                    trimmed.length in 2..15 &&
                    trimmed.all { it.isLetter() || it.isWhitespace() || it == '!' || it == '\'' || it == '"' }
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

        fun classifySupersetExpansion(oldText: String, newText: String): SupersetVerdict {
            val oldTrim = oldText.trim()
            val newTrim = newText.trim()
            if (oldTrim.isBlank() || newTrim.isBlank() || oldTrim.equals(newTrim, ignoreCase = true)) {
                return SupersetVerdict.NOT_SUPERSET
            }
            if (newTrim.length <= oldTrim.length) {
                return SupersetVerdict.NOT_SUPERSET
            }

            // 1. Terminal glued punctuation check: e.g. "Exactly. Good luck 47.RROBERTRow"
            // Punctuation immediately followed by >=2 letters with no whitespace is an OCR gluing artifact / credit pollution
            if (Regex("[.!?][A-Za-z]{2,}").containsMatchIn(newTrim)) {
                return SupersetVerdict.CREDIT_POLLUTION
            }

            // 2. Identify added prefix and added suffix
            var addedPrefix = ""
            var addedSuffix = ""
            var matched = false

            var idx = newTrim.indexOf(oldTrim, ignoreCase = true)
            var matchLen = oldTrim.length

            if (idx < 0) {
                val oldTrimmedPunct = oldTrim.trimEnd('.', '!', '?', ',', ';', ':', '"', '\'', ' ')
                if (oldTrimmedPunct.length >= 3) {
                    idx = newTrim.indexOf(oldTrimmedPunct, ignoreCase = true)
                    if (idx >= 0) {
                        matchLen = oldTrimmedPunct.length
                    }
                }
            }

            if (idx >= 0) {
                addedPrefix = newTrim.substring(0, idx).trim()
                addedSuffix = newTrim.substring(idx + matchLen).trim()
                matched = true
            } else {
                // Token-level sliding window match
                val cWords = oldTrim.lowercase(java.util.Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                val nWords = newTrim.lowercase(java.util.Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                if (cWords.size >= 2 && nWords.size > cWords.size) {
                    val windowSize = cWords.size
                    var bestWindow = -1
                    for (i in 0..(nWords.size - windowSize)) {
                        var matches = 0
                        for (j in 0 until windowSize) {
                            if (nWords[i + j] == cWords[j]) {
                                matches++
                            }
                        }
                        if (matches == windowSize || (cWords.size >= 4 && matches.toFloat() / cWords.size >= 0.80f)) {
                            bestWindow = i
                            break
                        }
                    }
                    if (bestWindow >= 0) {
                        matched = true
                        val preWords = nWords.take(bestWindow)
                        val postWords = nWords.drop(bestWindow + windowSize)
                        if (preWords.isNotEmpty()) addedPrefix = preWords.joinToString(" ")
                        if (postWords.isNotEmpty()) addedSuffix = postWords.joinToString(" ")
                    }
                }
            }

            if (!matched || (addedPrefix.isEmpty() && addedSuffix.isEmpty())) {
                // Check if high word recall (>= 65%) with non-matching extra tokens indicates credit/dossier pollution
                val oldWords = oldTrim.lowercase(java.util.Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                val newWords = newTrim.lowercase(java.util.Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

                if (oldWords.isNotEmpty() && newWords.isNotEmpty()) {
                    val matchingCount = oldWords.count { ow -> newWords.contains(ow) }
                    val wordRecall = matchingCount.toFloat() / oldWords.size.toFloat()

                    if (wordRecall >= 0.65f) {
                        val extraTokens = newWords.filter { nw -> !oldWords.contains(nw) }
                        if (extraTokens.isNotEmpty()) {
                            val extraString = extraTokens.joinToString(" ")
                            val rawNewTokens = newTrim.split(Regex("\\s+")).filter { it.isNotBlank() }
                            val rawExtraTokens = rawNewTokens.filter { nt ->
                                val cleanNt = nt.lowercase(java.util.Locale.US).filter { it.isLetterOrDigit() }
                                extraTokens.contains(cleanNt)
                            }

                            val hasCreditKeyword = CREDIT_KEYWORDS.any { kw -> newTrim.uppercase(java.util.Locale.US).contains(kw) }
                            val hasAllCapsCredit = rawExtraTokens.any { token ->
                                val letters = token.filter { it.isLetter() }
                                letters.length >= 4 && (letters.count { it.isUpperCase() }.toFloat() / letters.length.toFloat()) >= 0.65f
                            }
                            val hasAlphanumericCorruption = rawExtraTokens.any { token ->
                                val hasDigit = token.any { it.isDigit() }
                                val hasLetter = token.any { it.isLetter() }
                                hasDigit && hasLetter
                            }
                            val lowExtraQuality = scoreOcrQuality(extraString) < 0.75f

                            if (hasCreditKeyword || hasAllCapsCredit || hasAlphanumericCorruption || (rawExtraTokens.size >= 2 && lowExtraQuality)) {
                                return SupersetVerdict.CREDIT_POLLUTION
                            }
                        }
                    }
                }

                return SupersetVerdict.NOT_SUPERSET
            }

            // 3. Left-edge pollution guards: newly introduced prefixes
            if (addedPrefix.isNotEmpty()) {
                val prefixTokens = addedPrefix.split(Regex("\\s+")).filter { it.isNotBlank() }
                val oldStartsWithCapital = oldTrim.firstOrNull()?.isUpperCase() == true
                if (uppercaseRatio(addedPrefix) >= 0.45f ||
                    scoreOcrQuality(addedPrefix) < 0.75f ||
                    (oldStartsWithCapital && prefixTokens.size <= 3) ||
                    isCreditOrTitleLine(addedPrefix)
                ) {
                    return SupersetVerdict.CREDIT_POLLUTION
                }
            }

            // 4. Right-edge delta quality audit
            if (addedSuffix.isNotEmpty()) {
                if (scoreOcrQuality(addedSuffix) < 0.72f) {
                    return SupersetVerdict.CREDIT_POLLUTION
                }
                val suffixLetters = addedSuffix.filter { it.isLetter() }
                if (suffixLetters.length >= 4) {
                    val upRatio = uppercaseRatio(addedSuffix)
                    val isShouted = isAllCapsShoutedDialogue(addedSuffix)
                    if (upRatio >= 0.55f && !isShouted) {
                        return SupersetVerdict.CREDIT_POLLUTION
                    }
                }
            }

            // 5. Total text quality audit
            if (scoreOcrQuality(newTrim) < 0.70f) {
                return SupersetVerdict.CREDIT_POLLUTION
            }

            return SupersetVerdict.CLEAN_PROGRESSIVE_EXPANSION
        }
    }

    private data class ElementCluster(
        val text: String,
        val top: Int,
        val centerX: Float,
        val isCentered: Boolean
    )

    fun isCreditOrTitleLine(text: String): Boolean = Companion.isCreditOrTitleLine(text)

    fun scoreOcrQuality(text: String): Float = Companion.scoreOcrQuality(text)

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

                    // Element-Level Gap Splitting: iterate line.elements sorted left-to-right.
                    // Split elements into separate horizontal clusters whenever the gap between consecutive elements > 1.8f * lineHeight.
                    val allClusters = mutableListOf<ElementCluster>()
                    for (line in allRawLines) {
                        val elements = line.elements
                        if (elements.isEmpty()) {
                            val text = line.text.trim()
                            if (text.isNotEmpty()) {
                                val box = line.boundingBox
                                val cx = box?.centerX()?.toFloat() ?: (roiWidth / 2f)
                                val cxRatio = if (roiWidth > 0f) cx / roiWidth else 0.5f
                                allClusters.add(
                                    ElementCluster(
                                        text = text,
                                        top = box?.top ?: 0,
                                        centerX = cx,
                                        isCentered = cxRatio in 0.18f..0.82f
                                    )
                                )
                            }
                            continue
                        }

                        val sortedElements = elements.sortedBy { it.boundingBox?.left ?: 0 }
                        val lineHeight = (line.boundingBox?.height() ?: 30).toFloat().coerceAtLeast(20f)
                        val gapThreshold = 1.8f * lineHeight

                        val clusters = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Element>>()
                        var curCluster = mutableListOf<com.google.mlkit.vision.text.Text.Element>()

                        for (elem in sortedElements) {
                            if (curCluster.isEmpty()) {
                                curCluster.add(elem)
                            } else {
                                val prevElem = curCluster.last()
                                val prevRight = prevElem.boundingBox?.right ?: 0
                                val currLeft = elem.boundingBox?.left ?: 0
                                val gap = currLeft - prevRight
                                if (gap > gapThreshold) {
                                    clusters.add(curCluster)
                                    curCluster = mutableListOf(elem)
                                } else {
                                    curCluster.add(elem)
                                }
                            }
                        }
                        if (curCluster.isNotEmpty()) {
                            clusters.add(curCluster)
                        }

                        for (c in clusters) {
                            val cText = c.joinToString(" ") { it.text.trim() }.trim()
                            if (cText.isEmpty()) continue
                            val cLeft = c.mapNotNull { it.boundingBox?.left }.minOrNull() ?: (line.boundingBox?.left ?: 0)
                            val cRight = c.mapNotNull { it.boundingBox?.right }.maxOrNull() ?: (line.boundingBox?.right ?: 0)
                            val cTop = c.mapNotNull { it.boundingBox?.top }.minOrNull() ?: (line.boundingBox?.top ?: 0)
                            val cCenterX = (cLeft + cRight) / 2f
                            val cxRatio = if (roiWidth > 0f) cCenterX / roiWidth else 0.5f
                            allClusters.add(
                                ElementCluster(
                                    text = cText,
                                    top = cTop,
                                    centerX = cCenterX,
                                    isCentered = cxRatio in 0.18f..0.82f
                                )
                            )
                        }
                    }

                    val hasCenteredCluster = allClusters.any { it.isCentered && it.text.any { ch -> ch.isLetter() } }

                    val lines = allClusters
                        .mapNotNull { cluster ->
                            val stripped = stripBackgroundTextureGarbage(cluster.text)
                            if (stripped.isBlank()) null else cluster.copy(text = stripped)
                        }
                        .filter { cluster ->
                            val text = cluster.text

                            // 1. Single character rejection: alphabetic count must be >= 2
                            val letterCount = text.count { it.isLetter() }
                            if (letterCount < 2) return@filter false

                            // 2. Reject Opening Credits & Title lines (>=75% uppercase for >=4 chars, or credit keywords)
                            if (isCreditOrTitleLine(text)) return@filter false

                            // 3. Side-Margin Filter: discard outer margin clusters (centerX/roiWidth outside 0.18f..0.82f) when a centered cluster exists
                            if (hasCenteredCluster && !cluster.isCentered) {
                                return@filter false
                            }

                            // 4. Reject Clipped Single-Word HUD Action Prompts & UI Fragments
                            val clusterTokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (clusterTokens.size == 1) {
                                val word = clusterTokens[0]
                                val coreLetters = word.filter { it.isLetter() }.lowercase(java.util.Locale.US)
                                val isShortOrHud = coreLetters.length <= 4 || OcrTextCleaner.HUD_ACTION_STEMS.contains(coreLetters)
                                if (isShortOrHud && PatchManager.lookup(text) == null) {
                                    return@filter false
                                }
                            }
                            if (Regex("(?i)^A\\s+[A-Za-z]{4,12}$").matches(text)) {
                                val hasTerminal = text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
                                if (!hasTerminal && PatchManager.lookup(text) == null) {
                                    return@filter false
                                }
                            }

                            !isNoiseOrSymbol(text) &&
                            !isHudPrompt(text) &&
                            !isAllCapsNonDialogue(text) &&
                            !OcrTextCleaner.isNoiseOrPrompt(text) &&
                            !OcrTextCleaner.isGarbledDocumentNoise(text) &&
                            !OcrTextCleaner.isDiacriticSelfOcrGarble(text)
                        }
                        .sortedBy { it.top }
                        .map { OcrTextCleaner.normalizeOcrArtifacts(it.text.trim()) }

                    val validLines = if (lines.isNotEmpty()) {
                        val joined = lines.joinToString(" ")
                        val normalizedJoined = OcrTextCleaner.normalizeOcrArtifacts(joined)
                        val strippedJoined = stripBackgroundTextureGarbage(normalizedJoined)
                        val totalLetters = strippedJoined.count { it.isLetter() }
                        val realWords = strippedJoined.split(Regex("\\s+")).filter { w ->
                            val lettersOnly = w.filter { it.isLetter() }
                            lettersOnly.length >= 2 && !OcrTextCleaner.HUD_ACTION_STEMS.contains(lettersOnly.lowercase(java.util.Locale.US))
                        }
                        val joinedTokens = strippedJoined.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val isSingleWordHudFragment = joinedTokens.size == 1 && run {
                            val word = joinedTokens[0]
                            val coreLetters = word.filter { it.isLetter() }.lowercase(java.util.Locale.US)
                            val isShortOrHud = coreLetters.length <= 4 || OcrTextCleaner.HUD_ACTION_STEMS.contains(coreLetters)
                            isShortOrHud && PatchManager.lookup(strippedJoined) == null
                        }
                        val isBannerPopup = Regex("(?i)^A\\s+[A-Za-z]{4,12}$").matches(strippedJoined) &&
                                !strippedJoined.endsWith(".") && !strippedJoined.endsWith("!") &&
                                PatchManager.lookup(strippedJoined) == null

                        if (totalLetters < 2 ||
                            (realWords.size < 2 && PatchManager.lookup(strippedJoined) == null) ||
                            isSingleWordHudFragment ||
                            isBannerPopup ||
                            !passesEnglishTokenRatioFilter(strippedJoined) ||
                            OcrTextCleaner.isGarbledDocumentNoise(strippedJoined) ||
                            OcrTextCleaner.isDiacriticSelfOcrGarble(strippedJoined) ||
                            isCreditOrTitleLine(strippedJoined)
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

    /**
     * Dedicated Full-Screen Menu / Popup OCR Mode.
     * Runs ML Kit across the full-screen frame without subtitle ROI cropping,
     * without all-caps credit filtration (preserving headers like AUTOSAVES AND CHECKPOINTS, BRIEFING),
     * without center-band restriction, and without single-word punctuation rejection.
     * Cleanly groups lines in each TextBlock into readable paragraphs, filtering out pure numeric HUD counters.
     */
    suspend fun recognizeFullScreenMenu(bitmap: Bitmap): List<MenuTextBlock> = suspendCancellableCoroutine { continuation ->
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

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
                    val resultBlocks = mutableListOf<MenuTextBlock>()

                    val allLineHeights = visionText.textBlocks.flatMap { it.lines }
                        .mapNotNull { it.boundingBox?.height() }
                        .filter { it > 0 }
                        .sorted()
                    val medianLineHeight = if (allLineHeights.isNotEmpty()) {
                        allLineHeights[allLineHeights.size / 2]
                    } else 0

                    for (block in visionText.textBlocks) {
                        val rawText = block.text.trim()
                        if (rawText.length < 2) continue

                        // Filter out pure numeric HUD counters (e.g. "8 / 74", "100%", "12:30", "$500")
                        val lettersCount = rawText.count { it.isLetter() }
                        if (lettersCount < 2) continue

                        val box = block.boundingBox ?: Rect(0, 0, 0, 0)

                        // Filter out miniature thumbnail labels inside menu tutorials whose bounding-box height is < 0.62f * medianLineHeight
                        if (medianLineHeight > 0 && box.height() < 0.62f * medianLineHeight && block.lines.size == 1) {
                            continue
                        }

                        // Merge lines in the block cleanly into a paragraph preserving intentional breaks
                        val cleanedLines = block.lines.mapNotNull { line ->
                            val lineText = line.text.trim()
                            if (lineText.count { it.isLetter() } >= 1) lineText else null
                        }

                        if (cleanedLines.isEmpty()) continue

                        val paragraphText = OcrTextCleaner.normalizeOcrArtifacts(cleanedLines.joinToString(" "))
                        val isHeaderCandidate = (block.lines.size == 1 && paragraphText.length in 3..45) ||
                                (paragraphText.count { it.isUpperCase() }.toFloat() / paragraphText.count { it.isLetter() }.coerceAtLeast(1) >= 0.70f && paragraphText.length <= 60)

                        resultBlocks.add(
                            MenuTextBlock(
                                text = paragraphText,
                                boundingBox = box,
                                isHeader = isHeaderCandidate
                            )
                        )
                    }

                    // Group and merge vertically adjacent blocks belonging to the same text card
                    val mergedBlocks = mutableListOf<MenuTextBlock>()
                    val sorted = resultBlocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

                    for (cur in sorted) {
                        if (mergedBlocks.isEmpty()) {
                            mergedBlocks.add(cur)
                        } else {
                            val prev = mergedBlocks.last()
                            val verticalGap = cur.boundingBox.top - prev.boundingBox.bottom
                            val prevHeight = prev.boundingBox.height()
                            val horizontalOverlap = maxOf(0, minOf(prev.boundingBox.right, cur.boundingBox.right) - maxOf(prev.boundingBox.left, cur.boundingBox.left))
                            val isSameCard = verticalGap in -5..(prevHeight * 1.5f).toInt() && horizontalOverlap > 20 && !prev.isHeader && !cur.isHeader

                            if (isSameCard) {
                                mergedBlocks.removeAt(mergedBlocks.size - 1)
                                val unionBox = Rect(prev.boundingBox).apply { union(cur.boundingBox) }
                                val mergedText = OcrTextCleaner.normalizeOcrArtifacts("${prev.text} ${cur.text}")
                                mergedBlocks.add(
                                    MenuTextBlock(
                                        text = mergedText,
                                        boundingBox = unionBox,
                                        isHeader = false
                                    )
                                )
                            } else {
                                mergedBlocks.add(cur)
                            }
                        }
                    }

                    if (continuation.isActive) {
                        continuation.resume(mergedBlocks)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit recognizeFullScreenMenu error: ${e.message}", e)
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit recognizeFullScreenMenu exception: ${t.message}", t)
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

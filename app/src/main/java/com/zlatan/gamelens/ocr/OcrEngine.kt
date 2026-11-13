package com.zlatan.gamelens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Processes full-screen game bitmap, detects English text blocks,
     * filters out noise, HUD numbers, and text intersecting the floating bubble.
     * Enforces strict confidence (>= 0.78) and English token linguistic validity (>= 60%).
     */
    suspend fun recognizeText(bitmap: Bitmap, excludedBounds: Rect? = null): List<OcrBlock> = suspendCancellableCoroutine { continuation ->
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

                    for (block in visionText.textBlocks) {
                        val rawBox = block.boundingBox ?: continue
                        val textTrimmed = block.text.trim()

                        // 1. Filter out empty, standalone numbers or pure symbols (HUD ammo, health, scores)
                        if (isNoiseOrSymbol(textTrimmed)) {
                            continue
                        }

                        // 2. Strict Linguistic Pattern Filter: reject blocks where < 60% of tokens are valid English words
                        if (!passesEnglishTokenRatioFilter(textTrimmed)) {
                            continue
                        }

                        // 3. Filter out elements strictly intersecting the floating control bubble
                        if (excludedBounds != null && (excludedBounds.contains(rawBox.centerX(), rawBox.centerY()) || Rect.intersects(rawBox, excludedBounds))) {
                            continue
                        }

                        // 4. Minimum size threshold
                        if (rawBox.width() < 10 || rawBox.height() < 10) {
                            continue
                        }

                        // Calculate average line confidence if provided by ML Kit
                        var totalConf = 0.0f
                        var confCount = 0

                        val lines = block.lines.mapNotNull { line ->
                            val lineBox = line.boundingBox ?: return@mapNotNull null
                            val lineText = line.text.trim()
                            if (isNoiseOrSymbol(lineText)) return@mapNotNull null

                            // ML Kit Line confidence check
                            line.confidence?.let { conf ->
                                totalConf += conf
                                confCount++
                            }

                            OcrLine(
                                text = lineText,
                                boundingBox = lineBox
                            )
                        }

                        val blockConfidence = if (confCount > 0) totalConf / confCount else null

                        // 5. Strict Confidence Filter: Reject if ML Kit confidence < 0.78
                        if (blockConfidence != null && blockConfidence < MIN_CONFIDENCE_THRESHOLD) {
                            continue
                        }

                        resultBlocks.add(
                            OcrBlock(
                                text = textTrimmed,
                                boundingBox = rawBox,
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

    private fun isNoiseOrSymbol(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text.length == 1 && !text.equals("a", ignoreCase = true) && !text.equals("i", ignoreCase = true)) {
            return true
        }
        return STANDALONE_NUMBER_OR_SYMBOL_REGEX.matches(text)
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

    fun close() {
        try {
            recognizer.close()
            Log.d(TAG, "ML Kit TextRecognizer closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TextRecognizer: ${e.message}", e)
        }
    }
}

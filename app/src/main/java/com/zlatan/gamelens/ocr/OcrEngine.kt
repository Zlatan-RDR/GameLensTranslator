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
        private val STANDALONE_NUMBER_OR_SYMBOL_REGEX = Regex("^[0-9\\s.,/:\\-+%$#@!&*()_=\\[\\]{}|<>?~^]+$")
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Processes full-screen game bitmap, detects English text blocks,
     * filters out noise, HUD numbers, and text intersecting the floating bubble.
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

                        // 2. Filter out elements strictly intersecting the floating control bubble
                        if (excludedBounds != null && (excludedBounds.contains(rawBox.centerX(), rawBox.centerY()) || Rect.intersects(rawBox, excludedBounds))) {
                            continue
                        }

                        // 3. Minimum size threshold
                        if (rawBox.width() < 8 || rawBox.height() < 8) {
                            continue
                        }

                        val lines = block.lines.mapNotNull { line ->
                            val lineBox = line.boundingBox ?: return@mapNotNull null
                            val lineText = line.text.trim()
                            if (isNoiseOrSymbol(lineText)) return@mapNotNull null
                            OcrLine(
                                text = lineText,
                                boundingBox = lineBox
                            )
                        }

                        resultBlocks.add(
                            OcrBlock(
                                text = textTrimmed,
                                boundingBox = rawBox,
                                confidence = null,
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

    fun close() {
        try {
            recognizer.close()
            Log.d(TAG, "ML Kit TextRecognizer closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TextRecognizer: ${e.message}", e)
        }
    }
}

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
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Processes the cropped ROI bitmap and converts detected text into OcrBlock objects
     * with coordinates mapped to absolute screen coordinates.
     */
    suspend fun recognizeText(bitmap: Bitmap, roiOffset: Rect): List<OcrBlock> = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val resultBlocks = mutableListOf<OcrBlock>()

                for (block in visionText.textBlocks) {
                    val rawBox = block.boundingBox ?: continue
                    val absBox = Rect(
                        rawBox.left + roiOffset.left,
                        rawBox.top + roiOffset.top,
                        rawBox.right + roiOffset.left,
                        rawBox.bottom + roiOffset.top
                    )

                    val lines = block.lines.mapNotNull { line ->
                        val lineBox = line.boundingBox ?: return@mapNotNull null
                        OcrLine(
                            text = line.text,
                            boundingBox = Rect(
                                lineBox.left + roiOffset.left,
                                lineBox.top + roiOffset.top,
                                lineBox.right + roiOffset.left,
                                lineBox.bottom + roiOffset.top
                            )
                        )
                    }

                    resultBlocks.add(
                        OcrBlock(
                            text = block.text,
                            boundingBox = absBox,
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

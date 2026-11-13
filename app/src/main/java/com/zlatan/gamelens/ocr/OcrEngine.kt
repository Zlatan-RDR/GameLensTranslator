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
        private const val MIN_UPSCALE_HEIGHT_PX = 80
        private const val UPSCALE_FACTOR = 2.0f
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Processes the cropped ROI bitmap and converts detected text into OcrBlock objects
     * with coordinates mapped accurately to absolute screen coordinates.
     * Automatically upscales small stylized game fonts to maximize ML Kit recognition accuracy.
     */
    suspend fun recognizeText(bitmap: Bitmap, roiOffset: Rect): List<OcrBlock> = suspendCancellableCoroutine { continuation ->
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        // 1. Ensure ARGB_8888 format
        val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                bitmap
            }
        } else {
            bitmap
        }

        // 2. Upscale small game subtitle crops for high-fidelity OCR detection
        val needsUpscale = argbBitmap.height < MIN_UPSCALE_HEIGHT_PX
        val scale = if (needsUpscale) UPSCALE_FACTOR else 1.0f

        val ocrInputBitmap = if (needsUpscale) {
            try {
                val targetW = (argbBitmap.width * scale).toInt()
                val targetH = (argbBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(argbBitmap, targetW, targetH, true)
            } catch (_: Exception) {
                argbBitmap
            }
        } else {
            argbBitmap
        }

        val inputImage = InputImage.fromBitmap(ocrInputBitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val resultBlocks = mutableListOf<OcrBlock>()

                for (block in visionText.textBlocks) {
                    val rawBox = block.boundingBox ?: continue
                    val absBox = Rect(
                        ((rawBox.left / scale).toInt() + roiOffset.left),
                        ((rawBox.top / scale).toInt() + roiOffset.top),
                        ((rawBox.right / scale).toInt() + roiOffset.left),
                        ((rawBox.bottom / scale).toInt() + roiOffset.top)
                    )

                    val lines = block.lines.mapNotNull { line ->
                        val lineBox = line.boundingBox ?: return@mapNotNull null
                        OcrLine(
                            text = line.text,
                            boundingBox = Rect(
                                ((lineBox.left / scale).toInt() + roiOffset.left),
                                ((lineBox.top / scale).toInt() + roiOffset.top),
                                ((lineBox.right / scale).toInt() + roiOffset.left),
                                ((lineBox.bottom / scale).toInt() + roiOffset.top)
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

                // Cleanup temporary upscaled bitmap if allocated
                if (ocrInputBitmap != argbBitmap && ocrInputBitmap != bitmap) {
                    ocrInputBitmap.recycle()
                }
                if (argbBitmap != bitmap) {
                    argbBitmap.recycle()
                }

                if (continuation.isActive) {
                    continuation.resume(resultBlocks)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit text recognition failed: ${e.message}", e)
                if (ocrInputBitmap != argbBitmap && ocrInputBitmap != bitmap) {
                    ocrInputBitmap.recycle()
                }
                if (argbBitmap != bitmap) {
                    argbBitmap.recycle()
                }
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

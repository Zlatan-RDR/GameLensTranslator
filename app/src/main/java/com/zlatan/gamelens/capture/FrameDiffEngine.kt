package com.zlatan.gamelens.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class CropResult(
    val bitmap: Bitmap,
    val cropRect: Rect
)

class FrameDiffEngine(
    private val sampleGridSize: Int = 32,
    private val diffThresholdPercent: Float = 0.015f // 1.5% difference threshold for fine subtitles
) {
    private var previousSampleHash: LongArray? = null
    private var previousRoi: Rect? = null

    /**
     * Crops the full-screen bitmap strictly to the given ROI bounding box.
     * Returns both the cropped bitmap and the exact clamped cropRect coordinates.
     */
    fun cropToRoi(fullBitmap: Bitmap, roi: Rect): CropResult? {
        if (fullBitmap.isRecycled || fullBitmap.width <= 0 || fullBitmap.height <= 0) return null

        val clampLeft = roi.left.coerceIn(0, fullBitmap.width - 1)
        val clampTop = roi.top.coerceIn(0, fullBitmap.height - 1)
        val clampRight = roi.right.coerceIn(clampLeft + 1, fullBitmap.width)
        val clampBottom = roi.bottom.coerceIn(clampTop + 1, fullBitmap.height)

        val cropWidth = clampRight - clampLeft
        val cropHeight = clampBottom - clampTop

        if (cropWidth <= 10 || cropHeight <= 10) return null

        return try {
            val cropped = Bitmap.createBitmap(fullBitmap, clampLeft, clampTop, cropWidth, cropHeight)
            CropResult(cropped, Rect(clampLeft, clampTop, clampRight, clampBottom))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Evaluates whether the cropped ROI bitmap has changed significantly compared to the last frame.
     * Runs on Dispatchers.Default for zero main-thread overhead.
     */
    suspend fun hasFrameChanged(croppedBitmap: Bitmap, currentRoi: Rect): Boolean = withContext(Dispatchers.Default) {
        // If ROI frame moved or resized, invalidate previous baseline immediately
        if (previousRoi == null || previousRoi != currentRoi) {
            previousRoi = Rect(currentRoi)
            previousSampleHash = computeSampleHash(croppedBitmap)
            return@withContext true
        }

        val currentHash = computeSampleHash(croppedBitmap)
        val prevHash = previousSampleHash

        if (prevHash == null || prevHash.size != currentHash.size) {
            previousSampleHash = currentHash
            return@withContext true
        }

        var differingSamples = 0
        val totalSamples = currentHash.size

        for (i in 0 until totalSamples) {
            val prevPixel = prevHash[i]
            val currPixel = currentHash[i]

            val dr = abs(((prevPixel shr 16) and 0xFF) - ((currPixel shr 16) and 0xFF))
            val dg = abs(((prevPixel shr 8) and 0xFF) - ((currPixel shr 8) and 0xFF))
            val db = abs((prevPixel and 0xFF) - (currPixel and 0xFF))

            // Check if color difference exceeds tolerance
            if (dr + dg + db > 40) {
                differingSamples++
            }
        }

        val changeRatio = differingSamples.toFloat() / totalSamples.toFloat()
        val hasChanged = changeRatio >= diffThresholdPercent

        if (hasChanged) {
            previousSampleHash = currentHash
        }

        hasChanged
    }

    private fun computeSampleHash(bitmap: Bitmap): LongArray {
        val width = bitmap.width
        val height = bitmap.height
        val samples = LongArray(sampleGridSize * sampleGridSize)

        val stepX = (width / sampleGridSize).coerceAtLeast(1)
        val stepY = (height / sampleGridSize).coerceAtLeast(1)

        var idx = 0
        for (y in 0 until sampleGridSize) {
            val sampleY = (y * stepY).coerceAtMost(height - 1)
            for (x in 0 until sampleGridSize) {
                val sampleX = (x * stepX).coerceAtMost(width - 1)
                val pixel = bitmap.getPixel(sampleX, sampleY)
                samples[idx++] = pixel.toLong() and 0xFFFFFFFFL
            }
        }
        return samples
    }

    fun reset() {
        previousSampleHash = null
        previousRoi = null
    }
}

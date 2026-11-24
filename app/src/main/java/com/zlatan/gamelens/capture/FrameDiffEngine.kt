package com.zlatan.gamelens.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FrameDiffEngine(
    private val sampleGridSize: Int = 36,
    private val diffThresholdPercent: Float = 0.005f // 0.5% difference threshold for sensitive subtitle detection
) {
    private val totalSamples = sampleGridSize * sampleGridSize
    private val currentSampleHash = LongArray(totalSamples)
    private val previousSampleHash = LongArray(totalSamples)
    private var hasValidPrevious = false
    private var previousWidth: Int = 0
    private var previousHeight: Int = 0

    /**
     * Evaluates whether the full-screen frame has changed significantly compared to the last frame.
     * Optionally skips pixel samples falling inside the excluded bubble area.
     * Uses pre-allocated LongArray primitive buffers for zero GC allocation churn.
     * Runs on Dispatchers.Default for zero main-thread overhead.
     */
    suspend fun hasFrameChanged(bitmap: Bitmap, excludedRect: Rect? = null): Boolean = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@withContext false

        val w = bitmap.width
        val h = bitmap.height

        populateSampleHash(bitmap, excludedRect)

        // If dimensions changed (e.g. rotation) or first frame
        if (!hasValidPrevious || previousWidth != w || previousHeight != h) {
            previousWidth = w
            previousHeight = h
            System.arraycopy(currentSampleHash, 0, previousSampleHash, 0, totalSamples)
            hasValidPrevious = true
            return@withContext true
        }

        var differingSamples = 0
        var validSamples = 0

        for (i in 0 until totalSamples) {
            val prevPixel = previousSampleHash[i]
            val currPixel = currentSampleHash[i]

            // -1L indicates an excluded/ignored sample (e.g. floating bubble)
            if (prevPixel == -1L || currPixel == -1L) continue
            validSamples++

            val dr = abs(((prevPixel shr 16) and 0xFF) - ((currPixel shr 16) and 0xFF))
            val dg = abs(((prevPixel shr 8) and 0xFF) - ((currPixel shr 8) and 0xFF))
            val db = abs((prevPixel and 0xFF) - (currPixel and 0xFF))

            if (dr + dg + db > 35) {
                differingSamples++
            }
        }

        if (validSamples == 0) return@withContext false

        val changeRatio = differingSamples.toFloat() / validSamples.toFloat()
        val hasChanged = changeRatio >= diffThresholdPercent

        if (hasChanged) {
            System.arraycopy(currentSampleHash, 0, previousSampleHash, 0, totalSamples)
        }

        hasChanged
    }

    private fun populateSampleHash(bitmap: Bitmap, excludedRect: Rect?) {
        val width = bitmap.width
        val height = bitmap.height

        val stepX = (width / sampleGridSize).coerceAtLeast(1)
        val stepY = (height / sampleGridSize).coerceAtLeast(1)

        var idx = 0
        for (y in 0 until sampleGridSize) {
            val sampleY = (y * stepY).coerceAtMost(height - 1)
            for (x in 0 until sampleGridSize) {
                val sampleX = (x * stepX).coerceAtMost(width - 1)
                if (excludedRect != null && excludedRect.contains(sampleX, sampleY)) {
                    currentSampleHash[idx++] = -1L
                } else {
                    val pixel = bitmap.getPixel(sampleX, sampleY)
                    currentSampleHash[idx++] = pixel.toLong() and 0xFFFFFFFFL
                }
            }
        }
    }

    fun reset() {
        hasValidPrevious = false
        previousWidth = 0
        previousHeight = 0
    }
}

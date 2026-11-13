package com.zlatan.gamelens.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

data class SampledColorScheme(
    val backgroundColor: Int,
    val textColor: Int
)

object EdgeColorSampler {

    private const val DEFAULT_MARGIN_PX = 3
    private const val DEFAULT_BG_ALPHA = 225 // High opacity to completely mask original text
    private val FALLBACK_BG_COLOR = 0xD9121212.toInt() // Dark translucent default
    private val FALLBACK_TEXT_COLOR = 0xFFFFFFFF.toInt() // White

    /**
     * Samples the perimeter edge pixels directly surrounding the bounding box
     * to compute an adaptive matching background color and contrasting text color.
     * Guaranteed never to crash on recycled bitmaps or out-of-bounds coordinates.
     */
    fun sampleEdgeColors(bitmap: Bitmap?, box: Rect): SampledColorScheme {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || box.width() <= 0 || box.height() <= 0) {
            return SampledColorScheme(FALLBACK_BG_COLOR, FALLBACK_TEXT_COLOR)
        }

        return runCatching {
            if (bitmap.isRecycled) return SampledColorScheme(FALLBACK_BG_COLOR, FALLBACK_TEXT_COLOR)

            val bmpW = bitmap.width
            val bmpH = bitmap.height
            if (bmpW <= 0 || bmpH <= 0) return SampledColorScheme(FALLBACK_BG_COLOR, FALLBACK_TEXT_COLOR)

            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            var sampleCount = 0

            // 1. Sample Top and Bottom edge rows with strict boundary clamping
            val topY = (box.top - DEFAULT_MARGIN_PX).coerceIn(0, bmpH - 1)
            val bottomY = (box.bottom + DEFAULT_MARGIN_PX).coerceIn(0, bmpH - 1)
            val startX = box.left.coerceIn(0, bmpW - 1)
            val endX = box.right.coerceIn(0, bmpW - 1)

            val stepX = ((endX - startX) / 16).coerceAtLeast(1)
            for (x in startX..endX step stepX) {
                if (bitmap.isRecycled) break
                val clampedX = x.coerceIn(0, bmpW - 1)

                val pixelTop = bitmap.getPixel(clampedX, topY)
                totalR += Color.red(pixelTop)
                totalG += Color.green(pixelTop)
                totalB += Color.blue(pixelTop)
                sampleCount++

                val pixelBottom = bitmap.getPixel(clampedX, bottomY)
                totalR += Color.red(pixelBottom)
                totalG += Color.green(pixelBottom)
                totalB += Color.blue(pixelBottom)
                sampleCount++
            }

            // 2. Sample Left and Right edge columns with strict boundary clamping
            val leftX = (box.left - DEFAULT_MARGIN_PX).coerceIn(0, bmpW - 1)
            val rightX = (box.right + DEFAULT_MARGIN_PX).coerceIn(0, bmpW - 1)
            val startY = box.top.coerceIn(0, bmpH - 1)
            val endY = box.bottom.coerceIn(0, bmpH - 1)

            val stepY = ((endY - startY) / 12).coerceAtLeast(1)
            for (y in startY..endY step stepY) {
                if (bitmap.isRecycled) break
                val clampedY = y.coerceIn(0, bmpH - 1)

                val pixelLeft = bitmap.getPixel(leftX, clampedY)
                totalR += Color.red(pixelLeft)
                totalG += Color.green(pixelLeft)
                totalB += Color.blue(pixelLeft)
                sampleCount++

                val pixelRight = bitmap.getPixel(rightX, clampedY)
                totalR += Color.red(pixelRight)
                totalG += Color.green(pixelRight)
                totalB += Color.blue(pixelRight)
                sampleCount++
            }

            if (sampleCount == 0) {
                return SampledColorScheme(FALLBACK_BG_COLOR, FALLBACK_TEXT_COLOR)
            }

            val avgR = (totalR / sampleCount).toInt().coerceIn(0, 255)
            val avgG = (totalG / sampleCount).toInt().coerceIn(0, 255)
            val avgB = (totalB / sampleCount).toInt().coerceIn(0, 255)

            val bgColor = Color.argb(DEFAULT_BG_ALPHA, avgR, avgG, avgB)

            // Calculate perceived luminance to select high-contrast readable text
            val luminance = (0.299 * avgR + 0.587 * avgG + 0.114 * avgB) / 255.0
            val textColor = if (luminance > 0.55) {
                Color.parseColor("#111111") // Dark text for bright game surfaces
            } else {
                Color.parseColor("#FFFFFF") // Crisp white text for dark/normal game surfaces
            }

            SampledColorScheme(bgColor, textColor)
        }.getOrDefault(SampledColorScheme(FALLBACK_BG_COLOR, FALLBACK_TEXT_COLOR))
    }
}

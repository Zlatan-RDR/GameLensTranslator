package com.zlatan.gamelens.ocr

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.abs

/**
 * High-speed 4x4-strided luminance pixel-diff gate for cropped subtitle ROI (~1.5ms on Snapdragon 8s Gen 4).
 * Compares integer luminance across a 4x4 subsampled grid and gates downstream OCR if delta < 2.5%.
 */
class FastPixelDiffGate(
    private val stride: Int = 4,
    private val diffThresholdPercent: Float = 0.025f // 2.5% delta
) {
    private var lastLuminanceSamples: ByteArray? = null
    private var lastSampleCount: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    /**
     * Samples 4x4-strided luminance across the cropped ROI.
     * Returns true if pixel delta >= 2.5% or dimensions changed.
     */
    fun hasChanged(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val w = bitmap.width
        val h = bitmap.height

        val samplesX = (w + stride - 1) / stride
        val samplesY = (h + stride - 1) / stride
        val totalSamples = samplesX * samplesY
        val currentSamples = ByteArray(totalSamples)

        val rowPixels = IntArray(w)
        var sampleIdx = 0

        for (y in 0 until h step stride) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w step stride) {
                val color = rowPixels[x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                // Fast integer luminance approximation: (r*77 + g*150 + b*29) shr 8
                val lum = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                if (sampleIdx < totalSamples) {
                    currentSamples[sampleIdx++] = lum
                }
            }
        }

        val prev = lastLuminanceSamples
        if (prev == null || lastWidth != w || lastHeight != h || prev.size != sampleIdx) {
            lastLuminanceSamples = currentSamples
            lastSampleCount = sampleIdx
            lastWidth = w
            lastHeight = h
            return true
        }

        var diffCount = 0
        for (i in 0 until sampleIdx) {
            val d = abs((currentSamples[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            if (d > 25) {
                diffCount++
            }
        }

        val changeRatio = diffCount.toFloat() / sampleIdx.toFloat()
        val changed = changeRatio >= diffThresholdPercent
        if (changed) {
            lastLuminanceSamples = currentSamples
            lastSampleCount = sampleIdx
        }
        return changed
    }

    fun reset() {
        lastLuminanceSamples = null
        lastSampleCount = 0
        lastWidth = 0
        lastHeight = 0
    }
}

/**
 * MORT (Monkeyhead's OCR Realtime Translator) luminance and color isolation mask.
 * Isolates crisp white/light-gray and bright yellow game dialogue text from complex 3D backgrounds.
 */
object MortFilter {
    /**
     * Binarizes subtitle pixels:
     * - Crisp white/off-white (lum >= 175, sat <= 65)
     * - Bright yellow subtitles (r >= 170, g >= 150, b <= 100, lum >= 150)
     * Output: 0xFFFFFFFF (pure white) for text, 0xFF000000 (pure black) for background.
     * Returns null if subtitle density is outside plausible dialogue bounds (< 30 or > 45% of ROI).
     */
    fun filter(src: Bitmap): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val w = src.width
        val h = src.height
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var subtitlePixels = 0
        for (i in 0 until total) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val sat = if (maxC == 0) 0 else ((maxC - minC) * 255) / maxC

            val isSub = (lum >= 175 && sat <= 65) || (r >= 170 && g >= 150 && b <= 100 && lum >= 150)
            if (isSub) {
                pixels[i] = -0x1 // 0xFFFFFFFF (pure white)
                subtitlePixels++
            } else {
                pixels[i] = -0x1000000 // 0xFF000000 (pure black)
            }
        }

        // Dialogue text plausibility filter: minimum 30 pixels, maximum 45% of ROI area
        if (subtitlePixels < 30 || subtitlePixels > (total * 0.45f)) {
            return null
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}

/**
 * Cleans extracted OCR lines and rejects HUD noise, counters, and short prompt artifacts.
 */
object OcrTextCleaner {
    private val STANDALONE_COUNTER_REGEX = Regex("^\\d+(\\s*[/\\\\\\-:]\\s*\\d+)?$")
    private val HUD_PROMPTS = setOf(
        "tap to", "press to", "hold to", "to skip", "skip",
        "press", "tap enter", "enter", "pause", "menu", "cancel",
        "continue", "autosave", "saving", "loading", "loading..."
    )

    fun isNoiseOrPrompt(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        // Standalone single letters (except valid English words 'a' or 'i')
        if (trimmed.length == 1) {
            return !trimmed.equals("a", ignoreCase = true) && !trimmed.equals("i", ignoreCase = true)
        }

        // Digits / score / counters like "12/30", "100", "5-2"
        if (STANDALONE_COUNTER_REGEX.matches(trimmed)) return true

        // Must contain at least one letter
        if (!trimmed.any { it.isLetter() }) return true

        val lower = trimmed.lowercase(Locale.US)
        for (prompt in HUD_PROMPTS) {
            if (lower == prompt || lower.startsWith("$prompt ") || lower.endsWith(" $prompt")) {
                return true
            }
        }

        // Short all-caps prompts (e.g. "PRESS X", "HOLD A", "TAP") unless found in patch
        if (trimmed.length in 3..14 && trimmed == trimmed.uppercase(Locale.US)) {
            val words = trimmed.split(Regex("\\s+"))
            if (words.size <= 2 && (words[0] in setOf("PRESS", "HOLD", "TAP", "USE", "SELECT", "BACK", "PAUSE", "MENU"))) {
                return true
            }
        }

        return false
    }

    /**
     * Calculates token similarity between two strings to avoid re-translating jitter (>= threshold).
     */
    fun isSimilar(s1: String, s2: String, threshold: Float = 0.88f): Boolean {
        if (s1.equals(s2, ignoreCase = true)) return true
        val n1 = s1.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), "")
        val n2 = s2.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), "")
        if (n1 == n2) return true
        if (n1.isEmpty() || n2.isEmpty()) return false

        val maxLen = maxOf(n1.length, n2.length)
        if (maxLen == 0) return true
        val dist = levenshtein(n1, n2)
        val similarity = 1.0f - (dist.toFloat() / maxLen.toFloat())
        return similarity >= threshold
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0) return len2
        if (len2 == 0) return len1
        if (abs(len1 - len2) > (maxOf(len1, len2) * 0.25f)) return abs(len1 - len2)

        val dp = IntArray(len2 + 1) { it }
        for (i in 1..len1) {
            var prev = dp[0]
            dp[0] = i
            val c1 = s1[i - 1]
            for (j in 1..len2) {
                val temp = dp[j]
                val cost = if (c1 == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[len2]
    }
}

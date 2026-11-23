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
    private var pendingSamples: ByteArray? = null
    private var lastSampleCount: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    /**
     * Computes the luminance difference ratio between the provided bitmap and the last committed frame.
     * Retains samples in pending state until committed.
     */
    fun computeDiffRatio(bitmap: Bitmap): Float {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0f
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

        pendingSamples = currentSamples
        val prev = lastLuminanceSamples
        if (prev == null || lastWidth != w || lastHeight != h || prev.size != sampleIdx) {
            return 1.0f
        }

        var diffCount = 0
        for (i in 0 until sampleIdx) {
            val d = abs((currentSamples[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            if (d > 25) {
                diffCount++
            }
        }

        return diffCount.toFloat() / sampleIdx.toFloat()
    }

    /**
     * Commits pending samples as the reference baseline for subsequent diff comparisons.
     */
    fun commitSamples(bitmap: Bitmap) {
        lastLuminanceSamples = pendingSamples
        lastSampleCount = pendingSamples?.size ?: 0
        lastWidth = bitmap.width
        lastHeight = bitmap.height
    }

    /**
     * Samples 4x4-strided luminance across the cropped ROI.
     * Returns true if pixel delta >= 2.5% or dimensions changed.
     */
    fun hasChanged(bitmap: Bitmap): Boolean {
        val ratio = computeDiffRatio(bitmap)
        val changed = ratio >= diffThresholdPercent
        if (changed) {
            commitSamples(bitmap)
        }
        return changed
    }

    fun reset() {
        lastLuminanceSamples = null
        pendingSamples = null
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

            // Softened threshold: lum >= 155 (was 175) to retain anti-aliased font strokes and thin letters
            val isSub = (lum >= 155 && sat <= 65) || (r >= 165 && g >= 145 && b <= 110 && lum >= 140)
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
    private val SHORT_VALID_WORDS = setOf(
        "a", "i", "to", "in", "on", "at", "by", "of", "or", "is", "it",
        "no", "go", "up", "we", "he", "me", "my", "do", "so", "ok", "am", "an", "as"
    )
    private val HUD_PROMPTS = setOf(
        "tap to", "press to", "hold to", "to skip", "skip",
        "press", "tap enter", "enter", "pause", "menu", "cancel",
        "continue", "autosave", "saving", "loading", "loading..."
    )
    val EUROPEAN_DIACRITICS = setOf(
        'ä', 'š', 'ì', '„', 'ö', 'ü', 'ç', 'é', 'è', 'à', 'â', 'ê', 'î', 'ô', 'û', 'ñ', '“', '”', '»', '«', '›', '‹'
    )

    /**
     * Rejects Arabic-as-Latin OCR garble (e.g. "Jlal ce Blg .äyul gUloglaall")
     * produced when ML Kit mistakenly scans Arabic overlay text with Latin models.
     */
    fun isDiacriticSelfOcrGarble(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val hasDiacritic = trimmed.any { it in EUROPEAN_DIACRITICS }

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true

        val shortGarbleCount = tokens.count { token ->
            val letters = token.filter { it.isLetter() }
            letters.length in 1..2 && !SHORT_VALID_WORDS.contains(letters.lowercase(Locale.US))
        }
        val shortGarbleRatio = shortGarbleCount.toFloat() / tokens.size.toFloat()

        if (hasDiacritic && shortGarbleCount > 0) return true
        if (tokens.size >= 2 && shortGarbleRatio > 0.30f) return true
        return false
    }

    /**
     * Pre-translation OCR repair for mid-word casing and split-word artifacts
     * before passing text to PatchManager or ML Kit.
     */
    fun normalizeOcrArtifacts(text: String): String {
        if (text.isBlank()) return ""
        var s = text.trim()

        // Split-word and spacing repairs
        s = s.replace(Regex("(?i)\\bBenj\\s+amin\\b"), "Benjamin")
        s = s.replace(Regex("(?i)\\bscalpe\\s+l\\b"), "scalpel")
        s = s.replace(Regex("(?i)\\bdon'tjudge\\b"), "don't judge")
        s = s.replace(Regex("(?i)\\bf\\s+inally\\b"), "finally")
        s = s.replace(Regex("(?i)\\bfina\\s+lly\\b"), "finally")
        s = s.replace(Regex("(?i)\\bdi\\s+ana\\b"), "Diana")
        s = s.replace(Regex("(?i)\\bburn\\s+wood\\b"), "Burnwood")
        s = s.replace(Regex("(?i)\\bi\\s+t's\\b"), "it's")
        s = s.replace(Regex("(?i)\\bice-\\s*cream\\b"), "ice-cream")
        s = s.replace(Regex("(?i)\\bqur\\b"), "our")
        s = s.replace(Regex("(?i)\\bluck(\\d+)\\b"), "luck, $1")
        s = s.replace(Regex("(?i)\\busedthe\\b"), "used the")
        s = s.replace(Regex("(?i)\\bafterall\\b"), "After all")

        // Remove stray underscores (e.g. cut off_ -> cut off)
        s = s.replace(Regex("_+"), " ")

        // Generic detached trailing-letter heal: collapse [A-Za-z]{4,} + single non-a/i letter -> glued
        s = s.replace(Regex("\\b([A-Za-z]{4,})\\s+([b-hj-zB-HJ-Z])\\b"), "$1$2")

        // Normalize colloquial contractions & slang for clean ML Kit translation
        s = s.replace(Regex("(?i)\\boutta\\b"), "out of")
        s = s.replace(Regex("(?i)\\bgonna\\b"), "going to")
        s = s.replace(Regex("(?i)\\bwanna\\b"), "want to")
        s = s.replace(Regex("(?i)\\bgotta\\b"), "got to")
        s = s.replace(Regex("(?i)\\bkinda\\b"), "kind of")

        // Mid-word casing & OCR character confusion fixes
        s = s.replace(Regex("(?i)\\bwilL\\b"), "will")
        s = s.replace(Regex("(?i)\\bWOuLd\\b"), "would")
        s = s.replace(Regex("(?i)\\bcoMmunication\\b"), "communication")
        s = s.replace(Regex("(?i)\\baccOunt\\b"), "account")
        s = s.replace(Regex("(?i)\\boWn\\b"), "own")
        s = s.replace(Regex("(?i)\\bdeaLt\\b"), "dealt")
        s = s.replace(Regex("(?i)\\beXposed\\b"), "exposed")
        s = s.replace(Regex("(?i)\\bW[ìi]red\\b"), "wired")

        s = s.replace(Regex("\\s+"), " ")

        return s.trim()
    }

    /**
     * Returns true if newText is a shorter prefix or subset (>= 75% token overlap)
     * of currently displayed text, preventing partial dialogue overwrite flickers.
     */
    fun isShorterSubsetOrPrefix(newText: String, currentText: String): Boolean {
        val n = newText.trim().lowercase(Locale.US)
        val c = currentText.trim().lowercase(Locale.US)
        if (n.isEmpty() || c.isEmpty() || n == c) return false
        if (n.length >= c.length) return false

        if (c.startsWith(n) || c.contains(n)) return true

        val newTokens = n.split(Regex("\\s+")).filter { it.isNotBlank() }
        val currTokens = c.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (newTokens.isEmpty()) return false

        val matchCount = newTokens.count { currTokens.contains(it) }
        val overlapRatio = matchCount.toFloat() / newTokens.size.toFloat()
        return overlapRatio >= 0.75f
    }

    fun isNoiseOrPrompt(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (isDiacriticSelfOcrGarble(trimmed)) return true

        // Reject self-app name or UI text
        if (trimmed.contains("GameLens", ignoreCase = true) || trimmed.contains("Translator", ignoreCase = true)) {
            return true
        }

        // Standalone single letters (except valid English words 'a' or 'i')
        if (trimmed.length == 1) {
            return !trimmed.equals("a", ignoreCase = true) && !trimmed.equals("i", ignoreCase = true)
        }

        // Digits / score / counters like "12/30", "100", "5-2"
        if (STANDALONE_COUNTER_REGEX.matches(trimmed)) return true

        // Must contain at least one letter
        if (!trimmed.any { it.isLetter() }) return true

        // Discard short < 4 letter strings unless they are recognized short lowercase/mixed words, never 1-3 char all-caps acronyms (IED, TED, ASS, CRE, ND)
        val lettersOnly = trimmed.filter { it.isLetter() }
        if (lettersOnly.length < 4) {
            val isAllCaps = lettersOnly.length >= 2 && lettersOnly == lettersOnly.uppercase(Locale.US)
            val lower = lettersOnly.lowercase(Locale.US)
            if (isAllCaps || !SHORT_VALID_WORDS.contains(lower)) {
                if (com.zlatan.gamelens.patch.PatchManager.lookup(trimmed) == null) {
                    return true
                }
            }
        }

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
     * Rejects garbled document noise, mixed alphanumeric gibberish (e.g. M0OC, pernalone5.07),
     * and long runs of broken consonants (> 35% of tokens).
     */
    fun isGarbledDocumentNoise(text: String): Boolean {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true

        var garbledCount = 0
        for (token in tokens) {
            val clean = token.filter { it.isLetterOrDigit() }
            if (clean.isEmpty()) continue

            val hasDigit = clean.any { it.isDigit() }
            val hasLetter = clean.any { it.isLetter() }

            // Mixed letters & digits (e.g. M0OC, pernalone5.07, TDDC94) that aren't known controllers/anchors
            val isMixedAlphaNum = hasDigit && hasLetter && !isKnownButtonOrAnchor(clean)

            // Consonant noise or vowel-less tokens of length >= 3 (e.g. CRE, TDDC, Fattrctt)
            val letters = clean.filter { it.isLetter() }
            val isConsonantNoise = if (letters.length >= 3) {
                val vowels = letters.count { it in "aeiouyAEIOUY" }
                vowels == 0 || hasExcessiveConsonantRun(letters)
            } else false

            if (isMixedAlphaNum || isConsonantNoise) {
                garbledCount++
            }
        }

        val garbledRatio = garbledCount.toFloat() / tokens.size.toFloat()
        return garbledRatio > 0.35f
    }

    private fun hasExcessiveConsonantRun(s: String): Boolean {
        var run = 0
        for (c in s.lowercase(Locale.US)) {
            if (c in "aeiouy") {
                run = 0
            } else {
                run++
                if (run >= 4) return true // 4+ consecutive consonants like 'Fattrctt'
            }
        }
        return false
    }

    private fun isKnownButtonOrAnchor(s: String): Boolean {
        val upper = s.uppercase(Locale.US)
        return upper in setOf("L1", "L2", "L3", "R1", "R2", "R3", "LB", "RB", "LT", "RT", "47")
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

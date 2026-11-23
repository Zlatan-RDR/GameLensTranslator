package com.zlatan.gamelens.ocr

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.abs

/**
 * High-speed 4x4-strided luminance pixel-diff gate for cropped subtitle ROI (~1.5ms on Snapdragon 8s Gen 4).
 * Compares integer luminance across a 4x4 subsampled grid and gates downstream OCR if delta < 0.45%.
 */
class FastPixelDiffGate(
    private val stride: Int = 4,
    private val diffThresholdPercent: Float = 0.0045f // 0.45% delta (lowered from 2.5% to detect short 2-3 word subtitles)
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
     * Returns true if pixel delta >= 0.45% or dimensions changed.
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
    val HUD_ACTION_STEMS = setOf(
        "drop", "drag", "enter", "fnter", "ente", "subdue", "hide", "dump", "open", "close",
        "take", "pick", "use", "talk", "interact", "disguise", "sguise", "disauise", "activate", "activat"
    )
    val EUROPEAN_DIACRITICS = setOf(
        'ä', 'ö', 'ü', 'š', 'ì', 'ç', 'ł', 'ž', 'ć', '„',
        'é', 'è', 'à', 'â', 'ê', 'î', 'ô', 'û', 'ñ', '“', '”', '»', '«', '›', '‹'
    )

    /**
     * Rejects Arabic-as-Latin OCR garble (e.g. "Jlal ce Blg .äyul gUloglaall")
     * produced when ML Kit mistakenly scans Arabic overlay text with Latin models.
     */
    fun isDiacriticSelfOcrGarble(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        // Reject broken punctuation/parenthesis fragments like "...(Self-Read"
        if (Regex("^\\.{2,}\\s*\\(").containsMatchIn(trimmed)) return true

        // Reject any OCR string containing >= 2 non-ASCII European diacritic characters anywhere
        val diacriticCount = trimmed.count { it in EUROPEAN_DIACRITICS }
        if (diacriticCount >= 2) return true

        val hasDiacritic = diacriticCount > 0
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
        s = s.replace(Regex("(?i)\\bel\\s+iminate\\b"), "eliminate")
        s = s.replace(Regex("(?i)\\bthewitnesses\\b"), "the witnesses")
        s = s.replace(Regex("(?i)\\bca\\s+lmly\\b"), "calmly")
        s = s.replace(Regex("(?i)\\bSilverbal\\s+ler\\b"), "Silverballer")
        s = s.replace(Regex("(?i)\\bofflimits\\b"), "off limits")
        s = s.replace(Regex("(?i)\\byoursilenced\\b"), "your silenced")
        s = s.replace(Regex("(?i)\\ballowedto\\b"), "allowed to")
        s = s.replace(Regex("(?i)\\boninstinct\\b"), "on instinct")
        s = s.replace(Regex("(?i)\\bpaintdry\\b"), "paint dry")

        // Remove stray underscores (e.g. cut off_ -> cut off)
        s = s.replace(Regex("_+"), " ")

        // Generic detached trailing-letter heal: collapse [A-Za-z]{4,} + single non-a/i letter -> glued
        s = s.replace(Regex("\\b([A-Za-z]{4,})\\s+([b-hj-zB-HJ-Z])\\b"), "$1$2")

        // Collapse single/double-letter OCR splits inside words: ca Lmly -> calmly, ho Ld -> hold
        s = Regex("\\b([a-z]{2,4})\\s+([A-Z][a-z]{2,})\\b").replace(s) { match ->
            match.groupValues[1] + match.groupValues[2].lowercase(Locale.US)
        }

        // Collapse suffix splits: suppli ed -> supplied, confusi on -> confusion, discret ion -> discretion
        s = s.replace(Regex("(?i)\\b([A-Za-z]{4,})\\s+(ed|on|ion|ly|ing|ment|al|er|es)\\b"), "$1$2")

        // Normalize colloquial contractions & slang for clean ML Kit translation
        s = s.replace(Regex("(?i)\\boutta\\b"), "out of")
        s = s.replace(Regex("(?i)\\bgonna\\b"), "going to")
        s = s.replace(Regex("(?i)\\bwanna\\b"), "want to")
        s = s.replace(Regex("(?i)\\bgotta\\b"), "got to")
        s = s.replace(Regex("(?i)\\bkinda\\b"), "kind of")

        // Split common OCR-glued gameplay pairs
        s = s.replace(Regex("(?i)\\btryto\\b"), "try to")
        s = s.replace(Regex("(?i)\\btoenter\\b"), "to enter")
        s = s.replace(Regex("(?i)\\bwilltry\\b"), "will try")
        s = s.replace(Regex("(?i)\\byouto\\b"), "you to")
        s = s.replace(Regex("(?i)\\bclosecombat\\b"), "close combat")
        s = s.replace(Regex("(?i)\\bit'sthe\\b"), "it's the")
        s = s.replace(Regex("(?i)\\banyt\\s+ime\\b"), "anytime")
        s = s.replace(Regex("(?i)\\bcli\\s+ent\\b"), "client")
        s = s.replace(Regex("(?i)\\bwillbring\\b"), "will bring")
        s = s.replace(Regex("(?i)\\bGettingintothe\\b"), "Getting into the")
        s = s.replace(Regex("(?i)\\bfromenemy\\b"), "from enemy")
        s = s.replace(Regex("(?i)\\btokeen\\s*lyperceive\\b"), "to keenly perceive")
        s = s.replace(Regex("(?i)\\bmanwill\\b"), "man will")
        s = s.replace(Regex("(?i)\\bItell\\b"), "I tell")
        s = s.replace(Regex("(?i)\\bislike\\b"), "is like")
        s = s.replace(Regex("(?i)\\bwereless\\b"), "were less")
        s = s.replace(Regex("(?i)\\bwhatshe's+\\b"), "what she's")
        s = s.replace(Regex("(?i)\\byourinstinct\\b"), "your instinct")
        s = s.replace(Regex("(?i)\\bbelooking\\b"), "be looking")
        s = s.replace(Regex("(?i)\\bComeon\\b"), "Come on")
        s = s.replace(Regex("(?i)\\bhewon't\\b"), "he won't")
        s = s.replace(Regex("(?i)\\bwakingup\\b"), "waking up")
        s = s.replace(Regex("(?i)\\btheagency\\b"), "the agency")
        s = s.replace(Regex("(?i)\\blastaugust\\b"), "last August")

        // Exact split word repairs
        s = s.replace(Regex("(?i)\\bwi\\s+tnesses\\b"), "witnesses")
        s = s.replace(Regex("(?i)\\bdisgui\\s+se\\b"), "disguise")
        s = s.replace(Regex("(?i)\\bsllip\\b"), "slip")
        s = s.replace(Regex("(?i)\\bunsi\\s+lenced\\b"), "unsilenced")
        s = s.replace(Regex("(?i)\\balertyour\\b"), "alert your")
        s = s.replace(Regex("(?i)\\basafe\\b"), "a safe")
        s = s.replace(Regex("(?i)\\bmysole\\b"), "my sole")
        s = s.replace(Regex("(?m)^Ithe\\b"), "The")

        // Mid-word casing & OCR character confusion fixes
        s = s.replace(Regex("(?i)\\bSiLverballer\\b"), "Silverballer")
        s = s.replace(Regex("(?i)\\bkilL\\b"), "kill")
        s = s.replace(Regex("(?i)\\byOu\\b"), "you")
        s = s.replace(Regex("(?i)\\bSOon\\b"), "soon")
        s = s.replace(Regex("(?i)\\blLike\\b"), "like")
        s = s.replace(Regex("(?i)\\bfinalLy\\b"), "finally")
        s = s.replace(Regex("(?i)\\bI'lL\\b"), "I'll")
        s = s.replace(Regex("(?i)\\bwilL\\b"), "will")
        s = s.replace(Regex("(?i)\\bWOuLd\\b"), "would")
        s = s.replace(Regex("(?i)\\bcoMmunication\\b"), "communication")
        s = s.replace(Regex("(?i)\\baccOunt\\b"), "account")
        s = s.replace(Regex("(?i)\\boWn\\b"), "own")
        s = s.replace(Regex("(?i)\\bdeaLt\\b"), "dealt")
        s = s.replace(Regex("(?i)\\beXposed\\b"), "exposed")
        s = s.replace(Regex("(?i)\\bW[ìi]red\\b"), "wired")

        // Generic mid-word stray uppercase normalization in mostly lowercase words
        val wordTokens = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        s = wordTokens.joinToString(" ") { token ->
            val letters = token.filter { it.isLetter() }
            if (letters.length >= 3 && !token.all { it.isLetter() && it.isUpperCase() }) {
                if (token.first().isLowerCase() && token.any { it.isUpperCase() }) {
                    token.lowercase(Locale.US)
                } else if (letters.length >= 3 && letters[0].isUpperCase() && letters[1].isUpperCase() && letters.drop(2).all { it.isLowerCase() }) {
                    letters[0].uppercase(Locale.US) + token.drop(1).lowercase(Locale.US)
                } else if (token.contains("'") && token.equals("i'll", ignoreCase = true)) {
                    "I'll"
                } else {
                    token
                }
            } else {
                token
            }
        }

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

    /**
     * Rejects new OCR candidate if it is a credit-polluted superset of currently displayed clean English text
     * (e.g. "Exactly. Good luck 47.RROBERTRow" after "Exactly. Good Luck 47.",
     *  or "ED DY Sir. Yeah it's an ice-cream truck..." after "Yeah it's an ice-cream truck...").
     */
    fun isCreditPollutedSuperset(newText: String, currentText: String): Boolean {
        val n = newText.trim().lowercase(Locale.US)
        val c = currentText.trim().lowercase(Locale.US)
        if (n.isEmpty() || c.isEmpty() || n == c) return false
        if (n.length <= c.length) return false

        val cWords = c.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val nWords = n.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (cWords.size < 2 || nWords.size <= cWords.size) return false

        // Check contiguous substring match ignoring punctuation
        val cClean = cWords.joinToString(" ")
        val nClean = nWords.joinToString(" ")
        if (nClean.contains(cClean)) return true

        // Check sliding window for >= 80% word match as a contiguous span
        val windowSize = cWords.size
        val minMatch = (cWords.size * 0.80f).toInt().coerceAtLeast(2)
        for (i in 0..(nWords.size - windowSize)) {
            var matches = 0
            for (j in 0 until windowSize) {
                if (nWords[i + j] == cWords[j]) {
                    matches++
                }
            }
            if (matches >= minMatch) {
                return true
            }
        }
        return false
    }

    fun isNoiseOrPrompt(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (isDiacriticSelfOcrGarble(trimmed)) return true
        if (Regex("^\\.{2,}\\s*\\(").containsMatchIn(trimmed)) return true

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

        // Reject single-word non-patch strings whose stripped alphabetic core is <= 4 chars or matches HUD action stems
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size == 1) {
            val coreLetters = tokens[0].filter { it.isLetter() }.lowercase(Locale.US)
            val isShortOrHud = coreLetters.length <= 4 || HUD_ACTION_STEMS.contains(coreLetters)
            if (isShortOrHud && com.zlatan.gamelens.patch.PatchManager.lookup(trimmed) == null) {
                return true
            }
        }

        // Reject 2-word non-sentence HUD banners matching ^A\s+[A-Za-z]{4,12}$ without terminal punctuation unless in patch
        if (Regex("(?i)^A\\s+[A-Za-z]{4,12}$").matches(trimmed)) {
            val hasTerminalPunctuation = trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
            if (!hasTerminalPunctuation && com.zlatan.gamelens.patch.PatchManager.lookup(trimmed) == null) {
                return true
            }
        }

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
     * Enforces exact word count match and rejects matches where any differing alphabetic word
     * of length >= 4 has Levenshtein distance > 1.
     */
    fun isSimilar(s1: String, s2: String, threshold: Float = 0.88f): Boolean {
        if (s1.equals(s2, ignoreCase = true)) return true
        val n1 = s1.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), "")
        val n2 = s2.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), "")
        if (n1 == n2) return true
        if (n1.isEmpty() || n2.isEmpty()) return false

        val w1 = n1.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val w2 = n2.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w1.size != w2.size) return false

        for (i in w1.indices) {
            val word1 = w1[i]
            val word2 = w2[i]
            if (word1 != word2) {
                val wordDist = levenshtein(word1, word2)
                if (wordDist > 1) return false
            }
        }

        val maxLen = maxOf(n1.length, n2.length)
        if (maxLen == 0) return true
        val dist = levenshtein(n1, n2)
        val similarity = 1.0f - (dist.toFloat() / maxLen.toFloat())
        return similarity >= threshold
    }

    fun levenshtein(s1: String, s2: String): Int {
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

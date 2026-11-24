package com.zlatan.gamelens.ocr

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.ln

object EnglishWordRepairer {

    private const val TAG = "EnglishWordRepairer"
    private const val ASSET_FILE = "en_dict.txt"

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val CONTRACTION_LEFT_REGEX = Regex("^([A-Za-z]+(?:n['’]t|['’](?:s|ve|re|ll|d|m)))([A-Za-z]+)$", RegexOption.IGNORE_CASE)
    private val CONTRACTION_RIGHT_REGEX = Regex("^([A-Za-z]+)(he['’]s|she['’]s|it['’]s|that['’]s|what['’]s|there['’]s|who['’]s|where['’]s|won['’]t|can['’]t)$", RegexOption.IGNORE_CASE)
    private val CAMEL_CASE_REGEX = Regex("^([A-Z][a-z]{2,})([A-Z][a-z]+|[a-z]{2,})$")

    @Volatile
    private var isInitialized = false

    private val dictionaryMap = HashMap<String, Int>(25000)

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                context.assets.open(ASSET_FILE).use { stream ->
                    loadDictionary(stream)
                }
                com.zlatan.gamelens.diag.DiagnosticLogger.log("EnglishWordRepairer: loaded ${dictionaryMap.size} words")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load $ASSET_FILE from assets: ${t.message}")
                com.zlatan.gamelens.diag.DiagnosticLogger.log("EnglishWordRepairer: failed to load assets: ${t.message}")
            }
        }
    }

    @Synchronized
    fun loadDictionary(inputStream: InputStream) {
        dictionaryMap.clear()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
            var rank = 0
            for (line in lines) {
                val word = line.trim().lowercase(Locale.US)
                if (word.isNotEmpty() && !dictionaryMap.containsKey(word)) {
                    dictionaryMap[word] = rank++
                }
            }
        }
        isInitialized = true
        try {
            Log.i(TAG, "Loaded ${dictionaryMap.size} words into EnglishWordRepairer dictionary.")
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun loadDictionary(words: List<String>) {
        dictionaryMap.clear()
        var rank = 0
        for (w in words) {
            val word = w.trim().lowercase(Locale.US)
            if (word.isNotEmpty() && !dictionaryMap.containsKey(word)) {
                dictionaryMap[word] = rank++
            }
        }
        isInitialized = true
    }

    fun isWord(word: String): Boolean {
        return dictionaryMap.containsKey(word.lowercase(Locale.US))
    }

    fun getWordRank(word: String): Int {
        return dictionaryMap[word.lowercase(Locale.US)] ?: 999999
    }

    /**
     * Measures the ratio of recognizable English dictionary words in the given text.
     */
    fun dictionaryCoverage(text: String): Float {
        val tokens = text.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return 0f
        var validCount = 0
        for (t in tokens) {
            val clean = t.filter { it.isLetter() }.lowercase(Locale.US)
            if (clean.isEmpty()) continue
            if (dictionaryMap.containsKey(clean)) {
                validCount++
            }
        }
        return validCount.toFloat() / tokens.size.toFloat()
    }

    private data class TokenParts(val leadingPunct: String, val core: String, val trailingPunct: String)

    private fun splitPunctuation(token: String): TokenParts {
        var start = 0
        while (start < token.length && !token[start].isLetterOrDigit() && token[start] != '\'') {
            start++
        }
        var end = token.length
        while (end > start && !token[end - 1].isLetterOrDigit() && token[end - 1] != '\'') {
            end--
        }
        val leading = token.substring(0, start)
        val core = if (start < end) token.substring(start, end) else ""
        val trailing = token.substring(end)
        return TokenParts(leading, core, trailing)
    }

    /**
     * Fast, universal offline English text repairer.
     * Pass A: Adjacent Split-Word Rejoining
     * Pass B: Viterbi / DP Glued-Word & Contraction Segmentation
     * Pass C: Stray Mixed-Case Cleanup
     */
    fun repair(text: String): String {
        if (text.isBlank() || dictionaryMap.isEmpty()) return text

        val rawTokens = text.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) return text

        // Pass A — Adjacent Split-Word Rejoining
        val rejoinedTokens = ArrayList<String>(rawTokens.size)
        var i = 0
        while (i < rawTokens.size) {
            if (i + 1 < rawTokens.size) {
                val p1 = splitPunctuation(rawTokens[i])
                val p2 = splitPunctuation(rawTokens[i + 1])
                if (p1.core.isNotEmpty() && p2.core.isNotEmpty() && p1.trailingPunct.isEmpty() && p2.leadingPunct.isEmpty()) {
                    val merged = (p1.core + p2.core).lowercase(Locale.US)
                    val w1Lower = p1.core.lowercase(Locale.US)
                    val w2Lower = p2.core.lowercase(Locale.US)

                    // Merge ONLY IF merged is a valid word >= 5 chars AND at least one component is NOT a valid word, OR known split (e.g. steal thy -> stealthy)
                    if (merged.length >= 5 && dictionaryMap.containsKey(merged)) {
                        if (!dictionaryMap.containsKey(w1Lower) || !dictionaryMap.containsKey(w2Lower) || (w1Lower == "steal" && w2Lower == "thy")) {
                            val mergedCasing = if (p1.core[0].isUpperCase()) {
                                merged.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                            } else {
                                merged
                            }
                            rejoinedTokens.add(p1.leadingPunct + mergedCasing + p2.trailingPunct)
                            i += 2
                            continue
                        }
                    }
                }
            }
            rejoinedTokens.add(rawTokens[i])
            i++
        }

        // Pass B — DP Glued-Word Segmentation & Contractions + Pass C: Mixed-case cleanup
        val finalTokens = ArrayList<String>(rejoinedTokens.size)
        for (idx in rejoinedTokens.indices) {
            val tok = rejoinedTokens[idx]
            val p = splitPunctuation(tok)
            val core = p.core
            if (core.isEmpty()) {
                finalTokens.add(tok)
                continue
            }

            val lower = core.lowercase(Locale.US)

            // If word already in dictionary: check Pass C (stray mixed-case) and keep
            if (dictionaryMap.containsKey(lower)) {
                var cleanedCore = core
                // Pass C: Stray mixed-case (e.g. LoW -> low, LiKe -> like, but not ALL-CAPS acronym and not TitleCase)
                if (!core.all { it.isUpperCase() } && !(core[0].isUpperCase() && core.substring(1).all { it.isLowerCase() }) && !core.all { it.isLowerCase() }) {
                    cleanedCore = lower
                }
                finalTokens.add(p.leadingPunct + cleanedCore + p.trailingPunct)
                continue
            }

            // Contraction gluing at end of left piece: e.g. Don't act -> Don't act
            val mCont1 = CONTRACTION_LEFT_REGEX.matchEntire(core)
            if (mCont1 != null) {
                val p1 = mCont1.groupValues[1]
                val p2 = mCont1.groupValues[2]
                val p1Norm = p1.lowercase(Locale.US).replace('’', '\'')
                val p2Norm = p2.lowercase(Locale.US)
                val p1Base = p1Norm.split('\'')[0]
                if ((dictionaryMap.containsKey(p1Base) || p1Norm in KNOWN_CONTRACTIONS) && dictionaryMap.containsKey(p2Norm)) {
                    finalTokens.add(p.leadingPunct + p1 + " " + p2 + p.trailingPunct)
                    continue
                }
            }

            // Contraction gluing at start of right piece: e.g. sayshe's -> says he's
            val mCont2 = CONTRACTION_RIGHT_REGEX.matchEntire(core)
            if (mCont2 != null) {
                val p1 = mCont2.groupValues[1]
                val p2 = mCont2.groupValues[2]
                if (dictionaryMap.containsKey(p1.lowercase(Locale.US))) {
                    finalTokens.add(p.leadingPunct + p1 + " " + p2 + p.trailingPunct)
                    continue
                }
            }

            // CamelCase gluing: e.g. Maybewe -> Maybe we
            val mCamel = CAMEL_CASE_REGEX.matchEntire(core)
            if (mCamel != null) {
                val p1 = mCamel.groupValues[1]
                val p2 = mCamel.groupValues[2]
                if (dictionaryMap.containsKey(p1.lowercase(Locale.US)) && dictionaryMap.containsKey(p2.lowercase(Locale.US))) {
                    finalTokens.add(p.leadingPunct + p1 + " " + p2 + p.trailingPunct)
                    continue
                }
            }

            // 4-letter a/i split: e.g. akid -> a kid (only when akid is NOT in dictionary)
            if (lower.length == 4 && (lower[0] == 'a' || lower[0] == 'i') && dictionaryMap.containsKey(lower.substring(1))) {
                finalTokens.add(p.leadingPunct + core[0] + " " + core.substring(1) + p.trailingPunct)
                continue
            }

            // Skip pure proper nouns: TitleCase token not at index 0 without internal gluing
            // Do NOT skip if it follows sentence-ending punctuation (., !, ?) so "Salt! Shameon you" repairs cleanly
            val followsPunctuation = idx > 0 && run {
                val prev = rejoinedTokens[idx - 1]
                prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?") ||
                        prev.endsWith(".\"") || prev.endsWith("!\"") || prev.endsWith("?\"") ||
                        prev.endsWith(".'") || prev.endsWith("!'") || prev.endsWith("?'")
            }
            if (idx > 0 && !followsPunctuation && core[0].isUpperCase() && core.substring(1).all { it.isLowerCase() }) {
                finalTokens.add(tok)
                continue
            }

            // Possessive gluing: e.g. ofsecurity's -> of security's
            if ((core.endsWith("'s") || core.endsWith("’s")) && lower !in KNOWN_CONTRACTIONS) {
                val stem = core.dropLast(2)
                val suffix = core.takeLast(2)
                val stemLower = stem.lowercase(Locale.US)
                if (dictionaryMap.containsKey(stemLower)) {
                    finalTokens.add(p.leadingPunct + core + p.trailingPunct)
                    continue
                }
                var stemSplit: List<String>? = null
                for (sIdx in 1 until stemLower.length) {
                    val w1 = stemLower.substring(0, sIdx)
                    val w2 = stemLower.substring(sIdx)
                    if ((w1.length == 1 && w1 != "a" && w1 != "i") || (w2.length == 1 && w2 != "a" && w2 != "i")) continue
                    if (maxOf(w1.length, w2.length) < 3) continue
                    val r1 = dictionaryMap[w1] ?: continue
                    val r2 = dictionaryMap[w2] ?: continue
                    if (r1 <= 16000 && r2 <= 16000) {
                        stemSplit = listOf(w1, w2)
                        break
                    }
                }
                if (stemSplit != null) {
                    val p1 = if (stem[0].isUpperCase()) stemSplit[0].replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() } else stemSplit[0]
                    finalTokens.add(p.leadingPunct + p1 + " " + stemSplit[1] + suffix + p.trailingPunct)
                    continue
                }
            }

            if (lower.length < 5) {
                finalTokens.add(tok)
                continue
            }

            // DP Segmentation: find optimal split into 2 or 3 words
            val n = lower.length
            var bestCost = 1e9
            var bestSplit: List<String>? = null

            // 2-word split
            for (splitIdx in 1 until n) {
                val w1 = lower.substring(0, splitIdx)
                val w2 = lower.substring(splitIdx)
                if ((w1.length == 1 && w1 != "a" && w1 != "i") || (w2.length == 1 && w2 != "a" && w2 != "i")) continue
                if (maxOf(w1.length, w2.length) < 3) continue
                val r1 = dictionaryMap[w1] ?: continue
                val r2 = dictionaryMap[w2] ?: continue
                if (r1 <= 16000 && r2 <= 16000) {
                    val cost = ln(r1 + 10.0) + ln(r2 + 10.0) + 3.6
                    if (cost < bestCost) {
                        bestCost = cost
                        bestSplit = listOf(w1, w2)
                    }
                }
            }

            // 3-word split
            for (s1 in 1 until n - 1) {
                for (s2 in s1 + 1 until n) {
                    val w1 = lower.substring(0, s1)
                    val w2 = lower.substring(s1, s2)
                    val w3 = lower.substring(s2)
                    if ((w1.length == 1 && w1 != "a" && w1 != "i") ||
                        (w2.length == 1 && w2 != "a" && w2 != "i") ||
                        (w3.length == 1 && w3 != "a" && w3 != "i")) continue
                    if (maxOf(w1.length, w2.length, w3.length) < 3) continue
                    val r1 = dictionaryMap[w1] ?: continue
                    val r2 = dictionaryMap[w2] ?: continue
                    val r3 = dictionaryMap[w3] ?: continue
                    if (r1 <= 16000 && r2 <= 16000 && r3 <= 16000) {
                        val cost = ln(r1 + 10.0) + ln(r2 + 10.0) + ln(r3 + 10.0) + 7.2
                        if (cost < bestCost) {
                            bestCost = cost
                            bestSplit = listOf(w1, w2, w3)
                        }
                    }
                }
            }

            if (bestSplit != null) {
                val pieces = bestSplit.toMutableList()
                if (core[0].isUpperCase()) {
                    pieces[0] = pieces[0].replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                }
                finalTokens.add(p.leadingPunct + pieces.joinToString(" ") + p.trailingPunct)
            } else {
                finalTokens.add(tok)
            }
        }

        return finalTokens.joinToString(" ")
    }

    private val KNOWN_CONTRACTIONS = setOf(
        "don't", "won't", "can't", "it's", "he's", "she's", "that's", "what's",
        "there's", "who's", "where's", "let's", "you're", "they're", "we're",
        "i'm", "i've", "you've", "we've", "they've", "i'll", "you'll", "he'll",
        "she'll", "we'll", "they'll", "i'd", "you'd", "he'd", "she'd", "we'd", "they'd"
    )
}

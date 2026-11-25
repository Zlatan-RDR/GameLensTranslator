package com.zlatan.gamelens.translation

import java.util.Locale

enum class Gender {
    UNSPECIFIED,
    FEMININE,
    MASCULINE
}

data class GlossaryEntry(
    val term: String,
    val target: String,
    val gender: Gender = Gender.UNSPECIFIED
)

/**
 * Per-game glossary and character gender context manager.
 * Provides dual-engine support:
 * 1. Numeric placeholder protection ([9001], [9002]) for Machine Translation (ML Kit / Free Google)
 * 2. System prompt rule formatting for Cloud/Local AI models (OpenAI, Claude, Gemini, Ollama)
 */
object GameGlossaryManager {

    private val GENDER_FEMININE_REGEX = Regex("(?i)\\[(أنثى|f|female|مؤنث)\\]")
    private val GENDER_MASCULINE_REGEX = Regex("(?i)\\[(ذكر|m|male|مذكر)\\]")
    private val GENDER_ANY_TAG_REGEX = Regex("(?i)\\[(أنثى|f|female|مؤنث|ذكر|m|male|مذكر)\\]")

    /**
     * Parses multiline glossary string supporting multiple syntax styles:
     * - Term = Target [أنثى]
     * - Term -> Target [ذكر]
     * - Term : Target
     */
    fun parse(rawText: String?): List<GlossaryEntry> {
        if (rawText.isNullOrBlank()) return emptyList()

        val lines = rawText.split(Regex("[\\r\\n;]+"))
        val result = mutableListOf<GlossaryEntry>()
        val seenTerms = mutableSetOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            val delimiterIndex = when {
                line.contains("->") -> line.indexOf("->") to 2
                line.contains("=") -> line.indexOf("=") to 1
                line.contains(":") -> line.indexOf(":") to 1
                else -> -1 to 0
            }

            if (delimiterIndex.first <= 0) {
                // Support standalone character tag format: e.g. "Diana [أنثى]", "Victoria [female]"
                if (GENDER_ANY_TAG_REGEX.containsMatchIn(line)) {
                    val gender = if (GENDER_FEMININE_REGEX.containsMatchIn(line)) Gender.FEMININE else Gender.MASCULINE
                    val clean = GENDER_ANY_TAG_REGEX.replace(line, "").trim()
                    if (clean.isNotEmpty() && !seenTerms.contains(clean.lowercase(Locale.US))) {
                        seenTerms.add(clean.lowercase(Locale.US))
                        result.add(GlossaryEntry(clean, clean, gender))
                    }
                }
                continue
            }

            val rawTerm = line.substring(0, delimiterIndex.first).trim()
            val rawTarget = line.substring(delimiterIndex.first + delimiterIndex.second).trim()

            if (rawTerm.isEmpty() || rawTarget.isEmpty()) continue

            val gender = when {
                GENDER_FEMININE_REGEX.containsMatchIn(rawTarget) || GENDER_FEMININE_REGEX.containsMatchIn(rawTerm) -> Gender.FEMININE
                GENDER_MASCULINE_REGEX.containsMatchIn(rawTarget) || GENDER_MASCULINE_REGEX.containsMatchIn(rawTerm) -> Gender.MASCULINE
                else -> Gender.UNSPECIFIED
            }

            val cleanTarget = GENDER_ANY_TAG_REGEX.replace(rawTarget, "").trim()
            val cleanTerm = GENDER_ANY_TAG_REGEX.replace(rawTerm, "").trim()

            if (cleanTerm.isNotEmpty() && cleanTarget.isNotEmpty() && !seenTerms.contains(cleanTerm.lowercase(Locale.US))) {
                seenTerms.add(cleanTerm.lowercase(Locale.US))
                result.add(GlossaryEntry(cleanTerm, cleanTarget, gender))
            }
        }

        return result
    }

    private val COMMON_FEMALE_TRANSLITERATIONS = mapOf(
        "victoria" to "فيكتوريا",
        "diana" to "ديانا",
        "sarah" to "سارة",
        "mary" to "ماري",
        "elena" to "إيلينا",
        "lara" to "لارا"
    )

    /**
     * Extracts all female character names (English terms and Arabic targets) for targeted gender agreement.
     */
    fun getFemaleNames(entries: List<GlossaryEntry>): Set<String> {
        val result = mutableSetOf<String>()
        for (entry in entries) {
            if (entry.gender == Gender.FEMININE) {
                if (entry.term.isNotBlank()) {
                    val t = entry.term.trim()
                    result.add(t)
                    COMMON_FEMALE_TRANSLITERATIONS[t.lowercase(Locale.US)]?.let { result.add(it) }
                }
                if (entry.target.isNotBlank()) {
                    val tg = entry.target.trim()
                    result.add(tg)
                    COMMON_FEMALE_TRANSLITERATIONS[tg.lowercase(Locale.US)]?.let { result.add(it) }
                }
            }
        }
        return result
    }

    /**
     * Protects glossary terms before sending to Machine Translation engines (Google Free / ML Kit)
     * by replacing them with numeric placeholders [9001], [9002], etc.
     * Sorts terms longest first to ensure multi-word phrases match before substrings.
     */
    fun protectBeforeMt(text: String, entries: List<GlossaryEntry>): Pair<String, Map<String, String>> {
        if (text.isBlank() || entries.isEmpty()) return text to emptyMap()

        // Longest terms first
        val sorted = entries.sortedByDescending { it.term.length }
        var protectedText = text
        val placeholderMap = mutableMapOf<String, String>()

        var index = 9001
        for (entry in sorted) {
            val termRegex = Regex("(?i)\\b${Regex.escape(entry.term)}\\b")
            if (termRegex.containsMatchIn(protectedText)) {
                val placeholder = "[$index]"
                protectedText = termRegex.replace(protectedText, placeholder)
                placeholderMap[placeholder] = entry.target
                index++
            }
        }

        return protectedText to placeholderMap
    }

    /**
     * Restores glossary targets into translated text with tolerance for MT bracket spacing ([ 9001 ]).
     */
    fun restoreAfterMt(translatedText: String, placeholders: Map<String, String>): String {
        if (translatedText.isBlank() || placeholders.isEmpty()) return translatedText

        var restored = translatedText
        for ((placeholder, target) in placeholders) {
            val num = placeholder.filter { it.isDigit() }
            val tolerantRegex = Regex("\\[\\s*$num\\s*\\]")
            restored = tolerantRegex.replace(restored, target)
        }

        return restored
    }

    /**
     * Formats glossary entries and character gender constraints for Custom AI system prompt injection.
     */
    fun formatForAiSystemPrompt(entries: List<GlossaryEntry>): String {
        if (entries.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\nPER-GAME GLOSSARY & CHARACTER GENDER RULES:\n")
        sb.append("Strictly follow these exact character names and gender agreements:\n")
        for (entry in entries) {
            when (entry.gender) {
                Gender.FEMININE -> sb.append("- ${entry.term} -> ${entry.target} (Female character / مؤنث: conjugate Arabic verbs, pronouns, and adjectives in feminine form)\n")
                Gender.MASCULINE -> sb.append("- ${entry.term} -> ${entry.target} (Male character / مذكر: conjugate Arabic verbs, pronouns, and adjectives in masculine form)\n")
                Gender.UNSPECIFIED -> sb.append("- ${entry.term} -> ${entry.target}\n")
            }
        }
        return sb.toString().trimEnd()
    }
}

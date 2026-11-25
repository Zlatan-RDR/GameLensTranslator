package com.zlatan.gamelens.translation

/**
 * Universal Arabic Translation Quality & Gaming Idiom Polish Layer.
 * Applied across ML Kit, Google Cloud Translate, and AI localization engines to
 * correct notorious literal gaming mistranslations and enforce Arabic typography rules.
 */
object TranslationQualityPolisher {

    // Fix Arabic typography spacing:
    // Conjunction 'و' attached to following Arabic word: "و الرجل" -> "والرجل"
    private val WAW_CONJUNCTION_REGEX = Regex("(?<=\\s|^)و\\s+([\u0621-\u064A\u0671-\u06D3])")

    // Stray spaces before Arabic & standard punctuation: " ،" -> "،", " ." -> "."
    private val PUNCTUATION_SPACING_REGEX = Regex("\\s+([،.؟!؛:])")

    // Multiple whitespaces collapsed to single
    private val MULTIPLE_WHITESPACE_REGEX = Regex("[\\s\\u00A0\\u200E\\u200F]+")

    // Gaming idiom literal mistranslations:
    // "تفجير التنكر" / "تفجير التمويه" / "تفجير تمويهك" / "تفجير تمويك" / "تم تنفيذه تنزه" -> "انكشف تنكرك"
    private val BLOWN_DISGUISE_REGEX = Regex("(?i)\\b(تفجير\\s+التنكر|تفجير\\s+التمويه|تفجير\\s+تمويهك|تفجير\\s+تمويك|تم\\s+تنفيذه\\s+تنزه)\\b")

    // "رقم لا" -> "لا، لا" (literal MT mistranslation of "No. No")
    private val NO_NUMBER_REGEX = Regex("(?i)\\bرقم\\s+لا\\b")

    // "نجلس البط" -> "أهداف سهلة" (sitting ducks)
    private val SITTING_DUCKS_REGEX = Regex("(?i)\\b(نجلس\\s+البط|البط\\s+الجالس)\\b")

    // "سحب الحمار" -> "أسرع" (haul ass)
    private val HAUL_ASS_REGEX = Regex("(?i)\\bسحب\\s+الحمار\\b")

    /**
     * Applies post-processing polish to Arabic translations.
     */
    fun polishArabic(text: String): String {
        if (text.isBlank()) return text
        var s = text.trim()

        // 1. Universal Gaming Idioms & Literal Mistranslation Fixes
        s = BLOWN_DISGUISE_REGEX.replace(s, "انكشف تنكرك")
        s = NO_NUMBER_REGEX.replace(s, "لا، لا")
        s = SITTING_DUCKS_REGEX.replace(s, "أهداف سهلة")
        s = HAUL_ASS_REGEX.replace(s, "أسرع")

        // 2. Arabic Typography Spacing Normalization
        s = WAW_CONJUNCTION_REGEX.replace(s, "و$1")
        s = PUNCTUATION_SPACING_REGEX.replace(s, "$1")
        s = MULTIPLE_WHITESPACE_REGEX.replace(s, " ").trim()

        return s
    }
}

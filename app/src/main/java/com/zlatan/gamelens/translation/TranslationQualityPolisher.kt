package com.zlatan.gamelens.translation

/**
 * Universal Arabic Translation Quality & Gaming Idiom Polish Layer.
 * Applied across ML Kit, Google Cloud Translate, and AI localization engines to
 * correct notorious literal gaming mistranslations and enforce Arabic typography rules.
 */
object TranslationQualityPolisher {

    // Fix Arabic typography spacing:
    // Conjunction 'و' attached to following Arabic word: "و الرجل" -> "والرجل", "المعلومات و السرية" -> "المعلومات والسرية"
    private val WAW_CONJUNCTION_REGEX = Regex("""(^|[\s\u00A0،.؟!؛:\)\]\}»"'])و[\s\u00A0\u200B-\u200D\uFEFF\u200E\u200F]+(?=[ء-يأإآؤئىة])""")

    // Feminine-object pronoun agreement: "تلك القلادة. لا تفقده" -> "تلك القلادة. لا تفقدها"
    private val FEMININE_OBJECT_PRONOUN_REGEX = Regex("(تلك\\s+[\\u0621-\\u064A]+[،.]\\s*لا\\s+[\\u0621-\\u064A]+)ه\\b")

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

    // Precompiled universal gaming glossary & literal MT replacements:
    private val CLOSE_IN_TARGET_REGEX = Regex("(?i)\\bإغلاق\\s+على\\s+الهدف\\b")
    private val CHECKPOINTS_REGEX = Regex("(?i)\\bنقاط\\s+التفتيش\\b")
    private val CHECKPOINT_REGEX = Regex("(?i)\\bنقطة\\s+التفتيش\\b")
    private val EXTRACTION_POINT_REGEX = Regex("(?i)\\bنقطة\\s+الاستخراج\\b")
    private val NO_PARTIES_REGEX = Regex("(?i)\\b(لا\\s+أحزاب|لا\\s+الحفلات)\\b")
    private val PLAY_BALL_REGEX = Regex("(?i)\\bللعب\\s+الكرة\\b")
    private val CATCH_DRIFT_REGEX = Regex("(?i)\\bفهمت\\s+انجرافي\\b")
    private val EUROPEAN_RIDE_REGEX = Regex("(?i)\\bرحلة\\s+أوروبية\\b")
    private val LOCK_ON_REGEX = Regex("(?i)\\bقفل\\s+اللقطة\\b")
    private val PROMISE_ME_REGEX = Regex("(?i)\\bوعد\\s+مني([.!]?)\\b")

    /**
     * Targeted feminine agreement polish when a female character name is mentioned in source or target.
     */
    fun polishFemaleAgreement(text: String, femaleNames: Set<String>, sourceEnglishText: String = ""): String {
        if (text.isBlank() || femaleNames.isEmpty()) return text

        val namesMentioned = femaleNames.filter { name ->
            (sourceEnglishText.isNotBlank() && sourceEnglishText.contains(name, ignoreCase = true)) ||
            text.contains(name, ignoreCase = true)
        }
        if (namesMentioned.isEmpty()) return text

        var s = text
        for (name in namesMentioned) {
            val esc = Regex.escape(name)
            s = Regex("يجب\\s+أن\\s+تكون\\s+($esc)", RegexOption.IGNORE_CASE).replace(s) { match -> "يجب أن تكوني ${match.groupValues[1]}" }
            s = Regex("لا\\s+بد\\s+أنك\\s+($esc)", RegexOption.IGNORE_CASE).replace(s) { match -> "لا بد أنكِ ${match.groupValues[1]}" }
        }

        s = s.replace(Regex("ادخل\\s+يا\\s+طفل\\b"), "ادخلي يا طفلتي")
        s = s.replace(Regex("تعال\\s+يا\\s+طفل\\b"), "تعالي يا طفلتي")

        s = s.replace(Regex("(?<=[.؟!،]\\s*)ادخل(?!ي)([.!؟]?)"), "ادخلي$1")
        s = s.replace(Regex("(?<=[.؟!،]\\s*)تعال(?!ي)([.!؟]?)"), "تعالي$1")

        return s
    }

    /**
     * Applies post-processing polish to Arabic translations.
     */
    fun polishArabic(
        text: String,
        femaleNames: Set<String> = emptySet(),
        sourceEnglishText: String = ""
    ): String {
        if (text.isBlank()) return text
        var s = text.trim()

        // 1. Universal Gaming Idioms & Literal Mistranslation Fixes
        s = BLOWN_DISGUISE_REGEX.replace(s, "انكشف تنكرك")
        s = NO_NUMBER_REGEX.replace(s, "لا، لا")
        s = SITTING_DUCKS_REGEX.replace(s, "أهداف سهلة")
        s = HAUL_ASS_REGEX.replace(s, "أسرع")

        s = CLOSE_IN_TARGET_REGEX.replace(s, "الاقتراب من الهدف")
        s = CHECKPOINTS_REGEX.replace(s, "نقاط الحفظ")
        s = CHECKPOINT_REGEX.replace(s, "نقطة الحفظ")
        s = EXTRACTION_POINT_REGEX.replace(s, "نقطة الإخلاء")
        s = NO_PARTIES_REGEX.replace(s, "ممنوع الحفلات")
        s = PLAY_BALL_REGEX.replace(s, "للتعاون")
        s = CATCH_DRIFT_REGEX.replace(s, "فهمت قصدي")
        s = EUROPEAN_RIDE_REGEX.replace(s, "سيارة أوروبية")
        s = LOCK_ON_REGEX.replace(s, "تحديد الهدف")
        s = PROMISE_ME_REGEX.replace(s, "عدني$1")
        s = FEMININE_OBJECT_PRONOUN_REGEX.replace(s, "$1ها")

        // 2. Targeted Female Agreement Polish
        if (femaleNames.isNotEmpty()) {
            s = polishFemaleAgreement(s, femaleNames, sourceEnglishText)
        }

        // 3. Arabic Typography Spacing Normalization
        s = MULTIPLE_WHITESPACE_REGEX.replace(s, " ").trim()
        while (WAW_CONJUNCTION_REGEX.containsMatchIn(s)) {
            val next = WAW_CONJUNCTION_REGEX.replace(s, "$1و")
            if (next == s) break
            s = next
        }
        s = PUNCTUATION_SPACING_REGEX.replace(s, "$1")
        s = MULTIPLE_WHITESPACE_REGEX.replace(s, " ").trim()

        return s
    }
}

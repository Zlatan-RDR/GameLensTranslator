package com.zlatan.gamelens.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationQualityPolisherTest {

    @Test
    fun testGamingIdiomCorrections() {
        assertEquals("انكشف تنكرك بسرعة", TranslationQualityPolisher.polishArabic("تفجير التنكر بسرعة"))
        assertEquals("انكشف تنكرك!", TranslationQualityPolisher.polishArabic("تفجير التمويه!"))
        assertEquals("انكشف تنكرك يا رجل", TranslationQualityPolisher.polishArabic("تفجير تمويهك يا رجل"))
        assertEquals("انكشف تنكرك", TranslationQualityPolisher.polishArabic("تم تنفيذه تنزه"))
        assertEquals("لا، لا، لا يمكننا الذهاب", TranslationQualityPolisher.polishArabic("رقم لا، لا يمكننا الذهاب"))
        assertEquals("نحن أهداف سهلة هنا", TranslationQualityPolisher.polishArabic("نحن نجلس البط هنا"))
        assertEquals("أسرع الآن!", TranslationQualityPolisher.polishArabic("سحب الحمار الآن!"))
    }

    @Test
    fun testArabicTypographySpacing() {
        // Conjunction 'و' should connect directly to subsequent word
        assertEquals("والسماء والطارق", TranslationQualityPolisher.polishArabic("و السماء و الطارق"))
        assertEquals("ذهب أحمد ومحمد", TranslationQualityPolisher.polishArabic("ذهب أحمد و محمد"))

        // Punctuation spacing
        assertEquals("مرحباً بك، كيف حالك؟", TranslationQualityPolisher.polishArabic("مرحباً بك ، كيف حالك ؟"))
    }
}

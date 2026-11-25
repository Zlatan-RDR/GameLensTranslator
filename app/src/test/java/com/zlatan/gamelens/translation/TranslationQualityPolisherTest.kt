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

        // New universal gaming glossary & literal MT corrections
        assertEquals("الاقتراب من الهدف", TranslationQualityPolisher.polishArabic("إغلاق على الهدف"))
        assertEquals("وصلنا إلى نقاط الحفظ", TranslationQualityPolisher.polishArabic("وصلنا إلى نقاط التفتيش"))
        assertEquals("هذه نقطة الحفظ", TranslationQualityPolisher.polishArabic("هذه نقطة التفتيش"))
        assertEquals("التوجه إلى نقطة الإخلاء", TranslationQualityPolisher.polishArabic("التوجه إلى نقطة الاستخراج"))
        assertEquals("ممنوع الحفلات الليلة", TranslationQualityPolisher.polishArabic("لا أحزاب الليلة"))
        assertEquals("جئت للتعاون معك", TranslationQualityPolisher.polishArabic("جئت للعب الكرة معك"))
        assertEquals("هل فهمت قصدي؟", TranslationQualityPolisher.polishArabic("هل فهمت انجرافي؟"))
        assertEquals("استأجرنا سيارة أوروبية", TranslationQualityPolisher.polishArabic("استأجرنا رحلة أوروبية"))
        assertEquals("تم تحديد الهدف", TranslationQualityPolisher.polishArabic("تم قفل اللقطة"))
        assertEquals("عدني.", TranslationQualityPolisher.polishArabic("وعد مني."))
        assertEquals("عدني!", TranslationQualityPolisher.polishArabic("وعد مني!"))
    }

    @Test
    fun testArabicTypographySpacing() {
        // Conjunction 'و' should connect directly to subsequent word
        assertEquals("والسماء والطارق", TranslationQualityPolisher.polishArabic("و السماء و الطارق"))
        assertEquals("ذهب أحمد ومحمد", TranslationQualityPolisher.polishArabic("ذهب أحمد و محمد"))
        assertEquals("المعلومات والسرية", TranslationQualityPolisher.polishArabic("المعلومات و السرية"))
        assertEquals("وكالة. لقد قدمت المعلومات والسرية. وبعت الكمال.", TranslationQualityPolisher.polishArabic("وكالة. لقد قدمت المعلومات و السرية. وبعت الكمال."))

        // Punctuation spacing
        assertEquals("مرحباً بك، كيف حالك؟", TranslationQualityPolisher.polishArabic("مرحباً بك ، كيف حالك ؟"))
    }

    @Test
    fun testFemininePronounAgreementAndNoParties() {
        assertEquals("تلك القلادة. لا تفقدها", TranslationQualityPolisher.polishArabic("تلك القلادة. لا تفقده"))
        assertEquals("تلك القلادة، لا تفقدها", TranslationQualityPolisher.polishArabic("تلك القلادة، لا تفقده"))
        assertEquals("ممنوع الحفلات هنا", TranslationQualityPolisher.polishArabic("لا الحفلات هنا"))
    }
}

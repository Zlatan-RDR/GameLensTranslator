package com.zlatan.gamelens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrProcessorTest {

    @Test
    fun testNumericDialogueNotDropped() {
        val ocrEngine = OcrEngine()
        assertTrue(ocrEngine.passesEnglishTokenRatioFilter("47? 47? Report, 47!"))
        assertTrue(ocrEngine.passesEnglishTokenRatioFilter("$37.50 for a drink"))
        assertTrue(ocrEngine.passesEnglishTokenRatioFilter("Level 47 complete!"))
        assertTrue(ocrEngine.passesEnglishTokenRatioFilter("Don't act so high"))

        // Pure numeric / symbol noise with zero English words must still be dropped
        assertFalse(ocrEngine.passesEnglishTokenRatioFilter("1234 5678"))
        assertFalse(ocrEngine.passesEnglishTokenRatioFilter("47 99 100"))
        assertFalse(ocrEngine.passesEnglishTokenRatioFilter("$%#@ 123"))
    }

    @Test
    fun testCleanMenuScanArtifacts() {
        assertEquals("Tap to continue", OcrTextCleaner.cleanMenuScanArtifacts("Tap ) to continue"))
        assertEquals("Tap to continue", OcrTextCleaner.cleanMenuScanArtifacts("Tap ( ) to continue"))
        assertEquals("Tap and hold", OcrTextCleaner.cleanMenuScanArtifacts("Tap  and hold"))
        assertEquals("Press to enter", OcrTextCleaner.cleanMenuScanArtifacts("Press () to enter"))
        assertEquals("Normal menu text", OcrTextCleaner.cleanMenuScanArtifacts("Normal menu text"))
    }
}

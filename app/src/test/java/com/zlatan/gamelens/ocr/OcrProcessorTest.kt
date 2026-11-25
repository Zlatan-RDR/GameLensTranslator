package com.zlatan.gamelens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrProcessorTest {

    @Test
    fun testNumericDialogueNotDropped() {
        assertTrue(OcrEngine.passesEnglishTokenRatioFilter("47? 47? Report, 47!"))
        assertTrue(OcrEngine.passesEnglishTokenRatioFilter("$37.50 for a drink"))
        assertTrue(OcrEngine.passesEnglishTokenRatioFilter("Level 47 complete!"))
        assertTrue(OcrEngine.passesEnglishTokenRatioFilter("Don't act so high"))

        // Pure numeric / symbol noise with zero English words must still be dropped
        assertFalse(OcrEngine.passesEnglishTokenRatioFilter("1234 5678"))
        assertFalse(OcrEngine.passesEnglishTokenRatioFilter("47 99 100"))
        assertFalse(OcrEngine.passesEnglishTokenRatioFilter("$%#@ 123"))
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

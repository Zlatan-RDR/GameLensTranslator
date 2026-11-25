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

    @Test
    fun testOcrPreNormalizerAndWordRepair() {
        // Contractions and accent apostrophes
        assertEquals("That's great", OcrTextCleaner.normalizeOcrArtifacts("That ´s great"))
        assertEquals("you're right", OcrTextCleaner.normalizeOcrArtifacts("you ´re right"))
        assertEquals("what's that", OcrTextCleaner.normalizeOcrArtifacts("what 's that"))

        // Digit-for-letter substitution
        assertEquals("Officer on duty", OcrTextCleaner.normalizeOcrArtifacts("0fficer on duty"))
        assertEquals("Only you", OcrTextCleaner.normalizeOcrArtifacts("0nly you"))

        // Stray HUD button prompts
        assertEquals("Chicago SWAT Officer", OcrTextCleaner.normalizeOcrArtifacts("L Chicago SWAT Officer"))
        assertEquals("Swap Knife", OcrTextCleaner.normalizeOcrArtifacts("L Swap Knife"))
        assertEquals("I know.", OcrTextCleaner.normalizeOcrArtifacts("I know."))
        assertEquals("A Personal Contract", OcrTextCleaner.normalizeOcrArtifacts("A Personal Contract"))

        // Dotted stylized emphasis
        assertEquals("Loving the suit!", OcrTextCleaner.normalizeOcrArtifacts("Lov.ing. the.suit!"))

        // Glued and split tokens
        assertEquals("I come in peace", OcrTextCleaner.normalizeOcrArtifacts("Icome in peace"))
        assertEquals("the king of Chinatown", OcrTextCleaner.normalizeOcrArtifacts("theking ofchinatown"))
        assertEquals("Controlled entry", OcrTextCleaner.normalizeOcrArtifacts("Control Led entry"))
    }

    @Test
    fun testNoiseAndFragmentRejection() {
        // Fragmented gibberish rejection
        assertTrue(OcrTextCleaner.isFragmentedGibberish("ill ing view trolled Der"))
        assertTrue(OcrTextCleaner.isNoiseOrPrompt("ill ing view trolled Der"))
        assertFalse(OcrTextCleaner.isFragmentedGibberish("Good morning agent 47"))

        // Multi-line and short valid words like "her", "and", "the" must NEVER be dropped as noise/prompt
        assertFalse(OcrTextCleaner.isNoiseOrPrompt("her down and offered me the contract."))
        assertFalse(OcrTextCleaner.isFragmentedGibberish("her down and offered me the contract."))

        // Dossier and HUD metadata detection
        assertTrue(OcrEngine.isDossierOrHudMetadata("HAIR: Black"))
        assertTrue(OcrEngine.isDossierOrHudMetadata("RANK: Sergeant"))
        assertTrue(OcrEngine.isDossierOrHudMetadata("NATIONALITY: American"))
        assertFalse(OcrEngine.isDossierOrHudMetadata("Her rank was very high in the agency"))

        // Logcat and self-capture rejection
        assertTrue(OcrTextCleaner.isLogcatOrSelfCaptureNoise("[12:34:56.789] D/GameLens: precision loop"))
        assertTrue(OcrTextCleaner.isLogcatOrSelfCaptureNoise("com.zlatan.gamelens.MainActivity"))
        assertFalse(OcrTextCleaner.isLogcatOrSelfCaptureNoise("This is normal game dialogue"))

        // Single-word dialogue allowed when punctuated with ? or !
        assertFalse(OcrTextCleaner.isNoiseOrPrompt("hello?"))
        assertFalse(OcrTextCleaner.isNoiseOrPrompt("wait!"))
        assertFalse(OcrTextCleaner.isNoiseOrPrompt("what?"))
        assertFalse(OcrTextCleaner.isNoiseOrPrompt("run!"))

        // Broken dangling preposition gibberish dropped
        assertTrue(OcrTextCleaner.isNoiseOrPrompt("Swapife with"))
        assertFalse(OcrTextCleaner.isNoiseOrPrompt("First Aid"))
    }

    @Test
    fun testSymmetricCleanUpgradeRules() {
        // 1. Clean-Subset Upgrade: allow if >= 50% removed tokens are garbage/metadata
        val lockedGarbageSubset = "Ganma (condtetra eft 130 Call me back when"
        val cleanSubsetCandidate = "Call me back when"
        assertTrue(OcrTextCleaner.isCleanSubsetUpgrade(cleanSubsetCandidate, lockedGarbageSubset))

        // Normal sentence subset with valid dictionary words must NOT upgrade (kept as shorter-subset)
        val fullSentence = "Take this. It's important. Take this."
        val validWordSubset = "Take this."
        assertFalse(OcrTextCleaner.isCleanSubsetUpgrade(validWordSubset, fullSentence))

        // 2. Clean-Superset Upgrade: allow if added tokens contain >= 2 valid dictionary words
        val baseText = "Call me back when"
        val cleanSupersetCandidate = "Call me back when you're done, got it?"
        assertTrue(OcrTextCleaner.isCleanSupersetUpgrade(cleanSupersetCandidate, baseText))

        // Credit/metadata-polluted superset must NOT upgrade
        val pollutedSupersetCandidate = "Call me back when you're done RANK GAMMA 1306"
        assertFalse(OcrTextCleaner.isCleanSupersetUpgrade(pollutedSupersetCandidate, "Call me back when you're done"))

        // 3. Inline Dossier Prefix/Suffix Scrubber
        val prefixedDossier = "Ganma (condtetra eft 130 Call me back when you're done"
        val suffixedDossier = "Call me back when you're done (condtetra eft 130"
        assertEquals("Call me back when you're done", OcrTextCleaner.scrubInlineDossierArtifacts(prefixedDossier))
        assertEquals("Call me back when you're done", OcrTextCleaner.scrubInlineDossierArtifacts(suffixedDossier))
    }

    @Test
    fun testWordOverlapRatioAndLogcatNoise() {
        // High word overlap between same sentence re-reads
        val s1 = "Chris here, yeah, we got a situation."
        val s1Polluted = "Chris here, yeah, we got a situation.EDDY Row"
        val ratio1 = OcrProcessor.computeWordOverlapRatio(s1Polluted, s1)
        assertTrue(ratio1 >= 0.70f)

        // Low word overlap between two different dialogue lines
        val s2 = "you tell me that huh?"
        val ratio2 = OcrProcessor.computeWordOverlapRatio(s2, s1)
        assertTrue(ratio2 < 0.45f)

        // Expanded logcat noise patterns
        assertTrue(OcrTextCleaner.isLogcatOrSelfCaptureNoise("12:34:56.789 D/DiagnosticLogger: acquireCroppedRoi"))
        assertTrue(OcrTextCleaner.isLogcatOrSelfCaptureNoise("FrameDiffEngine: diff=0.005"))
        assertTrue(OcrTextCleaner.isLogcatOrSelfCaptureNoise("TranslationOverlayService: poll"))

        // Punctuation spacing normalization
        assertEquals("Chinatown. 12", OcrTextCleaner.normalizeOcrArtifacts("Chinatown . 12"))
        assertEquals("Hello, world", OcrTextCleaner.normalizeOcrArtifacts("Hello , world"))
    }
}

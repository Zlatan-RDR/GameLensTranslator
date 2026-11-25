package com.zlatan.gamelens.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDetectorTest {

    @Test
    fun testValidSpeakerDetection() {
        val r1 = SpeakerDetector.detect("Diana: Find the exit, 47.")
        assertTrue(r1.hasSpeaker)
        assertEquals("Diana", r1.speaker)
        assertEquals("Find the exit, 47.", r1.dialogue)

        val r2 = SpeakerDetector.detect("Agent 47: I'm in position.")
        assertTrue(r2.hasSpeaker)
        assertEquals("Agent 47", r2.speaker)
        assertEquals("I'm in position.", r2.dialogue)

        val r3 = SpeakerDetector.detect("Benjamin Travis: Eliminate the target immediately.")
        assertTrue(r3.hasSpeaker)
        assertEquals("Benjamin Travis", r3.speaker)
        assertEquals("Eliminate the target immediately.", r3.dialogue)
    }

    @Test
    fun testExcludedHudLabelsAndTimestamps() {
        // Excluded HUD prompts
        val r1 = SpeakerDetector.detect("NOTE: The safe code is hidden upstairs.")
        assertFalse(r1.hasSpeaker)
        assertEquals("NOTE: The safe code is hidden upstairs.", r1.dialogue)

        val r2 = SpeakerDetector.detect("TIP: Crouch to reduce footstep noise.")
        assertFalse(r2.hasSpeaker)

        val r3 = SpeakerDetector.detect("BRIEFING: Infiltrate the mansion.")
        assertFalse(r3.hasSpeaker)

        val r4 = SpeakerDetector.detect("WARNING: Restricted area ahead.")
        assertFalse(r4.hasSpeaker)

        // Timestamps
        val r5 = SpeakerDetector.detect("12:30: Briefing began.")
        assertFalse(r5.hasSpeaker)

        val r6 = SpeakerDetector.detect("00:15: Security alert triggered.")
        assertFalse(r6.hasSpeaker)
    }

    @Test
    fun testStandardDialogueWithoutSpeaker() {
        val r1 = SpeakerDetector.detect("Just another day at the office.")
        assertFalse(r1.hasSpeaker)
        assertEquals("Just another day at the office.", r1.dialogue)

        val r2 = SpeakerDetector.detect("Did you see that? Over there!")
        assertFalse(r2.hasSpeaker)
    }
}

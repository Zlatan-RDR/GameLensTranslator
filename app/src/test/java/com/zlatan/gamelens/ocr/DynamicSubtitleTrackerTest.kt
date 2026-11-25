package com.zlatan.gamelens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicSubtitleTrackerTest {

    @Test
    fun testHudClutterRejection() {
        val tracker = DynamicSubtitleTracker()
        val screenW = 1920
        val screenH = 1080

        // Ammo / health counters
        val ammoBox = DynamicSubtitleTracker.TrackerRect(1700, 950, 1850, 1000)
        assertTrue(tracker.isHudClutter(ammoBox, "100/100", screenW, screenH))
        assertTrue(tracker.isHudClutter(ammoBox, "30", screenW, screenH))
        assertTrue(tracker.isHudClutter(ammoBox, "x5", screenW, screenH))
        assertTrue(tracker.isHudClutter(ammoBox, "01:23", screenW, screenH))

        // HUD hotkeys and status tags
        val tagBox = DynamicSubtitleTracker.TrackerRect(50, 50, 100, 100)
        assertTrue(tracker.isHudClutter(tagBox, "HP", screenW, screenH))
        assertTrue(tracker.isHudClutter(tagBox, "[E]", screenW, screenH))
        assertTrue(tracker.isHudClutter(tagBox, "TAB", screenW, screenH))

        // Minimap / top-corner health bars
        val minimapBox = DynamicSubtitleTracker.TrackerRect(50, 30, 250, 120)
        assertTrue(tracker.isHudClutter(minimapBox, "Radar Area", screenW, screenH))

        // Real dialogue text in center/bottom
        val dialogueBox = DynamicSubtitleTracker.TrackerRect(400, 800, 1500, 900)
        assertFalse(tracker.isHudClutter(dialogueBox, "You tell me that, huh?", screenW, screenH))
    }

    @Test
    fun testDialogueCandidateDetection() {
        val tracker = DynamicSubtitleTracker()

        assertTrue(tracker.isDialogueCandidate("Yeah, it's an ice-cream truck."))
        assertTrue(tracker.isDialogueCandidate("Who's that?"))
        assertTrue(tracker.isDialogueCandidate("I tell you, he won't be looking."))

        assertFalse(tracker.isDialogueCandidate("12/30"))
        assertFalse(tracker.isDialogueCandidate("skip"))
        assertFalse(tracker.isDialogueCandidate("tap to"))
        assertFalse(tracker.isDialogueCandidate("x"))
    }

    @Test
    fun testOnDiscoveryFrameLocksRoiWithPadding() {
        val tracker = DynamicSubtitleTracker()
        assertEquals(DynamicSubtitleTracker.State.DISCOVERY, tracker.getState())

        val lines = listOf(
            DynamicSubtitleTracker.DetectedLine(
                "Yeah, it's an ice-cream truck.",
                DynamicSubtitleTracker.TrackerRect(500, 800, 1200, 860)
            )
        )

        val locked = tracker.onDiscoveryFrame(lines, 1920, 1080)
        assertNotNull(locked)
        assertEquals(DynamicSubtitleTracker.State.LOCKED, tracker.getState())

        // Width = 700. PadX = 700 * 0.12 = 84px. Left = 500 - 84 = 416, Right = 1200 + 84 = 1284
        // Height = 60. PadY = 60 * 0.25 = 15px. Top = 800 - 15 = 785, Bottom = 860 + 15 = 875
        assertEquals(416, locked!!.left)
        assertEquals(785, locked.top)
        assertEquals(1284, locked.right)
        assertEquals(875, locked.bottom)

        val lockedF = tracker.getLockedRoi()
        assertNotNull(lockedF)
    }

    @Test
    fun testAutoGrowNearBoundary() {
        val tracker = DynamicSubtitleTracker()
        val lines = listOf(
            DynamicSubtitleTracker.DetectedLine(
                "Chris here, yeah, we got a situation.",
                DynamicSubtitleTracker.TrackerRect(500, 800, 1200, 860)
            )
        )
        tracker.onDiscoveryFrame(lines, 1920, 1080)
        val initialRoi = tracker.getLockedTrackerRect()!!

        // Text is right on left margin (< 15px) inside the ROI crop (e.g. left = 5)
        val nearEdgeInRoi = DynamicSubtitleTracker.TrackerRect(5, 20, 400, 50)
        val grown = tracker.checkAutoGrow(nearEdgeInRoi, initialRoi.width, initialRoi.height)
        assertNotNull(grown)
        assertTrue(grown!!.left < initialRoi.left)
    }

    @Test
    fun testAutoRelocateAfterTimeoutAndMotion() {
        val tracker = DynamicSubtitleTracker()
        val lines = listOf(
            DynamicSubtitleTracker.DetectedLine(
                "Hold on, someone's coming.",
                DynamicSubtitleTracker.TrackerRect(500, 800, 1200, 860)
            )
        )
        tracker.onDiscoveryFrame(lines, 1920, 1080)
        assertEquals(DynamicSubtitleTracker.State.LOCKED, tracker.getState())

        // Empty frame at t=1000ms
        tracker.notifyFrameOcrStatus(isEmpty = true, hasMotionElsewhere = true, nowMs = 1000L)
        assertEquals(DynamicSubtitleTracker.State.LOCKED, tracker.getState())

        // Empty frame at t=2000ms (1000ms elapsed < 2500ms)
        tracker.notifyFrameOcrStatus(isEmpty = true, hasMotionElsewhere = true, nowMs = 2000L)
        assertEquals(DynamicSubtitleTracker.State.LOCKED, tracker.getState())

        // Empty frame at t=3600ms (2600ms elapsed >= 2500ms with motion elsewhere)
        tracker.notifyFrameOcrStatus(isEmpty = true, hasMotionElsewhere = true, nowMs = 3600L)
        assertEquals(DynamicSubtitleTracker.State.DISCOVERY, tracker.getState())
        assertNull(tracker.getLockedRoi())
    }
}

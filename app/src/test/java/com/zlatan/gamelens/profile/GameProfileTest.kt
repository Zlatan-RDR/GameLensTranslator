package com.zlatan.gamelens.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileTest {

    @Test
    fun testSerializationWithAutoSubtitleTracking() {
        val profile = GameProfile(
            id = "test_game",
            name = "Test Game",
            roiLeft = 0.15f,
            roiTop = 0.70f,
            roiRight = 0.85f,
            roiBottom = 0.92f,
            autoSubtitleTracking = true
        )

        val json = profile.toJsonObject()
        assertTrue(json.getBoolean("autoSubtitleTracking"))

        val deserialized = GameProfile.fromJsonObject(json)
        assertEquals("test_game", deserialized.id)
        assertEquals("Test Game", deserialized.name)
        assertEquals(0.15f, deserialized.roiLeft, 0.001f)
        assertTrue(deserialized.autoSubtitleTracking)
    }

    @Test
    fun testDefaultAutoSubtitleTrackingIsFalse() {
        val profile = GameProfile(
            id = "default",
            name = "Default"
        )
        assertFalse(profile.autoSubtitleTracking)

        val json = profile.toJsonObject()
        assertFalse(json.getBoolean("autoSubtitleTracking"))

        val deserialized = GameProfile.fromJsonObject(json)
        assertFalse(deserialized.autoSubtitleTracking)
    }
}

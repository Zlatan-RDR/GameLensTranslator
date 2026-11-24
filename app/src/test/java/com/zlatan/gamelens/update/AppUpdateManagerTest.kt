package com.zlatan.gamelens.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testSemanticVersionComparison() {
        // Newer versions
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.0", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.10", "1.0.9"))
        assertTrue(AppUpdateManager.isNewerVersion("v2.1.3", "v2.1.2"))

        // Equal versions
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "v1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("2.5.1", "2.5.1"))

        // Older versions
        assertFalse(AppUpdateManager.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.2.0", "1.3.0"))
    }

    @Test
    fun testHttp404GracefullyHandledAsNoUpdate() {
        val result404 = AppUpdateManager.parseReleaseResponse(404, null, "1.0.0")
        assertTrue(result404 is UpdateCheckResult.UpToDate)

        val result500 = AppUpdateManager.parseReleaseResponse(500, null, "1.0.0")
        assertTrue(result500 is UpdateCheckResult.Error)
    }
}

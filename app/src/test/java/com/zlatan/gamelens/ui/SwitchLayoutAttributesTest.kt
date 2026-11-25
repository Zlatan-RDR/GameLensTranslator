package com.zlatan.gamelens.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class SwitchLayoutAttributesTest {

    @Test
    fun testAllSwitchesHaveRequiredAttributes() {
        val candidateFiles = listOf(
            File("app/src/main/res/layout/activity_main.xml"),
            File("src/main/res/layout/activity_main.xml"),
            File("../app/src/main/res/layout/activity_main.xml")
        )
        val layoutFile = candidateFiles.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not locate activity_main.xml in candidate paths: $candidateFiles")

        val xmlContent = layoutFile.readText()

        // 1. Verify no raw MaterialSwitch is used (ensure uniform SwitchMaterial)
        assertFalse(
            "activity_main.xml should not contain MaterialSwitch to prevent theme/onMeasure regressions",
            xmlContent.contains("com.google.android.material.materialswitch.MaterialSwitch")
        )

        // 2. Extract all switch elements using regex
        val switchPattern = Pattern.compile(
            "<(com\\.google\\.android\\.material\\.switchmaterial\\.SwitchMaterial|androidx\\.appcompat\\.widget\\.SwitchCompat|Switch)[^>]*?/>",
            Pattern.DOTALL
        )
        val matcher = switchPattern.matcher(xmlContent)
        val switchSnippets = mutableListOf<String>()
        while (matcher.find()) {
            switchSnippets.add(matcher.group())
        }

        assertTrue("Expected to find at least 7 switches in activity_main.xml, found ${switchSnippets.size}", switchSnippets.size >= 7)

        val expectedIds = listOf(
            "switchHighContrastOcr",
            "switchOnlineEnhanced",
            "switchTypewriterMode",
            "switchGameAudio",
            "switchTextShadow",
            "switchAutoSubtitleTracking",
            "switchDeveloperMode"
        )

        val idPattern = Pattern.compile("android:id=\"@\\+id/([^\"]+)\"")

        val foundIds = mutableListOf<String>()

        for (snippet in switchSnippets) {
            val idMatcher = idPattern.matcher(snippet)
            val id = if (idMatcher.find()) idMatcher.group(1) else "unknown"
            foundIds.add(id)

            // Assert showText is explicitly false in both namespaces
            assertTrue(
                "Switch '$id' must specify app:showText=\"false\" to prevent StaticLayout.<init> NPE",
                snippet.contains("app:showText=\"false\"")
            )
            assertTrue(
                "Switch '$id' must specify android:showText=\"false\"",
                snippet.contains("android:showText=\"false\"")
            )

            // Assert empty textOn and textOff to prevent CharSequence.length() NPE
            assertTrue(
                "Switch '$id' must specify android:textOn=\"\"",
                snippet.contains("android:textOn=\"\"")
            )
            assertTrue(
                "Switch '$id' must specify android:textOff=\"\"",
                snippet.contains("android:textOff=\"\"")
            )
        }

        for (expectedId in expectedIds) {
            assertTrue(
                "Expected switch '$expectedId' was not found in parsed switches: $foundIds",
                foundIds.contains(expectedId)
            )
        }
    }
}

package com.zlatan.gamelens.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameGlossaryManagerTest {

    @Test
    fun testParseMultipleSyntaxStyles() {
        val rawGlossary = """
            # Game Glossary
            Victoria = فيكتوريا [أنثى]
            Agent 47 -> العميل 47 [ذكر]
            Diana : ديانا [F]
            Travis = ترافيس [M]
            Silverballer -> سيلفربالر
            Burnwood : بيرنوود
        """.trimIndent()

        val entries = GameGlossaryManager.parse(rawGlossary)
        assertEquals(6, entries.size)

        val victoria = entries.find { it.term == "Victoria" }!!
        assertEquals("فيكتوريا", victoria.target)
        assertEquals(Gender.FEMININE, victoria.gender)

        val agent47 = entries.find { it.term == "Agent 47" }!!
        assertEquals("العميل 47", agent47.target)
        assertEquals(Gender.MASCULINE, agent47.gender)

        val diana = entries.find { it.term == "Diana" }!!
        assertEquals("ديانا", diana.target)
        assertEquals(Gender.FEMININE, diana.gender)

        val travis = entries.find { it.term == "Travis" }!!
        assertEquals("ترافيس", travis.target)
        assertEquals(Gender.MASCULINE, travis.gender)

        val silverballer = entries.find { it.term == "Silverballer" }!!
        assertEquals("سيلفربالر", silverballer.target)
        assertEquals(Gender.UNSPECIFIED, silverballer.gender)
    }

    @Test
    fun testMtProtectionAndRestorationWithSpacingTolerance() {
        val entries = listOf(
            GlossaryEntry("Agent 47", "العميل 47", Gender.MASCULINE),
            GlossaryEntry("Agent", "عميل", Gender.MASCULINE),
            GlossaryEntry("Victoria", "فيكتوريا", Gender.FEMININE)
        )

        val input = "Agent 47 must protect Victoria, not just any Agent."
        val (protectedText, placeholders) = GameGlossaryManager.protectBeforeMt(input, entries)

        // Longest term first: "Agent 47" must be protected as [9001], "Victoria" as [9002], "Agent" as [9003]
        assertTrue(protectedText.contains("[9001]"))
        assertTrue(protectedText.contains("[9002]"))
        assertTrue(protectedText.contains("[9003]"))
        assertFalse(protectedText.contains("Agent 47"))

        // Simulate MT engine inserting spaces inside brackets: e.g. "[ 9001 ]" or "[9002]"
        val mtOutput = "يجب على [ 9001 ] حماية [9002]، وليس مجرد أي [ 9003 ]."
        val restored = GameGlossaryManager.restoreAfterMt(mtOutput, placeholders)

        assertEquals("يجب على العميل 47 حماية فيكتوريا، وليس مجرد أي عميل.", restored)
    }

    @Test
    fun testAiSystemPromptFormatting() {
        val entries = listOf(
            GlossaryEntry("Victoria", "فيكتوريا", Gender.FEMININE),
            GlossaryEntry("Agent 47", "العميل 47", Gender.MASCULINE),
            GlossaryEntry("Silverballer", "سيلفربالر", Gender.UNSPECIFIED)
        )

        val prompt = GameGlossaryManager.formatForAiSystemPrompt(entries)
        assertTrue(prompt.contains("PER-GAME GLOSSARY & CHARACTER GENDER RULES:"))
        assertTrue(prompt.contains("Victoria -> فيكتوريا (Female character / مؤنث"))
        assertTrue(prompt.contains("Agent 47 -> العميل 47 (Male character / مذكر"))
        assertTrue(prompt.contains("Silverballer -> سيلفربالر"))
    }
}

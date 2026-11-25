package com.zlatan.gamelens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class EnglishWordRepairerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            val possiblePaths = listOf(
                File("app/src/main/assets/en_dict.txt"),
                File("src/main/assets/en_dict.txt"),
                File("../app/src/main/assets/en_dict.txt"),
                File("assets/en_dict.txt"),
                File("../assets/en_dict.txt")
            )
            val dictFile = possiblePaths.firstOrNull { it.exists() }
                ?: error("Could not find en_dict.txt in candidate paths: $possiblePaths")
            FileInputStream(dictFile).use { stream ->
                EnglishWordRepairer.loadDictionary(stream)
            }
        }
    }

    @Test
    fun testPositiveRepairs() {
        val cases = mapOf(
            "beendealtwith" to "been dealt with",
            "thehead" to "the head",
            "ofsecurity" to "of security",
            "shouldjust" to "should just",
            "Maybewe" to "Maybe we",
            "fromthe" to "from the",
            "Turningon" to "Turning on",
            "aradio" to "a radio",
            "radiocan" to "radio can",
            "Don'tact" to "Don't act",
            "sohigh" to "so high",
            "groundfloor" to "ground floor",
            "seethrough" to "see through",
            "sayshe's" to "says he's",
            "akid" to "a kid",
            "al ways" to "always",
            "hand le" to "handle",
            "mind ful" to "mindful",
            "Disgu ise" to "Disguise",
            "Inst inct" to "Instinct",
            "unsi lenced" to "unsilenced",
            "LoW" to "low",
            "Salt! Shameon you" to "Salt! Shame on you",
            "appointa" to "appoint a",
            "clienton" to "client on",
            "suppliedinformation" to "supplied information",
            "yourscore" to "your score",
            "playingon" to "playing on",
            "steal thy" to "stealthy",
            "ofsecurity's" to "of security's",
            "thewitnesses" to "the witnesses",
            "thison" to "this on",
            "silencerfrom" to "silencer from",
            "focusingon" to "focusing on",
            "focuson" to "focus on",
            "thenlock" to "then lock",
            "agenton" to "agent on",
            "youlistening" to "you listening",
            "thehints" to "the hints",
            "andinstinct" to "and instinct",
            "Excel lent" to "Excellent",
            "supp li ed" to "supplied",
            "up start" to "upstart",
            "under stated" to "understated",
            "count less" to "countless",
            "Europeanride" to "European ride",
            "herewithout" to "here without",
            "47!s" to "47's",
            "agent 47!s" to "agent 47's",
            "Put thison the table" to "Put this on the table",
            "Try Turningon the radio" to "Try Turning on the radio",
            "They saw thewitnesses outside" to "They saw the witnesses outside",
            "youlistening? Sweet." to "you listening? Sweet.",
            "himsellf" to "himself",
            "He saw himsellf in the mirror" to "He saw himself in the mirror"
        )

        for ((input, expected) in cases) {
            val actual = EnglishWordRepairer.repair(input)
            assertEquals("Failed repair for input: '$input'", expected, actual)
        }
    }

    @Test
    fun testNegativeProtections() {
        val protectedLines = listOf(
            "Diana Burnwood",
            "Silverballer",
            "Victoria",
            "Chicago",
            "a way",
            "in to",
            "for ever",
            "Hitman: Absolution"
        )

        for (line in protectedLines) {
            val actual = EnglishWordRepairer.repair(line)
            assertEquals("Protected line should not be altered: '$line'", line, actual)
        }
    }

    @Test
    fun testSpacelessJitterGateVsCache() {
        // Active-screen fluctuating spaces should be detected as the same active subtitle
        assertTrue(
            "Fluctuating spaces must be detected as same active subtitle",
            OcrTextCleaner.isSameActiveSubtitle("Don'tact so high", "Don't act sohigh")
        )

        // Distinct nouns (guard vs gardener) must NEVER collide in cache
        assertFalse(
            "Distinct nouns must NEVER collide in cache",
            OcrTextCleaner.isSimilarForCache("close to the guard", "close to the gardener")
        )

        // Single-character typo in cache should still hit
        assertTrue(
            "Single-character typo should match in cache",
            OcrTextCleaner.isSimilarForCache("close to the guard", "close to the guurd")
        )
    }

    @Test
    fun testDictionaryCoverage() {
        val highCoverage = "this is a clean english sentence with valid words"
        val lowCoverage = "asdfgh zxcvbn qwert kljpoi"
        assertTrue(EnglishWordRepairer.dictionaryCoverage(highCoverage) >= 0.90f)
        assertTrue(EnglishWordRepairer.dictionaryCoverage(lowCoverage) <= 0.10f)
    }

    @Test
    fun testRepairSplitCurrencyAndDecimal() {
        assertEquals(
            "$37.50 a night. No parties.",
            EnglishWordRepairer.repair("$37. 50 a night. No parties.")
        )
        assertEquals(
            "End of chapter 1. 20 minutes later.",
            EnglishWordRepairer.repair("End of chapter 1. 20 minutes later.")
        )
        assertEquals(
            "Hello world. This is test 2. 50 days passed.",
            EnglishWordRepairer.repair("Hello world. This is test 2. 50 days passed.")
        )
    }
}

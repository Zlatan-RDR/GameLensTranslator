package com.zlatan.gamelens.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationManagerTest {

    @Test
    fun testNormalizeApiUrl() {
        // OpenAI-compatible endpoints
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            TranslationManager.normalizeApiUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            TranslationManager.normalizeApiUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            TranslationManager.normalizeApiUrl("https://api.openai.com/v1/chat/completions")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            TranslationManager.normalizeApiUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            TranslationManager.normalizeApiUrl("https://openrouter.ai/api/v1")
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            TranslationManager.normalizeApiUrl("http://127.0.0.1:8080/v1")
        )

        // Anthropic Claude endpoints
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            TranslationManager.normalizeApiUrl("https://api.anthropic.com")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            TranslationManager.normalizeApiUrl("https://api.anthropic.com/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            TranslationManager.normalizeApiUrl("https://api.anthropic.com/v1")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            TranslationManager.normalizeApiUrl("https://api.anthropic.com/v1/messages")
        )
    }
}

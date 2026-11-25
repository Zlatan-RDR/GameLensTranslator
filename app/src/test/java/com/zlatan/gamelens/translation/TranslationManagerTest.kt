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

    @Test
    fun testGeminiParameterStripping() {
        val geminiUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        val openAiUrl = "https://api.openai.com/v1/chat/completions"

        // Gemini endpoint should omit temperature
        val geminiBody = TranslationManager.buildOpenAiRequestBody(
            geminiUrl,
            "gemini-2.0-flash",
            "system prompt",
            "user text",
            includeGenerationParams = true
        )
        org.junit.Assert.assertFalse("Gemini payload must omit temperature", geminiBody.has("temperature"))
        org.junit.Assert.assertFalse("Gemini payload must omit top_p", geminiBody.has("top_p"))
        org.junit.Assert.assertFalse("Gemini payload must omit top_k", geminiBody.has("top_k"))
        assertEquals("gemini-2.0-flash", geminiBody.getString("model"))
        assertEquals(120, geminiBody.getInt("max_tokens"))

        // Standard OpenAI endpoint should retain temperature = 0.2
        val openAiBody = TranslationManager.buildOpenAiRequestBody(
            openAiUrl,
            "gpt-4o-mini",
            "system prompt",
            "user text",
            includeGenerationParams = true
        )
        org.junit.Assert.assertTrue("OpenAI payload must include temperature", openAiBody.has("temperature"))
        assertEquals(0.2, openAiBody.getDouble("temperature"), 0.001)

        // Retry without generation params should omit temperature
        val retryBody = TranslationManager.buildOpenAiRequestBody(
            openAiUrl,
            "gpt-4o-mini",
            "system prompt",
            "user text",
            includeGenerationParams = false
        )
        org.junit.Assert.assertFalse("Retry payload must omit temperature", retryBody.has("temperature"))
    }
}

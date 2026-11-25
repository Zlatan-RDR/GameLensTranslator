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

        // Gemini endpoint should omit temperature and include reasoning_effort = none & max_tokens = 512
        val geminiBody = TranslationManager.buildOpenAiRequestBody(
            geminiUrl,
            "gemini-3.5-flash",
            "system prompt",
            "user text",
            includeGenerationParams = true
        )
        org.junit.Assert.assertFalse("Gemini payload must omit temperature", geminiBody.has("temperature"))
        org.junit.Assert.assertFalse("Gemini payload must omit top_p", geminiBody.has("top_p"))
        org.junit.Assert.assertFalse("Gemini payload must omit top_k", geminiBody.has("top_k"))
        assertEquals("gemini-3.5-flash", geminiBody.getString("model"))
        assertEquals("none", geminiBody.getString("reasoning_effort"))
        assertEquals(512, geminiBody.getInt("max_tokens"))

        // Gemini endpoint with explicit reasoningEffort = "minimal"
        val geminiMinimal = TranslationManager.buildOpenAiRequestBody(
            geminiUrl,
            "gemini-3.5-flash",
            "system prompt",
            "user text",
            includeGenerationParams = true,
            reasoningEffort = "minimal"
        )
        assertEquals("minimal", geminiMinimal.getString("reasoning_effort"))
        assertEquals(512, geminiMinimal.getInt("max_tokens"))

        // Standard OpenAI endpoint should retain temperature = 0.2 and max_tokens = 512
        val openAiBody = TranslationManager.buildOpenAiRequestBody(
            openAiUrl,
            "gpt-4o-mini",
            "system prompt",
            "user text",
            includeGenerationParams = true
        )
        org.junit.Assert.assertTrue("OpenAI payload must include temperature", openAiBody.has("temperature"))
        assertEquals(0.2, openAiBody.getDouble("temperature"), 0.001)
        assertEquals(512, openAiBody.getInt("max_tokens"))
        org.junit.Assert.assertFalse("OpenAI payload should not have reasoning_effort by default", openAiBody.has("reasoning_effort"))

        // Retry without generation params should omit temperature
        val retryBody = TranslationManager.buildOpenAiRequestBody(
            openAiUrl,
            "gpt-4o-mini",
            "system prompt",
            "user text",
            includeGenerationParams = false
        )
        org.junit.Assert.assertFalse("Retry payload must omit temperature", retryBody.has("temperature"))
        assertEquals(512, retryBody.getInt("max_tokens"))
    }

    @Test
    fun testGlossaryFemaleAgreementAndConjunctionPolish() {
        val glossaryText = "Diana [أنثى]\nVictoria [أنثى]"
        val entries = GameGlossaryManager.parse(glossaryText)
        val femaleNames = GameGlossaryManager.getFemaleNames(entries)

        // 1. Conjunction 'و ' polish (including with NBSP \u00A0)
        val rawConjunction = "لقد قدمت المعلومات و\u00A0السرية."
        val polishedConjunction = TranslationQualityPolisher.polishArabic(rawConjunction, femaleNames)
        assertEquals("لقد قدمت المعلومات والسرية.", polishedConjunction)

        val rawConjunctionNormalSpace = "لقد قدمت المعلومات و السرية."
        val polishedNormal = TranslationQualityPolisher.polishArabic(rawConjunctionNormalSpace, femaleNames)
        assertEquals("لقد قدمت المعلومات والسرية.", polishedNormal)

        // 2. Female agreement with Victoria
        val sourceEnglish = "Oh, you must be victoria. Come on in, child."
        val rawMt = "أوه، يجب أن تكون فيكتوريا. ادخل يا طفل."
        val polishedFemale = TranslationQualityPolisher.polishArabic(rawMt, femaleNames, sourceEnglish)
        assertEquals("أوه، يجب أن تكوني فيكتوريا. ادخلي يا طفلتي.", polishedFemale)
    }

    @Test
    fun testGeminiNativeUrlResolution() {
        // Native models endpoint
        org.junit.Assert.assertTrue(
            TranslationManager.isGeminiNativeEndpoint("https://generativelanguage.googleapis.com/v1beta/models")
        )
        // OpenAI-compat proxy should NOT be treated as native
        org.junit.Assert.assertFalse(
            TranslationManager.isGeminiNativeEndpoint("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
        )

        val resolved = TranslationManager.resolveGeminiNativeUrl(
            "https://generativelanguage.googleapis.com/v1beta/models",
            "gemini-3.5-flash",
            "test_api_key_123"
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=test_api_key_123",
            resolved
        )

        // normalizeApiUrl must NOT append /chat/completions to Gemini native endpoint
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            TranslationManager.normalizeApiUrl("https://generativelanguage.googleapis.com/v1beta/models")
        )
    }

    @Test
    fun testGeminiNativePayloadFormattingAndResponseParsing() {
        // Zero-thinking budget payload
        val payloadBudget0 = TranslationManager.buildGeminiNativeRequestBody(
            systemPrompt = "Translate to Arabic",
            userContent = "Hello friend",
            thinkingMode = TranslationManager.GeminiThinkingMode.BUDGET_0
        )
        val genConfig0 = payloadBudget0.getJSONObject("generationConfig")
        assertEquals(512, genConfig0.getInt("maxOutputTokens"))
        org.junit.Assert.assertFalse(genConfig0.has("temperature"))
        org.junit.Assert.assertFalse(genConfig0.has("top_p"))
        org.junit.Assert.assertFalse(genConfig0.has("top_k"))
        val thinkingConfig0 = genConfig0.getJSONObject("thinkingConfig")
        assertEquals(0, thinkingConfig0.getInt("thinkingBudget"))

        // Fallback MINIMAL mode payload
        val payloadMinimal = TranslationManager.buildGeminiNativeRequestBody(
            systemPrompt = "Translate to Arabic",
            userContent = "Hello friend",
            thinkingMode = TranslationManager.GeminiThinkingMode.MINIMAL
        )
        val thinkingConfigMinimal = payloadMinimal.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertEquals("MINIMAL", thinkingConfigMinimal.getString("thinkingLevel"))

        // Fallback NONE mode payload
        val payloadNone = TranslationManager.buildGeminiNativeRequestBody(
            systemPrompt = "Translate to Arabic",
            userContent = "Hello friend",
            thinkingMode = TranslationManager.GeminiThinkingMode.NONE
        )
        org.junit.Assert.assertFalse(payloadNone.getJSONObject("generationConfig").has("thinkingConfig"))

        // Response parser
        val responseJson = org.json.JSONObject(
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "مرحباً يا صديقي" }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val parsed = TranslationManager.parseGeminiNativeResponse(responseJson)
        assertEquals("مرحباً يا صديقي", parsed)
    }
}

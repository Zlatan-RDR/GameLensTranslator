package com.zlatan.gamelens.translation

enum class TranslationMode {
    OFFLINE_MLKIT,
    CLOUD_OPENAI
}

data class TranslationConfig(
    val mode: TranslationMode = TranslationMode.OFFLINE_MLKIT,
    val apiEndpoint: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini"
)

package com.zlatan.gamelens.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey
    val originalText: String,
    val translatedText: String,
    val targetLanguage: String = "ar",
    val timestamp: Long = System.currentTimeMillis()
)

package com.zlatan.gamelens.glossary

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class GlossaryManager(context: Context) {

    companion object {
        private const val TAG = "GlossaryManager"
        private const val ASSET_FILE = "game_glossary.json"
    }

    private val glossaryMap = ConcurrentHashMap<String, String>()

    init {
        loadGlossaryFromAssets(context)
    }

    private fun loadGlossaryFromAssets(context: Context) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(ASSET_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }

            val jsonRoot = JSONObject(jsonString)
            val terms = jsonRoot.optJSONObject("terms")
            if (terms != null) {
                val keys = terms.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = terms.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) {
                        glossaryMap[key.lowercase()] = value
                    }
                }
            }
            Log.d(TAG, "Loaded ${glossaryMap.size} game terms from $ASSET_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load glossary from assets: ${e.message}", e)
        }
    }

    /**
     * Checks if the text has an exact glossary match.
     */
    fun findExactMatch(text: String): String? {
        val key = text.trim().lowercase()
        return glossaryMap[key]
    }

    /**
     * Applies glossary term replacements on any embedded recognized words.
     */
    fun applyTermReplacements(text: String): String {
        var result = text
        for ((term, replacement) in glossaryMap) {
            val pattern = Pattern.compile("(?i)\\b${Pattern.quote(term)}\\b")
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }

    fun addCustomTerm(englishTerm: String, arabicTerm: String) {
        if (englishTerm.isNotBlank() && arabicTerm.isNotBlank()) {
            glossaryMap[englishTerm.trim().lowercase()] = arabicTerm.trim()
        }
    }

    fun size(): Int = glossaryMap.size
}

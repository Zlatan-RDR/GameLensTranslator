package com.zlatan.gamelens.profile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.overlay.CalibrationOverlay
import com.zlatan.gamelens.service.TranslationOverlayService
import org.json.JSONArray
import org.json.JSONObject

data class GameProfile(
    val id: String,
    val name: String,
    val roiLeft: Float = 0.10f,
    val roiTop: Float = 0.655f,
    val roiRight: Float = 0.90f,
    val roiBottom: Float = 0.95f,
    val displayMode: String = MainActivity.MODE_OFFSET_ABOVE,
    val fontSizeSp: Float = MainActivity.DEFAULT_FONT_SIZE_SP,
    val typewriterMode: Boolean = false,
    val patchName: String? = null,
    val textColorHex: String = "#FFFFFF",
    val bgOpacityPct: Int = 88,
    val textShadow: Boolean = true,
    val highContrastOcr: Boolean = false,
    val glossaryText: String = "",
    val autoSubtitleTracking: Boolean = false
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("roiLeft", roiLeft.toDouble())
            put("roiTop", roiTop.toDouble())
            put("roiRight", roiRight.toDouble())
            put("roiBottom", roiBottom.toDouble())
            put("displayMode", displayMode)
            put("fontSizeSp", fontSizeSp.toDouble())
            put("typewriterMode", typewriterMode)
            if (patchName != null) put("patchName", patchName)
            put("textColorHex", textColorHex)
            put("bgOpacityPct", bgOpacityPct)
            put("textShadow", textShadow)
            put("highContrastOcr", highContrastOcr)
            put("glossaryText", glossaryText)
            put("autoSubtitleTracking", autoSubtitleTracking)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): GameProfile {
            return GameProfile(
                id = obj.optString("id", "default"),
                name = obj.optString("name", "Default"),
                roiLeft = obj.optDouble("roiLeft", 0.10).toFloat(),
                roiTop = obj.optDouble("roiTop", 0.655).toFloat(),
                roiRight = obj.optDouble("roiRight", 0.90).toFloat(),
                roiBottom = obj.optDouble("roiBottom", 0.95).toFloat(),
                displayMode = obj.optString("displayMode", MainActivity.MODE_OFFSET_ABOVE),
                fontSizeSp = obj.optDouble("fontSizeSp", MainActivity.DEFAULT_FONT_SIZE_SP.toDouble()).toFloat(),
                typewriterMode = obj.optBoolean("typewriterMode", false),
                patchName = if (obj.has("patchName")) obj.optString("patchName") else null,
                textColorHex = obj.optString("textColorHex", "#FFFFFF"),
                bgOpacityPct = obj.optInt("bgOpacityPct", 88),
                textShadow = obj.optBoolean("textShadow", true),
                highContrastOcr = obj.optBoolean("highContrastOcr", false),
                glossaryText = obj.optString("glossaryText", ""),
                autoSubtitleTracking = obj.optBoolean("autoSubtitleTracking", false)
            )
        }
    }
}

object GameProfileManager {
    private const val KEY_PROFILES_JSON = "key_game_profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "key_active_game_profile_id"

    @Synchronized
    fun getProfiles(context: Context): List<GameProfile> {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES_JSON, null)
        if (raw.isNullOrBlank()) {
            return initDefaultProfiles(context, prefs)
        }

        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<GameProfile>()
            for (i in 0 until array.length()) {
                list.add(GameProfile.fromJsonObject(array.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                initDefaultProfiles(context, prefs)
            } else {
                list
            }
        } catch (_: Throwable) {
            initDefaultProfiles(context, prefs)
        }
    }

    private fun initDefaultProfiles(context: Context, prefs: SharedPreferences): List<GameProfile> {
        val curCalRect = CalibrationOverlay.getCalibratedRect(prefs)
        val curDisplayMode = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OFFSET_ABOVE) ?: MainActivity.MODE_OFFSET_ABOVE
        val curFontSize = prefs.getFloat(MainActivity.KEY_SUBTITLE_FONT_SIZE_SP, MainActivity.DEFAULT_FONT_SIZE_SP)
        val curTypewriter = prefs.getBoolean(MainActivity.KEY_TYPEWRITER_MODE, false)

        val defaultProfile = GameProfile(
            id = "default",
            name = "الوضع الافتراضي (Default)",
            roiLeft = curCalRect.left,
            roiTop = curCalRect.top,
            roiRight = curCalRect.right,
            roiBottom = curCalRect.bottom,
            displayMode = curDisplayMode,
            fontSizeSp = curFontSize,
            typewriterMode = curTypewriter
        )

        val hitmanProfile = GameProfile(
            id = "hitman",
            name = "Hitman: Absolution",
            roiLeft = 0.10f,
            roiTop = 0.655f,
            roiRight = 0.90f,
            roiBottom = 0.95f,
            displayMode = MainActivity.MODE_OFFSET_ABOVE,
            fontSizeSp = 18f,
            typewriterMode = false
        )

        val list = listOf(defaultProfile, hitmanProfile)
        saveProfilesList(prefs, list)
        if (!prefs.contains(KEY_ACTIVE_PROFILE_ID)) {
            prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, "default").apply()
        }
        return list
    }

    fun getActiveProfileId(context: Context): String {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_PROFILE_ID, "default") ?: "default"
    }

    fun getActiveProfile(context: Context): GameProfile {
        val profiles = getProfiles(context)
        val activeId = getActiveProfileId(context)
        return profiles.find { it.id == activeId } ?: profiles.firstOrNull() ?: GameProfile("default", "Default")
    }

    @Synchronized
    fun saveProfile(context: Context, profile: GameProfile) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val profiles = getProfiles(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles.add(profile)
        }
        saveProfilesList(prefs, profiles)
    }

    @Synchronized
    fun deleteProfile(context: Context, profileId: String): Boolean {
        if (profileId == "default") return false
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val profiles = getProfiles(context).toMutableList()
        val removed = profiles.removeAll { it.id == profileId }
        if (removed) {
            saveProfilesList(prefs, profiles)
            if (getActiveProfileId(context) == profileId) {
                applyProfile(context, "default")
            }
        }
        return removed
    }

    @Synchronized
    fun applyProfile(context: Context, profileId: String) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val profiles = getProfiles(context)
        val profile = profiles.find { it.id == profileId } ?: return

        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, profile.id)
            .putBoolean(CalibrationOverlay.PREFS_KEY_CALIBRATION_ENABLED, true)
            .putFloat(CalibrationOverlay.PREFS_KEY_CAL_LEFT, profile.roiLeft)
            .putFloat(CalibrationOverlay.PREFS_KEY_CAL_TOP, profile.roiTop)
            .putFloat(CalibrationOverlay.PREFS_KEY_CAL_RIGHT, profile.roiRight)
            .putFloat(CalibrationOverlay.PREFS_KEY_CAL_BOTTOM, profile.roiBottom)
            .putString(MainActivity.KEY_OVERLAY_RENDER_MODE, profile.displayMode)
            .putFloat(MainActivity.KEY_SUBTITLE_FONT_SIZE_SP, profile.fontSizeSp)
            .putBoolean(MainActivity.KEY_TYPEWRITER_MODE, profile.typewriterMode)
            .putString(MainActivity.KEY_SUBTITLE_TEXT_COLOR, profile.textColorHex)
            .putInt(MainActivity.KEY_SUBTITLE_BG_OPACITY, profile.bgOpacityPct)
            .putBoolean(MainActivity.KEY_SUBTITLE_TEXT_SHADOW, profile.textShadow)
            .putBoolean(MainActivity.KEY_AUTO_SUBTITLE_TRACKING, profile.autoSubtitleTracking)
            .apply()

        try {
            val intent = Intent(context, TranslationOverlayService::class.java).apply {
                action = TranslationOverlayService.ACTION_RELOAD_SETTINGS
                putExtra(TranslationOverlayService.EXTRA_FONT_SIZE_SP, profile.fontSizeSp)
            }
            context.startService(intent)
        } catch (_: Throwable) {}
    }

    private fun saveProfilesList(prefs: SharedPreferences, list: List<GameProfile>) {
        val array = JSONArray()
        for (p in list) {
            array.put(p.toJsonObject())
        }
        prefs.edit().putString(KEY_PROFILES_JSON, array.toString()).apply()
    }
}

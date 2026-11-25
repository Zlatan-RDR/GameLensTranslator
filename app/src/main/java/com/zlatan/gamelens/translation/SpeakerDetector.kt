package com.zlatan.gamelens.translation

import java.util.Locale

/**
 * Robust speaker detection for cinematic subtitles.
 * Extracts dialogue speakers formatted as "Speaker: Dialogue" while excluding
 * non-speaker HUD labels (NOTE, TIP, BRIEFING) and timestamps (00:15).
 */
object SpeakerDetector {

    private val SPEAKER_REGEX = Regex("^([A-Z][A-Za-z0-9 .'-]{1,22}):\\s+(.+)$")
    private val EXCLUDED_LABELS = setOf(
        "NOTE", "TIP", "BRIEFING", "OBJECTIVE", "MISSION", "HINT",
        "WARNING", "CAUTION", "STATUS", "TARGET", "UPDATE", "LOCATION",
        "TIME", "DATE", "ALERT", "INFO", "NOTICE", "GUIDE", "CHAPTER",
        "ACT", "LEVEL", "STAGE", "SCENE", "EPISODE", "CHECKPOINT"
    )
    private val TIMESTAMP_REGEX = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")

    data class Result(
        val hasSpeaker: Boolean,
        val speaker: String?,
        val dialogue: String
    )

    fun detect(text: String): Result {
        val trimmed = text.trim()
        val match = SPEAKER_REGEX.find(trimmed) ?: return Result(false, null, trimmed)
        val candidateSpeaker = match.groupValues[1].trim()
        val dialogue = match.groupValues[2].trim()

        if (EXCLUDED_LABELS.contains(candidateSpeaker.uppercase(Locale.US))) {
            return Result(false, null, trimmed)
        }
        if (TIMESTAMP_REGEX.matches(candidateSpeaker)) {
            return Result(false, null, trimmed)
        }
        if (candidateSpeaker.all { it.isDigit() || it == ':' || it == '.' }) {
            return Result(false, null, trimmed)
        }
        if (dialogue.isBlank()) {
            return Result(false, null, trimmed)
        }

        return Result(true, candidateSpeaker, dialogue)
    }
}

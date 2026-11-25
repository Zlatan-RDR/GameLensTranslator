package com.zlatan.gamelens.ocr

import android.graphics.Rect
import android.graphics.RectF
import java.util.Locale

/**
 * Dynamic Subtitle Tracker (Auto-ROI).
 * Dynamically discovers, locks onto, and tracks dialogue subtitle boxes across the screen,
 * automatically rejecting HUD clutter, minimaps, and status meters.
 */
class DynamicSubtitleTracker {

    enum class State {
        DISCOVERY,
        LOCKED
    }

    data class TrackerRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2

        fun toRect(): Rect = Rect(left, top, right, bottom)

        fun toRectF(screenW: Int, screenH: Int): RectF {
            val sw = screenW.coerceAtLeast(1).toFloat()
            val sh = screenH.coerceAtLeast(1).toFloat()
            return RectF(
                (left / sw).coerceIn(0f, 1f),
                (top / sh).coerceIn(0f, 1f),
                (right / sw).coerceIn(0f, 1f),
                (bottom / sh).coerceIn(0f, 1f)
            )
        }

        companion object {
            fun fromRect(rect: Rect): TrackerRect =
                TrackerRect(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    data class DetectedLine(
        val text: String,
        val box: TrackerRect
    ) {
        constructor(text: String, rect: Rect) : this(text, TrackerRect.fromRect(rect))
    }

    private var state: State = State.DISCOVERY
    private var lockedRoi: TrackerRect? = null
    private var lastEmptyTimestampMs: Long = 0L
    private var screenWidth: Int = 1920
    private var screenHeight: Int = 1080

    fun getState(): State = state

    fun getLockedRoi(): RectF? {
        val roi = lockedRoi ?: return null
        return roi.toRectF(screenWidth, screenHeight)
    }

    fun getLockedTrackerRect(): TrackerRect? = lockedRoi

    fun setScreenDimensions(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            screenWidth = w
            screenHeight = h
        }
    }

    /**
     * Checks if a bounding box or text represents HUD clutter:
     * - Corner narrow boxes (x < 0.12 or x > 0.88 with width < 0.30 relative to screenW)
     * - Standalone ammo counters/numbers (regex: ^x?\d+([/:\-]\d+)?x?$)
     * - HUD hotkeys and status tags: HP, MP, FPS, PING, LVL, TAB, ESC, [E], etc.
     * - Extreme top corner boxes (mini-map, health bars, top 15% and outside center 60%)
     */
    fun isHudClutter(box: TrackerRect, text: String, screenW: Int, screenH: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        val sw = screenW.coerceAtLeast(1).toFloat()
        val sh = screenH.coerceAtLeast(1).toFloat()
        val relLeft = box.left / sw
        val relRight = box.right / sw
        val relWidth = box.width / sw
        val relTop = box.top / sh

        // 1. Standalone ammo/health/timer counters (e.g. 100/100, 30, x5, 01:23)
        if (Regex("""^x?\d+(\s*[/\\:\-]\s*\d+)?x?$""").matches(trimmed)) return true

        // 2. HUD hotkeys and status tags
        if (Regex("""^\[?[A-Za-z0-9]{1,3}\]?$""").matches(trimmed) &&
            trimmed.uppercase(Locale.US) in HUD_TAGS
        ) return true

        // 3. Mini-map or top-corner health/ammo bars (top 15% and outside center 60%)
        if (relTop < 0.15f && (relLeft < 0.20f || relRight > 0.80f)) return true

        // 4. Narrow corner boxes (x < 0.12 or x > 0.88 with width < 0.30)
        if ((relLeft < 0.12f || relRight > 0.88f) && relWidth < 0.30f) return true

        return false
    }

    /**
     * Identifies whether text is dialogue candidate:
     * - >= 3 dictionary/dialogue words OR contains sentence punctuation (., ?, !, ..., ", —).
     * - Rejects pure HUD prompt or counter.
     */
    fun isDialogueCandidate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 3) return false
        if (OcrTextCleaner.isNoiseOrPrompt(trimmed)) return false

        val hasDialoguePunctuation = trimmed.contains('.') || trimmed.contains('?') ||
                trimmed.contains('!') || trimmed.contains('"') || trimmed.contains('—') ||
                trimmed.contains('…')

        val words = trimmed.split(Regex("""\s+""")).filter { word ->
            word.any { it.isLetter() }
        }

        if (words.size >= 3 || (hasDialoguePunctuation && words.size >= 2) || (words.size >= 1 && hasDialoguePunctuation && trimmed.length >= 8)) {
            return true
        }

        return false
    }

    /**
     * Evaluates candidate lines on discovery frame.
     * Computes padded bounding box (+12% horiz, +25% vert) and locks ROI.
     */
    fun onDiscoveryFrame(lines: List<DetectedLine>, screenW: Int, screenH: Int): TrackerRect? {
        setScreenDimensions(screenW, screenH)

        val dialogueLines = lines.filter { line ->
            !isHudClutter(line.box, line.text, screenW, screenH) && isDialogueCandidate(line.text)
        }

        if (dialogueLines.isEmpty()) return null

        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE

        for (dl in dialogueLines) {
            minL = minOf(minL, dl.box.left)
            minT = minOf(minT, dl.box.top)
            maxR = maxOf(maxR, dl.box.right)
            maxB = maxOf(maxB, dl.box.bottom)
        }

        val unionW = (maxR - minL).coerceAtLeast(1)
        val unionH = (maxB - minT).coerceAtLeast(1)

        val padX = (unionW * 0.12f).toInt().coerceAtLeast(16)
        val padY = (unionH * 0.25f).toInt().coerceAtLeast(12)

        val paddedL = (minL - padX).coerceIn(0, screenW - 1)
        val paddedT = (minT - padY).coerceIn(0, screenH - 1)
        val paddedR = (maxR + padX).coerceIn(paddedL + 1, screenW)
        val paddedB = (maxB + padY).coerceIn(paddedT + 1, screenH)

        val locked = TrackerRect(paddedL, paddedT, paddedR, paddedB)
        lockedRoi = locked
        state = State.LOCKED
        lastEmptyTimestampMs = 0L
        return locked
    }

    /**
     * Auto-grow: If dialogue text is within 15px of edge of current crop, expands ROI margins.
     */
    fun checkAutoGrow(textBoxInRoi: TrackerRect, roiW: Int, roiH: Int): TrackerRect? {
        val current = lockedRoi ?: return null
        val margin = 15

        var expandLeft = 0
        var expandRight = 0
        var expandTop = 0
        var expandBottom = 0

        if (textBoxInRoi.left < margin) {
            expandLeft = (roiW * 0.10f).toInt().coerceAtLeast(20)
        }
        if (textBoxInRoi.right > (roiW - margin)) {
            expandRight = (roiW * 0.10f).toInt().coerceAtLeast(20)
        }
        if (textBoxInRoi.top < margin) {
            expandTop = (roiH * 0.15f).toInt().coerceAtLeast(15)
        }
        if (textBoxInRoi.bottom > (roiH - margin)) {
            expandBottom = (roiH * 0.15f).toInt().coerceAtLeast(15)
        }

        if (expandLeft == 0 && expandRight == 0 && expandTop == 0 && expandBottom == 0) {
            return current
        }

        val newL = (current.left - expandLeft).coerceIn(0, screenWidth - 1)
        val newT = (current.top - expandTop).coerceIn(0, screenHeight - 1)
        val newR = (current.right + expandRight).coerceIn(newL + 1, screenWidth)
        val newB = (current.bottom + expandBottom).coerceIn(newT + 1, screenHeight)

        val updated = TrackerRect(newL, newT, newR, newB)
        lockedRoi = updated
        return updated
    }

    /**
     * Notifies tracker of frame status:
     * If empty for >= 2500ms AND motion elsewhere is detected, resets to DISCOVERY.
     */
    fun notifyFrameOcrStatus(isEmpty: Boolean, hasMotionElsewhere: Boolean, nowMs: Long) {
        if (state != State.LOCKED) return

        if (!isEmpty) {
            lastEmptyTimestampMs = 0L
            return
        }

        if (lastEmptyTimestampMs == 0L) {
            lastEmptyTimestampMs = nowMs
            return
        }

        val emptyDuration = nowMs - lastEmptyTimestampMs
        if (emptyDuration >= 2500L && hasMotionElsewhere) {
            reset()
        }
    }

    fun reset() {
        state = State.DISCOVERY
        lockedRoi = null
        lastEmptyTimestampMs = 0L
    }

    companion object {
        private val HUD_TAGS = setOf(
            "HP", "MP", "XP", "LVL", "FPS", "MS", "TAB", "ESC",
            "E", "F", "R", "Q", "V", "X", "Z", "C", "AP", "SP"
        )
    }
}

package com.zlatan.gamelens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

@SuppressLint("ViewConstructor")
class ArabicOverlayView(
    context: Context,
    val targetBox: Rect
) : AppCompatTextView(context) {

    init {
        // Enforce Native RTL Arabic text rendering
        textDirection = View.TEXT_DIRECTION_RTL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false

        val padHorizontal = (3 * resources.displayMetrics.density).toInt()
        val padVertical = (1 * resources.displayMetrics.density).toInt()
        setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
    }

    /**
     * Updates the content and styling of the overlay view using the translated Arabic text
     * and the sampled edge colors from the game surface.
     */
    fun bindTranslation(translatedText: String, colorScheme: SampledColorScheme) {
        text = translatedText
        setTextColor(colorScheme.textColor)

        // Seamless rounded background matching game background
        val cornerRadiusPx = 4f * resources.displayMetrics.density
        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(colorScheme.backgroundColor)
            cornerRadius = cornerRadiusPx
        }
        background = backgroundDrawable

        // Dynamic Text Auto-Sizing
        calculateOptimalTextSize(translatedText, targetBox.width(), targetBox.height())
    }

    private fun calculateOptimalTextSize(arabicText: String, availableWidthPx: Int, availableHeightPx: Int) {
        if (availableWidthPx <= 0 || availableHeightPx <= 0 || arabicText.isEmpty()) return

        val density = resources.displayMetrics.scaledDensity
        val minTextSizeSp = 9f
        val maxTextSizeSp = 22f

        // Initial estimate based on bounding box height
        var candidateSp = (availableHeightPx / density * 0.70f).coerceIn(minTextSizeSp, maxTextSizeSp)

        val paint = TextPaint(paint)
        val padPx = compoundPaddingLeft + compoundPaddingRight
        val usableWidth = (availableWidthPx - padPx).coerceAtLeast(10)

        // Decrement font size if measured width exceeds available box width
        while (candidateSp > minTextSizeSp) {
            paint.textSize = candidateSp * density
            val textWidth = paint.measureText(arabicText)
            if (textWidth <= usableWidth) {
                break
            }
            candidateSp -= 0.75f
        }

        setTextSize(TypedValue.COMPLEX_UNIT_SP, candidateSp)
    }
}

package com.zlatan.gamelens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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

        val padHorizontal = (8 * resources.displayMetrics.density).toInt()
        val padVertical = (3 * resources.displayMetrics.density).toInt()
        setPadding(padHorizontal, padVertical, padHorizontal, padVertical)

        // Text shadow for crisp legibility over dynamic game graphics
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    /**
     * Updates the content and styling of the overlay view using the translated Arabic text
     * and the sampled edge colors from the game surface.
     */
    fun bindTranslation(translatedText: String, colorScheme: SampledColorScheme) {
        text = translatedText
        setTextColor(Color.WHITE) // High contrast white text

        // Seamless high-contrast background with subtle border
        val cornerRadiusPx = 6f * resources.displayMetrics.density
        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Ensure strong opacity (#E6121212 or adaptive)
            val bg = if (Color.alpha(colorScheme.backgroundColor) < 200) {
                Color.argb(235, Color.red(colorScheme.backgroundColor), Color.green(colorScheme.backgroundColor), Color.blue(colorScheme.backgroundColor))
            } else {
                colorScheme.backgroundColor
            }
            setColor(bg)
            cornerRadius = cornerRadiusPx
            setStroke(1, Color.parseColor("#44FFFFFF"))
        }
        background = backgroundDrawable

        // Dynamic Text Auto-Sizing with guaranteed readable minimum
        calculateOptimalTextSize(translatedText, targetBox.width(), targetBox.height())
    }

    private fun calculateOptimalTextSize(arabicText: String, availableWidthPx: Int, availableHeightPx: Int) {
        if (availableWidthPx <= 0 || availableHeightPx <= 0 || arabicText.isEmpty()) return

        val density = resources.displayMetrics.scaledDensity
        val minTextSizeSp = 14f
        val maxTextSizeSp = 24f

        // Initial estimate based on bounding box height
        var candidateSp = (availableHeightPx / density * 0.85f).coerceIn(minTextSizeSp, maxTextSizeSp)

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
            candidateSp -= 0.5f
        }

        setTextSize(TypedValue.COMPLEX_UNIT_SP, candidateSp)
    }
}

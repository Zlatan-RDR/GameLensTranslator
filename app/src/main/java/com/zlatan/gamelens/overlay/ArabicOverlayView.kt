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
import com.zlatan.gamelens.MainActivity

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
        isFocusable = false
        isClickable = false

        val pad6dp = (6 * resources.displayMetrics.density).toInt()
        setPadding(pad6dp, pad6dp, pad6dp, pad6dp)

        // Text shadow for crisp legibility over dynamic game graphics
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    /**
     * Updates the content and styling of the overlay view using the translated Arabic text.
     * In Opaque Cover Mode (0xFF080A0F, alpha=255), completely conceals original English text underneath for clean native look.
     */
    fun bindTranslation(translatedText: String, colorScheme: SampledColorScheme? = null) {
        text = translatedText
        setTextColor(Color.WHITE) // High contrast crisp white text

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isOpaqueCover = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OPAQUE_COVER) == MainActivity.MODE_OPAQUE_COVER
        val bgColor = if (isOpaqueCover) 0xFF080A0F.toInt() else 0xD910141D.toInt()

        val cornerRadiusPx = 4f * resources.displayMetrics.density
        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = cornerRadiusPx
            if (!isOpaqueCover) {
                setStroke((1.2f * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.parseColor("#4DFFFFFF"))
            }
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

package com.zlatan.gamelens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.ocr.OcrBlock

class SeamlessOverlayRenderer(private val context: Context) {

    companion object {
        private const val TAG = "SeamlessRenderer"
        private val FALLBACK_SCHEME = SampledColorScheme(0xD9121212.toInt(), Color.WHITE)
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayContainer: FrameLayout? = null
    private var isContainerAttached = false
    private var isVisible = true

    private var subtitleView: TextView? = null
    private val hideSubtitleRunnable = Runnable {
        subtitleView?.visibility = View.GONE
    }

    init {
        initOverlayContainer()
    }

    @SuppressLint("RtlHardcoded")
    private fun initOverlayContainer() {
        if (overlayContainer != null) return

        // Explicit LTR layout direction prevents Android from inverting leftMargin in RTL locales
        val container = FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            isClickable = false
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 100% Touch Pass-Through flags: Zero touch interception, full-screen alignment
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        // Centered bottom cinematic subtitle bar for live game audio transcription
        val subView = TextView(context).apply {
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            val padH = (20 * context.resources.displayMetrics.density).toInt()
            val padV = (10 * context.resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xE60B0F17.toInt()) // Dark-translucent cinematic #E60B0F17
                cornerRadius = 10f * context.resources.displayMetrics.density
                setStroke((1.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.parseColor("#4DFFFFFF"))
            }
            background = bg
            visibility = View.GONE
        }

        val subParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (42 * context.resources.displayMetrics.density).toInt()
            leftMargin = (24 * context.resources.displayMetrics.density).toInt()
            rightMargin = (24 * context.resources.displayMetrics.density).toInt()
        }

        container.addView(subView, subParams)
        subtitleView = subView

        try {
            windowManager.addView(container, params)
            overlayContainer = container
            isContainerAttached = true
            Log.d(TAG, "SeamlessOverlayRenderer container attached to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay container to WindowManager: ${e.message}", e)
        }
    }

    private fun clearOcrViews() {
        val container = overlayContainer ?: return
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child !== subtitleView) {
                container.removeViewAt(i)
            }
        }
    }

    /**
     * Renders Arabic translations over the game screen at exact BoundingBox coordinates.
     * Uses explicit Gravity.TOP or Gravity.LEFT to avoid RTL coordinate inversion.
     * Maps coordinates proportionally when captured frame dimensions differ from screen metrics.
     * Always posted to MainLooper for thread-safe UI updates.
     */
    fun renderTranslations(
        blocks: List<OcrBlock>,
        screenW: Int = 0,
        screenH: Int = 0,
        bitmapW: Int = 0,
        bitmapH: Int = 0
    ) {
        mainHandler.post {
            if (!isContainerAttached || overlayContainer == null) {
                initOverlayContainer()
            }
            val container = overlayContainer ?: return@post
            if (!isContainerAttached) return@post

            clearOcrViews()

            if (!isVisible || blocks.isEmpty()) {
                return@post
            }

            val scaleX = if (bitmapW > 0 && screenW > 0) screenW.toFloat() / bitmapW.toFloat() else 1.0f
            val scaleY = if (bitmapH > 0 && screenH > 0) screenH.toFloat() / bitmapH.toFloat() else 1.0f

            for (block in blocks) {
                val arabicText = block.translatedText ?: continue
                if (arabicText.isBlank()) continue

                val box = block.boundingBox
                if (box.width() <= 5 || box.height() <= 5) continue

                val scaledLeft = (box.left * scaleX).toInt()
                val scaledTop = (box.top * scaleY).toInt()
                val scaledW = (box.width() * scaleX).toInt()
                val scaledH = (box.height() * scaleY).toInt()

                val padX = (6 * context.resources.displayMetrics.density).toInt()
                val padY = (6 * context.resources.displayMetrics.density).toInt()
                val targetW = scaledW + padX * 2
                val minBoxH = (24 * context.resources.displayMetrics.density).toInt()
                val targetH = (scaledH + padY * 2).coerceAtLeast(minBoxH)
                val targetLeft = (scaledLeft - padX).coerceAtLeast(0)
                val targetTop = (scaledTop - padY).coerceAtLeast(0)
                val renderBox = Rect(targetLeft, targetTop, targetLeft + targetW, targetTop + targetH)

                val colorScheme = block.colorScheme ?: FALLBACK_SCHEME

                val overlayView = ArabicOverlayView(context, renderBox).apply {
                    bindTranslation(arabicText, colorScheme)
                }

                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val isOffsetAbove = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OPAQUE_COVER) == MainActivity.MODE_OFFSET_ABOVE
                val finalTop = if (isOffsetAbove) {
                    (targetTop - targetH - (4 * context.resources.displayMetrics.density).toInt()).coerceAtLeast(0)
                } else {
                    targetTop
                }

                // Explicit Gravity.TOP or Gravity.LEFT guarantees exact absolute X/Y positioning in RTL
                val layoutParams = FrameLayout.LayoutParams(targetW, targetH).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    leftMargin = targetLeft
                    topMargin = finalTop
                }

                container.addView(overlayView, layoutParams)
            }
            _hasPinnedCards = blocks.isNotEmpty()
        }
    }

    @Volatile
    private var _hasPinnedCards = false

    fun hasPinnedMenuCards(): Boolean = _hasPinnedCards

    fun clearMenuCards() {
        mainHandler.post {
            clearOcrViews()
            _hasPinnedCards = false
        }
    }

    /**
     * Renders speech translations in the zero-flicker centered bottom cinematic subtitle bar.
     * Auto-hides after 2.2 seconds of silence so old lines never linger over game scenes.
     */
    fun renderSubtitle(arabicText: String) {
        mainHandler.post {
            if (!isContainerAttached || overlayContainer == null) {
                initOverlayContainer()
            }
            val sub = subtitleView ?: return@post
            if (arabicText.isBlank()) {
                sub.visibility = View.GONE
                return@post
            }
            sub.text = arabicText
            sub.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideSubtitleRunnable)
            mainHandler.postDelayed(hideSubtitleRunnable, 2200L)
        }
    }

    /**
     * Clears all floating translation boxes from the screen.
     */
    fun clear() {
        mainHandler.post {
            clearOcrViews()
            _hasPinnedCards = false
            subtitleView?.visibility = View.GONE
        }
    }

    /**
     * Controls visibility of the overlay layer.
     * Used right before frame capture to enforce the Anti-Feedback Loop.
     */
    fun setVisibility(visible: Boolean) {
        this.isVisible = visible
        mainHandler.post {
            overlayContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun release() {
        mainHandler.post {
            overlayContainer?.let { container ->
                try {
                    container.removeAllViews()
                    if (isContainerAttached) {
                        windowManager.removeView(container)
                        isContainerAttached = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error detaching overlay container: ${e.message}", e)
                }
            }
            overlayContainer = null
            subtitleView = null
        }
    }
}

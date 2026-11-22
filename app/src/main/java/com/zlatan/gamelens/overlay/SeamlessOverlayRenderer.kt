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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.ocr.OcrBlock

class SeamlessOverlayRenderer(private val context: Context) {

    companion object {
        private const val TAG = "SeamlessRenderer"
        private val FALLBACK_SCHEME = SampledColorScheme(Color.argb(255, 5, 7, 11), Color.WHITE)
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayContainer: FrameLayout? = null
    private var containerParams: WindowManager.LayoutParams? = null
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

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
            alpha = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        containerParams = params

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val initialFontSize = prefs.getFloat(MainActivity.KEY_SUBTITLE_FONT_SIZE_SP, 18f)

        // Centered bottom cinematic subtitle bar for live game audio transcription
        val subView = TextView(context).apply {
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, initialFontSize)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            val padH = (20 * context.resources.displayMetrics.density).toInt()
            val padV = (10 * context.resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(255, 5, 7, 11)) // Solid opaque #FF05070B
                cornerRadius = 10f * context.resources.displayMetrics.density
                alpha = 255
            }
            background = bg
            alpha = 1.0f
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

            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val isOpaqueCover = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OPAQUE_COVER) == MainActivity.MODE_OPAQUE_COVER

            if (isOpaqueCover) {
                // Dynamic Multi-Line Union Bounding Box for Opaque Cover Mode:
                // Encompass all detected subtitle lines in active region (Line 1 + Line 2 + Line 3)
                val validBlocks = blocks.filter {
                    val txt = it.translatedText
                    !txt.isNullOrBlank() && it.boundingBox.width() > 5 && it.boundingBox.height() > 5
                }

                if (validBlocks.isNotEmpty()) {
                    val unionRect = Rect()
                    var initialized = false
                    val combinedLines = mutableListOf<String>()

                    for (block in validBlocks) {
                        val box = block.boundingBox
                        val scaledLeft = (box.left * scaleX).toInt()
                        val scaledTop = (box.top * scaleY).toInt()
                        val scaledRight = (box.right * scaleX).toInt()
                        val scaledBottom = (box.bottom * scaleY).toInt()

                        if (!initialized) {
                            unionRect.set(scaledLeft, scaledTop, scaledRight, scaledBottom)
                            initialized = true
                        } else {
                            unionRect.union(scaledLeft, scaledTop, scaledRight, scaledBottom)
                        }
                        combinedLines.add(block.translatedText!!)
                    }

                    // Dynamic Multi-Line Union Bounding Box for Opaque Cover Mode:
                    // Spanning the union rect padded to width = 88% of screen width, centered horizontally
                    val pad10dp = (10 * context.resources.displayMetrics.density).toInt()
                    val targetW = if (screenW > 0) (0.88f * screenW).toInt() else (unionRect.width() + pad10dp * 2)
                    val targetLeft = if (screenW > 0) ((screenW - targetW) / 2).coerceAtLeast(0) else (unionRect.left - pad10dp).coerceAtLeast(0)
                    val targetTop = (unionRect.top - pad10dp).coerceAtLeast(0)
                    val targetBottom = (unionRect.bottom + pad10dp).coerceAtMost(if (screenH > 0) screenH else Int.MAX_VALUE)
                    val minBoxH = (28 * context.resources.displayMetrics.density).toInt()
                    val targetH = (targetBottom - targetTop).coerceAtLeast(minBoxH)
                    val renderBox = Rect(targetLeft, targetTop, targetLeft + targetW, targetTop + targetH)

                    val combinedArabicText = combinedLines.joinToString("\n")
                    val overlayView = ArabicOverlayView(context, renderBox).apply {
                        bindTranslation(combinedArabicText, null)
                        if (isOpaqueCover) {
                            alpha = 1.0f
                            background?.alpha = 255
                        }
                    }

                    val layoutParams = FrameLayout.LayoutParams(targetW, targetH).apply {
                        gravity = Gravity.TOP or Gravity.LEFT
                        leftMargin = targetLeft
                        topMargin = targetTop
                    }

                    container.addView(overlayView, layoutParams)
                }
            } else {
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

                    val finalTop = (targetTop - targetH - (4 * context.resources.displayMetrics.density).toInt()).coerceAtLeast(0)

                    // Explicit Gravity.TOP or Gravity.LEFT guarantees exact absolute X/Y positioning in RTL
                    val layoutParams = FrameLayout.LayoutParams(targetW, targetH).apply {
                        gravity = Gravity.TOP or Gravity.LEFT
                        leftMargin = targetLeft
                        topMargin = finalTop
                    }

                    container.addView(overlayView, layoutParams)
                }
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
     * Instantly clears all OCR views, pinned cards, and voice subtitle text.
     * Enforces complete clean state when switching between OCR and live audio modes.
     */
    fun clearAllOverlays() {
        mainHandler.post {
            clearOcrViews()
            _hasPinnedCards = false
            subtitleView?.visibility = View.GONE
            subtitleView?.text = ""
        }
    }

    /**
     * Re-synchronizes overlay window container layout metrics on display or orientation change.
     */
    fun updateScreenDimensions() {
        mainHandler.post {
            val container = overlayContainer ?: return@post
            if (!isContainerAttached) return@post
            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return@post
            try {
                windowManager.updateViewLayout(container, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update overlay view layout on config change: ${e.message}")
            }
        }
    }

    /**
     * Renders a sample solid-opaque cinematic Arabic subtitle card on screen for 3 seconds.
     * Allows 1-tap verification of overlay visibility and screen-space alignment over fullscreen games.
     */
    fun renderTestOverlay() {
        mainHandler.post {
            if (!isContainerAttached || overlayContainer == null) {
                initOverlayContainer()
            }
            val container = overlayContainer ?: return@post
            clearOcrViews()
            _hasPinnedCards = true

            val metrics = context.resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val density = metrics.density

            val targetW = (0.88f * screenW).toInt()
            val targetLeft = ((screenW - targetW) / 2).coerceAtLeast(0)
            val targetTop = (0.80f * screenH).toInt()
            val targetH = (56 * density).toInt()
            val renderBox = Rect(targetLeft, targetTop, targetLeft + targetW, targetTop + targetH)

            val testView = ArabicOverlayView(context, renderBox).apply {
                bindTranslation("فحص ظهور الترجمة: طبقة العرض السينمائية تعمل بنجاح 100%", null)
            }

            val layoutParams = FrameLayout.LayoutParams(targetW, targetH).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                leftMargin = targetLeft
                topMargin = targetTop
            }

            container.addView(testView, layoutParams)

            // Auto-dismiss test card after 3.0 seconds
            mainHandler.postDelayed({
                clearOcrViews()
                _hasPinnedCards = false
            }, 3000L)
        }
    }

    /**
     * Renders speech or OCR translations in the zero-flicker bottom cinematic subtitle bar.
     * Supports Opaque Cover Mode (100% solid dark 0xFF05070B over calibrated subtitle zone)
     * and Offset Above Mode (translucent bar positioned cleanly above bottom zone).
     * Auto-hides dynamically based on reading speed so old lines clear promptly between scenes.
     */
    fun renderSubtitle(arabicText: String, englishText: String = "") {
        mainHandler.post {
            if (!isContainerAttached || overlayContainer == null) {
                initOverlayContainer()
            }
            val sub = subtitleView ?: return@post
            if (arabicText.isBlank()) {
                sub.visibility = View.GONE
                return@post
            }

            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val isOpaqueCover = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OPAQUE_COVER) == MainActivity.MODE_OPAQUE_COVER
            val calRect = CalibrationOverlay.getCalibratedRect(prefs)
            val fontSizeSp = prefs.getFloat(MainActivity.KEY_SUBTITLE_FONT_SIZE_SP, 18f)
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

            val metrics = context.resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val density = metrics.density

            val zoneLeft = (calRect.left * screenW).toInt()
            val zoneTop = (calRect.top * screenH).toInt()
            val zoneRight = (calRect.right * screenW).toInt()
            val zoneBottom = (calRect.bottom * screenH).toInt()
            val zoneW = (zoneRight - zoneLeft).coerceAtLeast(100)
            val zoneH = (zoneBottom - zoneTop).coerceAtLeast((28 * density).toInt())

            if (isOpaqueCover) {
                containerParams?.let { cp ->
                    if (cp.alpha != 1.0f) {
                        cp.alpha = 1.0f
                        try {
                            if (isContainerAttached && overlayContainer != null) {
                                windowManager.updateViewLayout(overlayContainer, cp)
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 100% solid opaque dark (0xFF05070B, alpha = 255) positioned directly over calibrated subtitle zone
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.argb(255, 5, 7, 11)) // 0xFF05070B
                    cornerRadius = 8f * density
                    alpha = 255
                }
                sub.background = bg
                sub.alpha = 1.0f
                sub.background?.alpha = 255
                sub.setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())

                val opaqueTop = (calRect.top * screenH).toInt()
                val opaqueBottom = (calRect.bottom * screenH).toInt()
                val opaqueH = (opaqueBottom - opaqueTop).coerceAtLeast((56 * density).toInt())
                val opaqueW = ((calRect.right - calRect.left) * screenW).toInt().coerceAtLeast((100 * density).toInt())
                val opaqueLeft = (calRect.left * screenW).toInt()

                val layoutParams = FrameLayout.LayoutParams(opaqueW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = opaqueLeft
                    topMargin = opaqueTop
                }
                sub.minimumHeight = opaqueH
                sub.layoutParams = layoutParams
            } else {
                // Offset Above Mode: position the Arabic subtitle bar cleanly above the calibrated bottom subtitle zone with zero overlap
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.argb(255, 5, 7, 11)) // Solid opaque #FF05070B
                    cornerRadius = 10f * density
                    alpha = 255
                    setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.parseColor("#4DFFFFFF"))
                }
                sub.background = bg
                sub.alpha = 1.0f
                sub.background?.alpha = 255
                sub.setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())

                val targetTop = (zoneTop - zoneH - (8 * density).toInt()).coerceAtLeast(0)
                val layoutParams = FrameLayout.LayoutParams(zoneW, zoneH).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = zoneLeft
                    topMargin = targetTop
                }
                sub.layoutParams = layoutParams
            }

            sub.text = arabicText
            sub.visibility = View.VISIBLE
            com.zlatan.gamelens.diag.SubtitleHistoryManager.addEntry(englishText, arabicText)
            mainHandler.removeCallbacks(hideSubtitleRunnable)
            val wordCount = arabicText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            val charBasedMs = 900L + (arabicText.length * 62L)
            val wordBasedMs = 900L + (wordCount * 340L)
            val holdMs = maxOf(charBasedMs, wordBasedMs).coerceIn(1800L, 9000L)
            mainHandler.postDelayed(hideSubtitleRunnable, holdMs)
        }
    }

    fun isSubtitleVisible(): Boolean = (subtitleView?.visibility == View.VISIBLE) || _hasPinnedCards

    fun updateFontSize(fontSizeSp: Float) {
        mainHandler.post {
            subtitleView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        }
    }

    fun showVoiceSubtitle(arabicText: String) = renderSubtitle(arabicText)

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
    fun setInvisibleForCapture() {
        this.isVisible = false
        overlayContainer?.visibility = View.INVISIBLE
    }

    fun restoreVisibilityAfterCapture() {
        this.isVisible = true
        overlayContainer?.visibility = View.VISIBLE
    }

    fun setVisibility(visible: Boolean) {
        this.isVisible = visible
        mainHandler.post {
            overlayContainer?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
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

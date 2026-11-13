package com.zlatan.gamelens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.zlatan.gamelens.ocr.OcrBlock

class SeamlessOverlayRenderer(private val context: Context) {

    companion object {
        private const val TAG = "SeamlessRenderer"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayContainer: FrameLayout? = null
    private var isContainerAttached = false
    private var isVisible = true

    init {
        initOverlayContainer()
    }

    @SuppressLint("RtlHardcoded")
    private fun initOverlayContainer() {
        if (overlayContainer != null) return

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Strictly Touch Pass-Through flags: Zero touch interception
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        }

        try {
            windowManager.addView(container, params)
            overlayContainer = container
            isContainerAttached = true
            Log.d(TAG, "SeamlessOverlayRenderer container attached to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay container to WindowManager: ${e.message}", e)
        }
    }

    /**
     * Renders Arabic translations over the game screen at exact BoundingBox coordinates.
     * Always posted to MainLooper for thread-safe UI updates.
     */
    fun renderTranslations(blocks: List<OcrBlock>, screenBitmap: Bitmap?) {
        mainHandler.post {
            if (!isContainerAttached || overlayContainer == null) {
                initOverlayContainer()
            }
            val container = overlayContainer ?: return@post
            if (!isContainerAttached) return@post

            container.removeAllViews()

            if (!isVisible || blocks.isEmpty()) {
                return@post
            }

            for (block in blocks) {
                val arabicText = block.translatedText ?: continue
                if (arabicText.isBlank()) continue

                val box = block.boundingBox
                if (box.width() <= 5 || box.height() <= 5) continue

                val colorScheme = EdgeColorSampler.sampleEdgeColors(screenBitmap, box)

                val overlayView = ArabicOverlayView(context, box).apply {
                    bindTranslation(arabicText, colorScheme)
                }

                val layoutParams = FrameLayout.LayoutParams(box.width(), box.height()).apply {
                    leftMargin = box.left
                    topMargin = box.top
                }

                container.addView(overlayView, layoutParams)
            }
        }
    }

    /**
     * Clears all floating translation boxes from the screen.
     */
    fun clear() {
        mainHandler.post {
            overlayContainer?.removeAllViews()
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
        }
    }
}

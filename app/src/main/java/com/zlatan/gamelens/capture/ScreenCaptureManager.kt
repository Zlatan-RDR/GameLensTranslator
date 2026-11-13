package com.zlatan.gamelens.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer

class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private var screenWidth: Int,
    private var screenHeight: Int,
    private var screenDensityDpi: Int
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "GameLensCaptureDisplay"
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val backgroundHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection onStop invoked by system")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.d(TAG, "MediaProjection onCapturedContentResize: ${width}x${height}")
            if (width > 0 && height > 0) {
                updateDimensions(width, height, screenDensityDpi)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.d(TAG, "MediaProjection onCapturedContentVisibilityChanged: isVisible=$isVisible")
        }
    }

    init {
        // MANDATORY FOR ANDROID 14/15/16: Register callback BEFORE createVirtualDisplay
        try {
            mediaProjection.registerCallback(projectionCallback, backgroundHandler)
            Log.d(TAG, "MediaProjection.Callback registered BEFORE VirtualDisplay creation")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MediaProjection.Callback: ${e.message}", e)
        }
        setupVirtualDisplay()
    }

    @Synchronized
    private fun setupVirtualDisplay() {
        if (virtualDisplay != null) return
        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )
            Log.d(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay: ${e.message}", e)
        }
    }

    /**
     * Resizes the existing VirtualDisplay on rotation without re-creating it,
     * complying strictly with Android 15/16 MediaProjection lifecycle rules.
     */
    @Synchronized
    fun updateDimensions(newWidth: Int, newHeight: Int, newDpi: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        if (screenWidth == newWidth && screenHeight == newHeight && screenDensityDpi == newDpi) return

        Log.d(TAG, "Updating capture dimensions: ${screenWidth}x${screenHeight} -> ${newWidth}x${newHeight} @ ${newDpi}dpi")
        this.screenWidth = newWidth
        this.screenHeight = newHeight
        this.screenDensityDpi = newDpi

        try {
            val oldReader = imageReader
            val newReader = ImageReader.newInstance(
                newWidth,
                newHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = newReader

            virtualDisplay?.let { vd ->
                vd.resize(newWidth, newHeight, newDpi)
                vd.surface = newReader.surface
                Log.d(TAG, "VirtualDisplay resized and surface switched to new ImageReader successfully")
            } ?: run {
                setupVirtualDisplay()
            }

            oldReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating capture dimensions: ${e.message}", e)
        }
    }

    /**
     * Grabs the latest frame from the ImageReader and returns it as a Bitmap.
     * Uses actual Image dimensions and handles pixel stride and row padding safely.
     */
    @Synchronized
    fun acquireLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null

        return try {
            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * imgW

            val bitmap = Bitmap.createBitmap(
                imgW + rowPadding / pixelStride,
                imgH,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, imgW, imgH)
                bitmap.recycle()
                cleanBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting Image to Bitmap: ${e.message}", e)
            null
        } finally {
            image.close()
        }
    }

    fun getDimensions(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    @Synchronized
    fun release() {
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            Log.d(TAG, "VirtualDisplay and ImageReader released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ScreenCaptureManager: ${e.message}", e)
        }
    }
}

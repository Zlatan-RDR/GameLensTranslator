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
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensityDpi: Int
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "GameLensCaptureDisplay"
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val backgroundHandler = Handler(Looper.getMainLooper())

    init {
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        try {
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
            Log.e(TAG, "Failed to initialize VirtualDisplay: ${e.message}", e)
        }
    }

    /**
     * Grabs the latest frame from the ImageReader and returns it as a Bitmap.
     * Accurately handles pixel stride and row padding to prevent slanted/corrupted frames.
     */
    fun acquireLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null

        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cleanBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image buffer to Bitmap: ${e.message}", e)
            null
        } finally {
            image.close()
        }
    }

    fun release() {
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

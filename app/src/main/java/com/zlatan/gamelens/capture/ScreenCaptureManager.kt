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
import com.zlatan.gamelens.diag.DiagnosticLogger
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
            DiagnosticLogger.log("MediaProjection: onStop")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.d(TAG, "MediaProjection onCapturedContentResize: ${width}x${height}")
            DiagnosticLogger.log("MediaProjection resize: ${width}x${height}")
            if (width > 0 && height > 0) {
                updateDimensions(width, height, screenDensityDpi)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.d(TAG, "MediaProjection onCapturedContentVisibilityChanged: isVisible=$isVisible")
            DiagnosticLogger.log("MediaProjection visibility: $isVisible")
        }
    }

    init {
        // MANDATORY FOR ANDROID 14/15/16: Register callback BEFORE createVirtualDisplay
        try {
            mediaProjection.registerCallback(projectionCallback, backgroundHandler)
            DiagnosticLogger.log("MediaProjection callback registered")
            Log.d(TAG, "MediaProjection.Callback registered BEFORE VirtualDisplay creation")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register MediaProjection.Callback: ${t.message}", t)
            DiagnosticLogger.log("RegisterCallback error: ${t.javaClass.simpleName} - ${t.message}")
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
            DiagnosticLogger.log("VirtualDisplay created: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")
            Log.d(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create VirtualDisplay: ${t.message}", t)
            DiagnosticLogger.log("VirtualDisplay create error: ${t.javaClass.simpleName} - ${t.message}")
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
        DiagnosticLogger.log("Capture resize: ${screenWidth}x${screenHeight} -> ${newWidth}x${newHeight}")
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
        } catch (t: Throwable) {
            Log.e(TAG, "Error updating capture dimensions: ${t.message}", t)
            DiagnosticLogger.log("Capture update error: ${t.javaClass.simpleName} - ${t.message}")
        }
    }

    /**
     * Grabs the latest frame from the ImageReader and returns it as a Bitmap.
     * Safely handles Qualcomm Adreno GPU row padding to prevent BufferUnderflowException.
     */
    @Synchronized
    fun acquireLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = try {
            reader.acquireLatestImage() ?: return null
        } catch (t: Throwable) {
            DiagnosticLogger.log("acquireLatestImage error: ${t.javaClass.simpleName} - ${t.message}")
            return null
        }

        var tempBitmap: Bitmap? = null
        var cleanBitmap: Bitmap? = null

        return try {
            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            if (planes.isEmpty()) return null

            val plane = planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * imgW
            val bufferWidth = imgW + (rowPadding / pixelStride)
            val requiredBytes = bufferWidth * imgH * 4

            // Adreno GPU buffer padding fix: if driver does not allocate trailing padding on the last row,
            // copy available bytes into a direct buffer of exact requiredBytes to prevent BufferUnderflowException
            val safeBuffer = if (buffer.remaining() >= requiredBytes) {
                buffer
            } else {
                val padded = ByteBuffer.allocateDirect(requiredBytes)
                buffer.rewind()
                padded.put(buffer)
                padded.rewind()
                padded
            }

            tempBitmap = Bitmap.createBitmap(
                bufferWidth,
                imgH,
                Bitmap.Config.ARGB_8888
            )
            tempBitmap.copyPixelsFromBuffer(safeBuffer)

            if (rowPadding == 0 && bufferWidth == imgW) {
                cleanBitmap = tempBitmap
                tempBitmap = null // transfer ownership
            } else {
                cleanBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, imgW, imgH)
            }
            cleanBitmap
        } catch (t: Throwable) {
            Log.e(TAG, "Error converting Image to Bitmap: ${t.message}", t)
            DiagnosticLogger.log("Image->Bitmap error: ${t.javaClass.simpleName} - ${t.message}")
            null
        } finally {
            // Let ART garbage collector reclaim bitmaps safely without risking native libhwui SIGABRT
            tempBitmap = null
            try {
                image.close()
            } catch (_: Throwable) {}
        }
    }

    fun getDimensions(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    @Synchronized
    fun release() {
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {}
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            Log.d(TAG, "VirtualDisplay and ImageReader released successfully")
            DiagnosticLogger.log("Capture manager released")
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing ScreenCaptureManager: ${t.message}", t)
        }
    }
}

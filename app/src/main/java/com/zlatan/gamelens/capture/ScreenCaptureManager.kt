package com.zlatan.gamelens.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        // 16 sample points across upper-right / right-half: x in 60%..92%, y in 10%..85%
        private val RIGHT_HALF_PROBE_X = floatArrayOf(0.65f, 0.74f, 0.83f, 0.92f)
        private val RIGHT_HALF_PROBE_Y = floatArrayOf(0.15f, 0.38f, 0.62f, 0.85f)

        // 12 sample points across upper-left: x in 8%..42%, y in 10%..60%
        private val TOP_LEFT_PROBE_X = floatArrayOf(0.12f, 0.26f, 0.40f)
        private val TOP_LEFT_PROBE_Y = floatArrayOf(0.15f, 0.30f, 0.45f, 0.60f)

        // 12 sample points across bottom-left strip: x in 8%..42%, y in 86%..97%
        private val BOT_LEFT_PROBE_X = floatArrayOf(0.12f, 0.26f, 0.40f)
        private val BOT_LEFT_PROBE_Y = floatArrayOf(0.88f, 0.91f, 0.94f, 0.97f)

        /**
         * Computes hardware-scaled dimensions capping the longest edge to max 1440px
         * while preserving exact aspect ratio.
         */
        internal fun computeScaledDimensions(rawW: Int, rawH: Int, maxEdge: Int = 1440): Pair<Int, Int> {
            val longest = maxOf(rawW, rawH)
            if (longest <= maxEdge) return Pair(rawW, rawH)
            val scale = maxEdge.toFloat() / longest.toFloat()
            val scaledW = Math.round(rawW * scale).coerceAtLeast(1)
            val scaledH = Math.round(rawH * scale).coerceAtLeast(1)
            return Pair(scaledW, scaledH)
        }

        /**
         * Fast row-strided RGBA copy from an ImageReader ByteBuffer into destination ByteBuffer.
         * Boundary-safe against GPU/Adreno row padding: does NOT mutate src.limit(),
         * verifies total buffer limits upfront, and restores src.position(0) cleanly.
         */
        internal fun copyRoiRgbaRows(
            src: ByteBuffer,
            rowStride: Int,
            roiLeft: Int,
            roiTop: Int,
            roiWidth: Int,
            roiHeight: Int,
            dst: ByteBuffer,
            rowScratch: ByteArray? = null
        ): Boolean {
            if (roiWidth <= 0 || roiHeight <= 0 || roiLeft < 0 || roiTop < 0) return false
            val roiRowBytes = roiWidth * 4
            val maxOffsetNeeded = (roiTop + roiHeight - 1).toLong() * rowStride + (roiLeft + roiWidth).toLong() * 4L
            val bufLimit = src.limit().toLong()
            if (maxOffsetNeeded > bufLimit) return false
            if (dst.remaining() < roiRowBytes * roiHeight) return false

            val scratch = if (rowScratch != null && rowScratch.size >= roiRowBytes) rowScratch else ByteArray(roiRowBytes)
            return try {
                for (row in 0 until roiHeight) {
                    val srcOffset = (roiTop + row) * rowStride + roiLeft * 4
                    src.position(srcOffset)
                    src.get(scratch, 0, roiRowBytes)
                    dst.put(scratch, 0, roiRowBytes)
                }
                true
            } catch (_: Throwable) {
                false
            } finally {
                src.position(0)
            }
        }

        /**
         * Fast row-strided RGBA copy from an ImageReader ByteBuffer into destination ByteArray.
         */
        internal fun copyRoiRgbaRows(
            src: ByteBuffer,
            rowStride: Int,
            roiLeft: Int,
            roiTop: Int,
            roiWidth: Int,
            roiHeight: Int,
            dst: ByteArray,
            rowScratch: ByteArray? = null
        ): Boolean {
            if (roiWidth <= 0 || roiHeight <= 0 || roiLeft < 0 || roiTop < 0) return false
            val roiRowBytes = roiWidth * 4
            val maxOffsetNeeded = (roiTop + roiHeight - 1).toLong() * rowStride + (roiLeft + roiWidth).toLong() * 4L
            val bufLimit = src.limit().toLong()
            if (maxOffsetNeeded > bufLimit) return false
            if (dst.size < roiRowBytes * roiHeight) return false

            return try {
                for (row in 0 until roiHeight) {
                    val srcOffset = (roiTop + row) * rowStride + roiLeft * 4
                    src.position(srcOffset)
                    src.get(dst, row * roiRowBytes, roiRowBytes)
                }
                true
            } catch (_: Throwable) {
                false
            } finally {
                src.position(0)
            }
        }
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var hasLoggedRoiSuccess = false
    private var pendingResizeRunnable: Runnable? = null
    private var lastLetterboxHealTimestamp: Long = 0L
    private var letterboxHealCount: Int = 0
    private var hasLoggedGeomAfterRebind: Boolean = false
    private var lastRebindReason: String = "init"

    private var reusableRoiBuffer: ByteBuffer? = null
    private var reusableRoiBitmap: Bitmap? = null
    private var reusableRowScratch: ByteArray? = null
    private var lastRoiW: Int = 0
    private var lastRoiH: Int = 0

    @Volatile
    private var isReleased: Boolean = false
    private var suppressResizeCallbackUntilMs: Long = 0L
    private var lastReboundWidth: Int = 0
    private var lastReboundHeight: Int = 0
    private var lastReboundTimestampMs: Long = 0L
    private var lastUpdateDimensionsTimestampMs: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection onStop invoked by system")
            DiagnosticLogger.log("MediaProjection: onStop")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (isReleased) return
            Log.d(TAG, "MediaProjection onCapturedContentResize: ${width}x${height}")
            DiagnosticLogger.log("MediaProjection resize: ${width}x${height}")
            if (width <= 0 || height <= 0) return

            val now = SystemClock.elapsedRealtime()

            // 1. 350ms cooldown between updateDimensions and onCapturedContentResize to stop ping-pong storms
            if (now - lastUpdateDimensionsTimestampMs < 350L) {
                Log.d(TAG, "Ignoring onCapturedContentResize during 350ms cooldown after updateDimensions: ${width}x${height}")
                DiagnosticLogger.log("Ignoring resize in cooldown (${now - lastUpdateDimensionsTimestampMs}ms < 350ms): ${width}x${height}")
                return
            }

            // 2. Check current actual WindowManager/display orientation to prevent flipping to a stale orientation
            val isCurrentOrientationLandscape = try {
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            } catch (_: Throwable) {
                screenWidth > screenHeight
            }
            val isIncomingLandscape = width > height
            if (isCurrentOrientationLandscape != isIncomingLandscape) {
                Log.d(TAG, "Ignoring stale onCapturedContentResize (${width}x${height}) contradicting display orientation (isLandscape=$isCurrentOrientationLandscape)")
                DiagnosticLogger.log("Ignoring stale resize (${width}x${height}) vs orientation (isLandscape=$isCurrentOrientationLandscape)")
                return
            }

            if (width == screenWidth && height == screenHeight) {
                if (now < suppressResizeCallbackUntilMs) {
                    Log.d(TAG, "Ignoring self-echo onCapturedContentResize: ${width}x${height}")
                    return
                }
                if (width == lastReboundWidth && height == lastReboundHeight && (now - lastReboundTimestampMs < 5000L)) {
                    Log.d(TAG, "Ignoring duplicate onCapturedContentResize within 5000ms: ${width}x${height}")
                    return
                }
            }

            pendingResizeRunnable?.let { backgroundHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!isReleased) {
                    forceRebindVirtualDisplay(width, height, "onCapturedContentResize")
                }
            }
            pendingResizeRunnable = runnable
            backgroundHandler.postDelayed(runnable, 180L)
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
            val (capW, capH) = computeScaledDimensions(screenWidth, screenHeight)
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                capW,
                capH,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                capW,
                capH,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )
            DiagnosticLogger.log("VirtualDisplay created: ${capW}x${capH} (raw=${screenWidth}x${screenHeight}) @ ${screenDensityDpi}dpi")
            Log.d(TAG, "VirtualDisplay created: ${capW}x${capH} (raw=${screenWidth}x${screenHeight}) @ ${screenDensityDpi}dpi")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create VirtualDisplay: ${t.message}", t)
            DiagnosticLogger.log("VirtualDisplay create error: ${t.javaClass.simpleName} - ${t.message}")
        }
    }

    /**
     * Force-rebinds the VirtualDisplay projection matrix without re-creating it,
     * complying strictly with Android 15/16 MediaProjection lifecycle rules.
     * Busts SurfaceFlinger/DisplayManagerService cached projection matrices via dimension/DPI nudging.
     */
    @Synchronized
    fun forceRebindVirtualDisplay(targetW: Int, targetH: Int, reason: String) {
        if (isReleased || targetW <= 0 || targetH <= 0) return
        val vd = virtualDisplay
        if (vd == null) {
            Log.w(TAG, "forceRebindVirtualDisplay aborted: VirtualDisplay is null or released (reason: $reason)")
            return
        }

        val (capW, capH) = computeScaledDimensions(targetW, targetH)
        Log.i(TAG, "forceRebindVirtualDisplay: ${screenWidth}x${screenHeight} -> ${targetW}x${targetH} (scaled: ${capW}x${capH}) @ ${screenDensityDpi}dpi (reason: $reason)")
        DiagnosticLogger.log("VirtualDisplay rebind [$reason]: ${screenWidth}x${screenHeight} -> ${targetW}x${targetH} (scaled: ${capW}x${capH})")

        this.screenWidth = targetW
        this.screenHeight = targetH
        this.lastReboundWidth = targetW
        this.lastReboundHeight = targetH
        this.lastReboundTimestampMs = SystemClock.elapsedRealtime()
        hasLoggedRoiSuccess = false
        hasLoggedGeomAfterRebind = false
        lastRebindReason = reason
        clearFullScreenCache()

        try {
            // 1. Detach old surface first
            vd.surface = null

            // 2. Invalidate DisplayManagerService / SurfaceFlinger projection cache via real dimension/DPI nudge
            suppressResizeCallbackUntilMs = SystemClock.elapsedRealtime() + 800L
            val nudgeW = (capW - 2).coerceAtLeast(16)
            val nudgeDpi = (screenDensityDpi - 1).coerceAtLeast(120)
            vd.resize(nudgeW, capH, nudgeDpi)
            vd.resize(capW, capH, screenDensityDpi)

            // 3. Close old ImageReader, create a fresh ImageReader, and attach surface
            val oldReader = imageReader
            val newReader = ImageReader.newInstance(
                capW,
                capH,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = newReader
            vd.surface = newReader.surface

            try {
                oldReader?.close()
            } catch (_: Throwable) {}

            Log.i(TAG, "VirtualDisplay rebind completed successfully ($reason)")
            DiagnosticLogger.log("VirtualDisplay rebind success: ${capW}x${capH} ($reason)")
        } catch (t: Throwable) {
            Log.e(TAG, "Error during forceRebindVirtualDisplay: ${t.message}", t)
            DiagnosticLogger.log("VirtualDisplay rebind error: ${t.javaClass.simpleName} - ${t.message}")
        }
    }

    /**
     * Resizes the existing VirtualDisplay on rotation without re-creating it,
     * complying strictly with Android 15/16 MediaProjection lifecycle rules.
     */
    @Synchronized
    fun updateDimensions(newWidth: Int, newHeight: Int, newDpi: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        this.lastUpdateDimensionsTimestampMs = SystemClock.elapsedRealtime()
        this.screenDensityDpi = newDpi
        if (screenWidth != newWidth || screenHeight != newHeight) {
            forceRebindVirtualDisplay(newWidth, newHeight, "updateDimensions")
        }
    }

    data class FrameProbeResult(
        val isStuckLetterbox: Boolean,
        val rightHalfNonBlackPct: Int,
        val topLeftNonBlackPct: Int,
        val botLeftNonBlackPct: Int
    )

    private fun probeFrameGeometry(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        bufLimit: Int
    ): FrameProbeResult {
        if (width <= height) {
            return FrameProbeResult(isStuckLetterbox = false, 100, 100, 100)
        }

        var rightNonBlack = 0
        var rightTotal = 0
        for (fx in RIGHT_HALF_PROBE_X) {
            val px = (fx * width).toInt().coerceIn(0, width - 1)
            for (fy in RIGHT_HALF_PROBE_Y) {
                val py = (fy * height).toInt().coerceIn(0, height - 1)
                val offset = py * rowStride + px * pixelStride
                if (offset >= 0 && offset + 3 < bufLimit) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    if (r > 0 || g > 0 || b > 0) rightNonBlack++
                    rightTotal++
                }
            }
        }

        var tlNonBlack = 0
        var tlTotal = 0
        for (fx in TOP_LEFT_PROBE_X) {
            val px = (fx * width).toInt().coerceIn(0, width - 1)
            for (fy in TOP_LEFT_PROBE_Y) {
                val py = (fy * height).toInt().coerceIn(0, height - 1)
                val offset = py * rowStride + px * pixelStride
                if (offset >= 0 && offset + 3 < bufLimit) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    if (r > 0 || g > 0 || b > 0) tlNonBlack++
                    tlTotal++
                }
            }
        }

        var blNonBlack = 0
        var blTotal = 0
        for (fx in BOT_LEFT_PROBE_X) {
            val px = (fx * width).toInt().coerceIn(0, width - 1)
            for (fy in BOT_LEFT_PROBE_Y) {
                val py = (fy * height).toInt().coerceIn(0, height - 1)
                val offset = py * rowStride + px * pixelStride
                if (offset >= 0 && offset + 3 < bufLimit) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    if (r + g + b > 30) blNonBlack++
                    blTotal++
                }
            }
        }

        val rPct = if (rightTotal > 0) (rightNonBlack * 100) / rightTotal else 0
        val tlPct = if (tlTotal > 0) (tlNonBlack * 100) / tlTotal else 0
        val blPct = if (blTotal > 0) (blNonBlack * 100) / blTotal else 0

        // Stuck portrait-letterbox condition: 100% of right half is black (rPct == 0),
        // 100% of top left is black (tlPct == 0), and bottom left has content (blPct > 0)
        val isStuck = (rPct == 0 && tlPct == 0 && blPct > 0)
        return FrameProbeResult(isStuck, rPct, tlPct, blPct)
    }

    private fun triggerLetterboxSelfHeal(width: Int, height: Int) {
        if (isReleased) return
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastLetterboxHealTimestamp >= 1500L && letterboxHealCount < 5) {
            letterboxHealCount++
            lastLetterboxHealTimestamp = nowMs
            backgroundHandler.post {
                if (!isReleased) {
                    forceRebindVirtualDisplay(width, height, "self-heal-letterbox (attempt $letterboxHealCount/5)")
                }
            }
        }
    }

    /**
     * Drains any stale queued frame from the ImageReader before capturing a fresh composited frame.
     */
    @Synchronized
    fun drainStaleFrame() {
        try {
            imageReader?.acquireLatestImage()?.close()
        } catch (_: Throwable) {}
    }

    /**
     * Grabs a cropped sub-region (ROI) directly from the ImageReader without allocating
     * the full-screen (1080x2400) Bitmap, cutting memory allocations by >80% and reducing latency.
     * Bounds-safe against Qualcomm Adreno buffer limits and unmutated buffer states.
     */
    @Synchronized
    fun acquireCroppedRoi(calRect: RectF): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = try {
            reader.acquireLatestImage() ?: return null
        } catch (t: Throwable) {
            DiagnosticLogger.log("acquireCroppedRoi error: ${t.javaClass.simpleName} - ${t.message}")
            return null
        }

        return try {
            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            if (planes.isEmpty()) return null

            val clampedLeft = calRect.left.coerceIn(0f, 1f)
            val clampedTop = calRect.top.coerceIn(0f, 1f)
            val clampedRight = calRect.right.coerceIn(clampedLeft, 1f)
            val clampedBottom = calRect.bottom.coerceIn(clampedTop, 1f)

            val cropLeft = (clampedLeft * imgW).toInt().coerceIn(0, imgW - 1)
            val cropTop = (clampedTop * imgH).toInt().coerceIn(0, imgH - 1)
            val cropRight = (clampedRight * imgW).toInt().coerceIn(cropLeft + 1, imgW)
            val cropBottom = (clampedBottom * imgH).toInt().coerceIn(cropTop + 1, imgH)

            val cropW = cropRight - cropLeft
            val cropH = cropBottom - cropTop

            if (cropW <= 0 || cropH <= 0) return null

            val plane = planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bufLimit = buffer.limit()

            if (imgW > imgH) {
                val probe = probeFrameGeometry(buffer, imgW, imgH, rowStride, pixelStride, bufLimit)
                if (probe.isStuckLetterbox) {
                    DiagnosticLogger.log("ScreenCapture: Detected stuck portrait-letterbox frame (${imgW}x${imgH}) — forcing VirtualDisplay rebind")
                    Log.w(TAG, "ScreenCapture: Detected stuck portrait-letterbox frame (${imgW}x${imgH}) — forcing VirtualDisplay rebind")
                    triggerLetterboxSelfHeal(imgW, imgH)
                    return null
                }
                if (!hasLoggedGeomAfterRebind) {
                    hasLoggedGeomAfterRebind = true
                    DiagnosticLogger.log("FrameGeom [rebind-${lastRebindReason}]: img=${imgW}x${imgH}, rightHalfNonBlack=${probe.rightHalfNonBlackPct}%, topLeftNonBlack=${probe.topLeftNonBlackPct}%, botLeftNonBlack=${probe.botLeftNonBlackPct}%")
                }
            }

            val rowBytes = cropW * pixelStride
            var roiBitmap: Bitmap? = null

            // Fast zero-allocation path for RGBA_8888 (pixelStride == 4)
            if (pixelStride == 4) {
                try {
                    val requiredBytes = rowBytes * cropH
                    var buf = reusableRoiBuffer
                    if (buf == null || buf.capacity() < requiredBytes) {
                        buf = ByteBuffer.allocateDirect(requiredBytes)
                        reusableRoiBuffer = buf
                    }
                    buf.clear()

                    var scratch = reusableRowScratch
                    if (scratch == null || scratch.size < rowBytes) {
                        scratch = ByteArray(rowBytes)
                        reusableRowScratch = scratch
                    }

                    val success = copyRoiRgbaRows(
                        src = buffer,
                        rowStride = rowStride,
                        roiLeft = cropLeft,
                        roiTop = cropTop,
                        roiWidth = cropW,
                        roiHeight = cropH,
                        dst = buf,
                        rowScratch = scratch
                    )

                    if (success) {
                        buf.flip()
                        var bmp = reusableRoiBitmap
                        if (bmp == null || bmp.isRecycled || lastRoiW != cropW || lastRoiH != cropH) {
                            bmp?.recycle()
                            bmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                            reusableRoiBitmap = bmp
                            lastRoiW = cropW
                            lastRoiH = cropH
                        }
                        bmp.copyPixelsFromBuffer(buf)
                        roiBitmap = bmp
                    } else {
                        Log.w(TAG, "copyRoiRgbaRows returned false, falling back to full copy")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Fast ROI row-copy failed, falling back: ${e.message}")
                }
            }

            // Guaranteed Fallback if fast path was skipped, returned false, or threw
            if (roiBitmap == null) {
                try {
                    buffer.position(0)
                    val dest = ByteArray(rowBytes * cropH)
                    for (row in 0 until cropH) {
                        val srcOffset = (cropTop + row) * rowStride + cropLeft * pixelStride
                        if (srcOffset < 0 || srcOffset + rowBytes > bufLimit) break
                        buffer.position(srcOffset)
                        buffer.get(dest, row * rowBytes, rowBytes)
                    }
                    buffer.position(0)
                    val fbBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                    fbBmp.copyPixelsFromBuffer(ByteBuffer.wrap(dest))
                    roiBitmap = fbBmp
                } catch (fbErr: Throwable) {
                    Log.e(TAG, "Fallback ROI copy failed: ${fbErr.message}", fbErr)
                }
            }

            if (!hasLoggedRoiSuccess) {
                hasLoggedRoiSuccess = true
                Log.i(TAG, "acquireCroppedRoi ready: img=${imgW}x${imgH}, crop=${cropW}x${cropH}, rowStride=$rowStride, limit=$bufLimit")
                DiagnosticLogger.log("acquireCroppedRoi ready: img=${imgW}x${imgH}, crop=${cropW}x${cropH}, rowStride=$rowStride, limit=$bufLimit")
            }

            roiBitmap
        } catch (t: Throwable) {
            Log.e(TAG, "Error acquiring cropped ROI: ${t.message}", t)
            DiagnosticLogger.log("acquireCroppedRoi error: ${t.javaClass.simpleName} - ${t.message}")
            null
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {}
        }
    }

    private var lastCapturedFullScreenBitmap: Bitmap? = null

    @Volatile
    private var isMenuModalVisible: Boolean = false

    @Volatile
    private var lastModalDismissedTimeMs: Long = 0L

    fun setMenuModalVisible(visible: Boolean) {
        isMenuModalVisible = visible
        if (!visible) {
            lastModalDismissedTimeMs = android.os.SystemClock.uptimeMillis()
        }
        clearFullScreenCache()
    }

    @Synchronized
    fun clearFullScreenCache() {
        try {
            lastCapturedFullScreenBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
        } catch (_: Throwable) {}
        lastCapturedFullScreenBitmap = null
    }

    private fun isModalScrimActive(): Boolean {
        if (isMenuModalVisible) return true
        val elapsed = android.os.SystemClock.uptimeMillis() - lastModalDismissedTimeMs
        return elapsed in 0L..400L
    }

    /**
     * Bounds-Safe Full-Screen Capture with Static-Frame Cache.
     * Uses row-by-row Adreno safe buffer copying (identical to acquireCroppedRoi)
     * with up to 4 retries (60ms apart) if acquireLatestImage() returns null on a static paused screen,
     * falling back to the cached full-screen frame only when no modal scrim is active.
     */
    @Synchronized
    fun acquireFullScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return if (isModalScrimActive()) null else lastCapturedFullScreenBitmap
        var image: Image? = null
        for (attempt in 1..4) {
            try {
                image = reader.acquireLatestImage()
                if (image != null) break
            } catch (t: Throwable) {
                DiagnosticLogger.log("acquireFullScreenImage attempt $attempt error: ${t.javaClass.simpleName} - ${t.message}")
            }
            if (attempt < 4) {
                try {
                    Thread.sleep(60L)
                } catch (_: InterruptedException) {}
            }
        }

        if (image == null) {
            val fallback = if (isModalScrimActive()) null else lastCapturedFullScreenBitmap
            Log.d(TAG, "acquireFullScreenBitmap: ImageReader returned null, using cached frame (cached=${fallback != null})")
            DiagnosticLogger.log("acquireFullScreen: ImageReader null, using cached frame (cached=${fallback != null}, scrimActive=${isModalScrimActive()})")
            return fallback
        }

        return try {
            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            if (planes.isEmpty()) return if (isModalScrimActive()) null else lastCapturedFullScreenBitmap

            val plane = planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bufLimit = buffer.limit()

            if (imgW > imgH) {
                val probe = probeFrameGeometry(buffer, imgW, imgH, rowStride, pixelStride, bufLimit)
                DiagnosticLogger.log("FrameGeom [MenuScan]: img=${imgW}x${imgH}, rightHalfNonBlack=${probe.rightHalfNonBlackPct}%, topLeftNonBlack=${probe.topLeftNonBlackPct}%, botLeftNonBlack=${probe.botLeftNonBlackPct}%")
                if (probe.isStuckLetterbox) {
                    DiagnosticLogger.log("ScreenCapture: Detected stuck portrait-letterbox frame in MenuScan (${imgW}x${imgH}) — forcing VirtualDisplay rebind")
                    Log.w(TAG, "ScreenCapture: Detected stuck portrait-letterbox frame in MenuScan (${imgW}x${imgH}) — forcing VirtualDisplay rebind")
                    triggerLetterboxSelfHeal(imgW, imgH)
                    return null
                }
            }

            val rowBytes = imgW * pixelStride
            val dest = ByteArray(rowBytes * imgH)

            for (row in 0 until imgH) {
                val srcOffset = row * rowStride
                if (srcOffset < 0 || srcOffset + rowBytes > bufLimit) {
                    break
                }
                buffer.position(srcOffset)
                buffer.get(dest, row * rowBytes, rowBytes)
            }

            val fullBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(dest))

            if (!isModalScrimActive()) {
                lastCapturedFullScreenBitmap = fullBitmap
            } else {
                lastCapturedFullScreenBitmap = null
            }
            fullBitmap
        } catch (t: Throwable) {
            Log.e(TAG, "Error acquiring full-screen bitmap: ${t.message}", t)
            DiagnosticLogger.log("acquireFullScreenBitmap error: ${t.javaClass.simpleName} - ${t.message}")
            if (isModalScrimActive()) null else lastCapturedFullScreenBitmap
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {}
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
            buffer.rewind()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * imgW
            val bufferWidth = imgW + (rowPadding / pixelStride)
            val requiredBytes = bufferWidth * imgH * 4

            // Adreno GPU buffer padding fix: if driver does not allocate trailing padding on the last row,
            // copy available bytes into a direct buffer of exact requiredBytes to prevent BufferUnderflowException
            val safeBuffer = if (buffer.remaining() >= requiredBytes) {
                buffer.position(0)
                buffer
            } else {
                val padded = ByteBuffer.allocateDirect(requiredBytes)
                buffer.position(0)
                padded.put(buffer)
                padded.position(0)
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
                try {
                    if (tempBitmap != null && !tempBitmap.isRecycled) {
                        tempBitmap.recycle()
                    }
                } catch (_: Throwable) {}
                tempBitmap = null
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
        isReleased = true
        try {
            pendingResizeRunnable?.let { backgroundHandler.removeCallbacks(it) }
            pendingResizeRunnable = null
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {}
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            try {
                lastCapturedFullScreenBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }
                reusableRoiBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }
            } catch (_: Throwable) {}
            lastCapturedFullScreenBitmap = null
            reusableRoiBitmap = null
            reusableRoiBuffer = null
            reusableRowScratch = null
            Log.d(TAG, "VirtualDisplay and ImageReader released successfully")
            DiagnosticLogger.log("Capture manager released")
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing ScreenCaptureManager: ${t.message}", t)
        }
    }
}

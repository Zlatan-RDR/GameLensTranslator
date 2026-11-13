package com.zlatan.gamelens.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.R
import com.zlatan.gamelens.capture.CropResult
import com.zlatan.gamelens.capture.FrameDiffEngine
import com.zlatan.gamelens.capture.ScreenCaptureManager
import com.zlatan.gamelens.db.TranslationCacheManager
import com.zlatan.gamelens.glossary.GlossaryManager
import com.zlatan.gamelens.ocr.OcrBlock
import com.zlatan.gamelens.ocr.OcrEngine
import com.zlatan.gamelens.overlay.SeamlessOverlayRenderer
import com.zlatan.gamelens.translation.TranslationConfig
import com.zlatan.gamelens.translation.TranslationManager
import com.zlatan.gamelens.translation.TranslationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationOverlayService : Service() {

    companion object {
        private const val TAG = "TranslationService"

        const val ACTION_START = "com.zlatan.gamelens.ACTION_START"
        const val ACTION_STOP = "com.zlatan.gamelens.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "gamelens_translation_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLICK_DRAG_TOLERANCE = 15f
        private const val FRAME_INTERVAL_MS = 300L
        private const val ANTI_FEEDBACK_VSYNC_DELAY_MS = 20L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var roiView: View? = null
    private var roiParams: WindowManager.LayoutParams? = null
    private var tvRoiTitle: TextView? = null
    private var isRoiVisible = true

    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            handleDisplayOrOrientationChange()
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    private var screenCaptureManager: ScreenCaptureManager? = null
    private val frameDiffEngine = FrameDiffEngine()
    private val ocrEngine = OcrEngine()

    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var glossaryManager: GlossaryManager
    private lateinit var translationManager: TranslationManager

    private var seamlessOverlayRenderer: SeamlessOverlayRenderer? = null
    private var latestDetectedBlocks: List<OcrBlock> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager?.registerDisplayListener(displayListener, mainHandler)

            createNotificationChannel()

            // Initialize Database, Cache, Glossary and Translation Managers
            cacheManager = TranslationCacheManager(this)
            glossaryManager = GlossaryManager(this)
            val config = loadTranslationConfigFromPreferences()
            translationManager = TranslationManager(this, cacheManager, glossaryManager, config)

            // Initialize Seamless Overlay Renderer
            seamlessOverlayRenderer = SeamlessOverlayRenderer(this)
            Log.d(TAG, "TranslationOverlayService onCreate completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in onCreate: ${e.message}", e)
            showToast("خطأ أثناء تهيئة خدمة الترجمة: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleDisplayOrOrientationChange()
    }

    private fun getRealScreenDimensions(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun handleDisplayOrOrientationChange() {
        val (w, h, dpi) = getRealScreenDimensions()
        screenCaptureManager?.updateDimensions(w, h, dpi)
        clampRoiWithinScreen(w, h)
    }

    private fun clampRoiWithinScreen(screenW: Int, screenH: Int) {
        val params = roiParams ?: return
        val view = roiView ?: return
        var changed = false

        if (params.width > screenW) {
            params.width = (screenW * 0.85f).toInt()
            changed = true
        }
        if (params.height > screenH) {
            params.height = (screenH * 0.45f).toInt()
            changed = true
        }
        if (params.x + params.width > screenW) {
            params.x = (screenW - params.width).coerceAtLeast(0)
            changed = true
        }
        if (params.y + params.height > screenH) {
            params.y = (screenH - params.height).coerceAtLeast(0)
            changed = true
        }

        if (changed) {
            mainHandler.post {
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
        }
    }

    fun getScanRoiRect(): Rect? {
        val params = roiParams ?: return null
        val density = resources.displayMetrics.density
        val headerHeight = (32 * density).toInt()
        val left = params.x
        val top = params.y + headerHeight
        val right = params.x + params.width
        val bottom = params.y + params.height
        return Rect(left, top, right, bottom)
    }

    private fun updateRoiBadge(text: String) {
        mainHandler.post {
            tvRoiTitle?.text = text
        }
    }

    private fun loadTranslationConfigFromPreferences(): TranslationConfig {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(MainActivity.KEY_TRANSLATION_MODE, MainActivity.MODE_OFFLINE)
        val mode = if (modeStr == MainActivity.MODE_CLOUD) TranslationMode.CLOUD_OPENAI else TranslationMode.OFFLINE_MLKIT
        val apiKey = prefs.getString(MainActivity.KEY_API_KEY, "") ?: ""
        val endpoint = prefs.getString(MainActivity.KEY_API_ENDPOINT, "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"

        return TranslationConfig(
            mode = mode,
            apiKey = apiKey,
            apiEndpoint = endpoint
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                // MANDATORY FOR ANDROID 14/15: startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // MUST be called FIRST before getting MediaProjection instance!
                try {
                    createNotificationChannel()
                    startForegroundWithNotification()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
                    showToast("تعذر تشغيل خدمة الواجهة الأمامية: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                try {
                    // Refresh config on start
                    translationManager.updateConfig(loadTranslationConfigFromPreferences())

                    // Setup UI overlays with try-catch
                    setupFloatingBubble()
                    setupRoiFrame()

                    // Initialize MediaProjection after foreground service is established
                    if (resultCode != 0 && resultData != null) {
                        initMediaProjectionAndCapture(resultCode, resultData)
                    } else {
                        Log.w(TAG, "MediaProjection token missing or canceled: resultCode=$resultCode")
                        showToast("تنبيه: لم يتم استلام رمز إذن التقاط الشاشة")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting overlay or capture: ${e.message}", e)
                    showToast("خطأ أثناء بدء الخدمة: ${e.message}")
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running_title))
            .setContentText(getString(R.string.service_running_desc))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType
        )
        Log.d(TAG, "ServiceCompat.startForeground called with type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GameLens Translator Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "قناة إشعارات خدمة ترجمة الألعاب الفورية"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun initMediaProjectionAndCapture(resultCode: Int, resultData: Intent) {
        try {
            val proj = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (proj == null) {
                Log.e(TAG, "Unable to obtain MediaProjection from token")
                showToast("فشل استخراج MediaProjection من النظام")
                return
            }
            mediaProjection = proj

            val (realW, realH, realDpi) = getRealScreenDimensions()
            screenCaptureManager = ScreenCaptureManager(
                context = this,
                mediaProjection = proj,
                screenWidth = realW,
                screenHeight = realH,
                screenDensityDpi = realDpi
            )

            startCaptureLoop()
            Log.d(TAG, "MediaProjection & ScreenCaptureManager initialized successfully: ${realW}x${realH} @ ${realDpi}dpi")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException acquiring MediaProjection: ${e.message}", e)
            showToast("خطأ أمني في إذن التقاط الشاشة: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception acquiring MediaProjection: ${e.message}", e)
            showToast("تعذر بدء التقاط الشاشة: ${e.message}")
        }
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.d(TAG, "End-to-End GameLens Pipeline active (interval: ${FRAME_INTERVAL_MS}ms)")
            while (isActive) {
                try {
                    processFramePipeline()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in End-to-End Pipeline: ${e.message}", e)
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private suspend fun processFramePipeline() {
        if (!isRoiVisible) {
            seamlessOverlayRenderer?.clear()
            return
        }

        val scanRoi = getScanRoiRect() ?: return
        val captureMgr = screenCaptureManager ?: return

        // 0. Synchronize capture dimensions with active display orientation
        val (screenW, screenH, dpi) = getRealScreenDimensions()
        val (curW, curH) = captureMgr.getDimensions()
        if (screenW != curW || screenH != curH) {
            captureMgr.updateDimensions(screenW, screenH, dpi)
            clampRoiWithinScreen(screenW, screenH)
        }

        // 1. Anti-Feedback Loop: temporarily hide translation overlay container right before frame capture
        seamlessOverlayRenderer?.setVisibility(false)
        delay(ANTI_FEEDBACK_VSYNC_DELAY_MS)

        // 2. Acquire raw full-screen game frame
        val fullBitmap: Bitmap? = captureMgr.acquireLatestBitmap()

        // 3. Restore translation overlay visibility immediately after frame acquisition
        seamlessOverlayRenderer?.setVisibility(true)

        if (fullBitmap == null) {
            updateRoiBadge("ROI [${screenW}x${screenH}] | انتظار الإطار...")
            return
        }

        val actualW = fullBitmap.width
        val actualH = fullBitmap.height

        // 4. Crop strictly to active user-selected ROI (excluding header)
        val cropResult = frameDiffEngine.cropToRoi(fullBitmap, scanRoi)
        if (cropResult == null) {
            fullBitmap.recycle()
            updateRoiBadge("ROI [${actualW}x${actualH}] | خارج حدود الشاشة")
            return
        }

        val croppedBitmap = cropResult.bitmap
        val cropRect = cropResult.cropRect

        // 5. Fast Frame Diffing: skip unchanged frames to save CPU and battery
        val hasChanged = frameDiffEngine.hasFrameChanged(croppedBitmap, cropRect)
        if (!hasChanged) {
            croppedBitmap.recycle()
            fullBitmap.recycle()
            return
        }

        // 6. On-Device ML Kit OCR Recognition
        val detectedBlocks = ocrEngine.recognizeText(croppedBitmap, cropRect)
        croppedBitmap.recycle()

        // 7. Auto-cleanup: If text in ROI has disappeared or changed to empty, clear overlay
        if (detectedBlocks.isEmpty()) {
            latestDetectedBlocks = emptyList()
            seamlessOverlayRenderer?.clear()
            fullBitmap.recycle()
            val status = translationManager.getStatusString()
            updateRoiBadge("ROI [${actualW}x${actualH}] | $status | لا يوجد نص")
            return
        }

        // 8. Asynchronous Translation through Glossary, Cache & Hybrid Engine on Dispatchers.IO
        var translatedCount = 0
        withContext(Dispatchers.IO) {
            for (block in detectedBlocks) {
                val translated = translationManager.translate(block.text)
                block.translatedText = translated
                if (translated.isNotBlank() && translated != block.text) {
                    translatedCount++
                }
            }
        }

        latestDetectedBlocks = detectedBlocks

        // 9. Seamless Overlay Rendering: Edge color sampling + Native RTL Arabic TextView placement
        seamlessOverlayRenderer?.renderTranslations(detectedBlocks, fullBitmap)
        fullBitmap.recycle()

        val status = translationManager.getStatusString()
        updateRoiBadge("ROI [${actualW}x${actualH}] | $status | OCR: ${detectedBlocks.size} | ترجم: $translatedCount")
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        if (bubbleView != null) return

        try {
            bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            bubbleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 200
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoved = false

            bubbleView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams?.x ?: 0
                        initialY = bubbleParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (Math.abs(deltaX) > CLICK_DRAG_TOLERANCE || Math.abs(deltaY) > CLICK_DRAG_TOLERANCE) {
                            isMoved = true
                        }
                        bubbleParams?.x = (initialX + deltaX).toInt()
                        bubbleParams?.y = (initialY + deltaY).toInt()
                        bubbleParams?.let { windowManager.updateViewLayout(bubbleView, it) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            toggleRoiVisibility()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(bubbleView, bubbleParams)
            Log.d(TAG, "Floating control bubble added to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager: ${e.message}", e)
            showToast("تنبيه: تعذر إظهار الفقاعة العائمة. يرجى تفعيل إذن 'عرض النوافذ بالخلفية' في HyperOS")
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupRoiFrame() {
        if (roiView != null) return

        try {
            roiView = LayoutInflater.from(this).inflate(R.layout.overlay_roi_frame, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val (realW, realH, _) = getRealScreenDimensions()
            val initialWidth = (realW * 0.70f).toInt()
            val initialHeight = (realH * 0.28f).toInt()

            roiParams = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (realW - initialWidth) / 2
                y = (realH * 0.58f).toInt()
            }

            tvRoiTitle = roiView?.findViewById(R.id.tvRoiTitle)
            val header = roiView?.findViewById<View>(R.id.roiHeader)
            val btnHide = roiView?.findViewById<ImageView>(R.id.btnHideRoi)
            val resizeHandle = roiView?.findViewById<ImageView>(R.id.ivResizeHandle)

            var headerInitX = 0
            var headerInitY = 0
            var headerTouchX = 0f
            var headerTouchY = 0f

            header?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        headerInitX = roiParams?.x ?: 0
                        headerInitY = roiParams?.y ?: 0
                        headerTouchX = event.rawX
                        headerTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - headerTouchX
                        val deltaY = event.rawY - headerTouchY
                        roiParams?.x = (headerInitX + deltaX).toInt()
                        roiParams?.y = (headerInitY + deltaY).toInt()
                        roiParams?.let { windowManager.updateViewLayout(roiView, it) }
                        true
                    }
                    else -> false
                }
            }

            var resizeStartW = 0
            var resizeStartH = 0
            var resizeTouchX = 0f
            var resizeTouchY = 0f
            val minWidthPx = (140 * resources.displayMetrics.density).toInt()
            val minHeightPx = (90 * resources.displayMetrics.density).toInt()

            resizeHandle?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeStartW = roiParams?.width ?: initialWidth
                        resizeStartH = roiParams?.height ?: initialHeight
                        resizeTouchX = event.rawX
                        resizeTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaW = (event.rawX - resizeTouchX).toInt()
                        val deltaH = (event.rawY - resizeTouchY).toInt()
                        val newW = (resizeStartW + deltaW).coerceAtLeast(minWidthPx)
                        val newH = (resizeStartH + deltaH).coerceAtLeast(minHeightPx)

                        roiParams?.width = newW
                        roiParams?.height = newH
                        roiParams?.let { windowManager.updateViewLayout(roiView, it) }
                        true
                    }
                    else -> false
                }
            }

            btnHide?.setOnClickListener {
                toggleRoiVisibility()
            }

            windowManager.addView(roiView, roiParams)
            isRoiVisible = true
            Log.d(TAG, "ROI frame added to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ROI frame to WindowManager: ${e.message}", e)
        }
    }

    private fun toggleRoiVisibility() {
        roiView?.let { view ->
            if (isRoiVisible) {
                view.visibility = View.GONE
                isRoiVisible = false
                seamlessOverlayRenderer?.clear()
                Toast.makeText(this, "تم إخفاء إطار منطقة الترجمة وإيقاف العرض", Toast.LENGTH_SHORT).show()
            } else {
                view.visibility = View.VISIBLE
                isRoiVisible = true
                frameDiffEngine.reset()
                Toast.makeText(this, "تم إظهار إطار منطقة الترجمة واستئناف العرض", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getCurrentRoiRect(): Rect? {
        val params = roiParams ?: return null
        return Rect(
            params.x,
            params.y,
            params.x + params.width,
            params.y + params.height
        )
    }

    fun getLatestDetectedBlocks(): List<OcrBlock> = latestDetectedBlocks

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        serviceScope.cancel()

        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        displayManager = null
        tvRoiTitle = null

        seamlessOverlayRenderer?.release()
        seamlessOverlayRenderer = null

        screenCaptureManager?.release()
        screenCaptureManager = null

        ocrEngine.close()
        translationManager.close()

        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            bubbleView = null
        }

        roiView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            roiView = null
        }

        mediaProjection?.stop()
        mediaProjection = null
    }
}

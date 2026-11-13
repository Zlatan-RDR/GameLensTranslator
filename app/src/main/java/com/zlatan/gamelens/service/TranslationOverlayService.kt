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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.R
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
        private const val LONG_PRESS_TIMEOUT_MS = 750L
        private const val FRAME_INTERVAL_MS = 300L
        private const val ANTI_FEEDBACK_VSYNC_DELAY_MS = 20L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var ivBubbleIcon: ImageView? = null

    // Single touchable control state
    @Volatile
    private var isAutoTranslationActive = true

    @Volatile
    private var forceInstantPass = true

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

            // Initialize Seamless Full-Screen Overlay Renderer
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
        clampBubbleWithinScreen(w, h)
    }

    private fun clampBubbleWithinScreen(screenW: Int, screenH: Int) {
        val params = bubbleParams ?: return
        val view = bubbleView ?: return
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()

        params.x = params.x.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenH - bubbleSize).coerceAtLeast(0))

        mainHandler.post {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
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
                // MANDATORY FOR ANDROID 14/15/16: startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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

                    // Setup Single Floating Bubble Control
                    setupFloatingBubble()

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
        Log.d(TAG, "ServiceCompat.startForeground called with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
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
            Log.d(TAG, "Full-Screen GameLens Pipeline active (interval: ${FRAME_INTERVAL_MS}ms)")
            while (isActive) {
                try {
                    processFramePipeline()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Pipeline: ${e.message}", e)
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private suspend fun processFramePipeline() {
        // Paused state: clear overlays and wait
        if (!isAutoTranslationActive) {
            seamlessOverlayRenderer?.clear()
            return
        }

        val captureMgr = screenCaptureManager ?: return

        // 0. Synchronize capture dimensions with active display orientation
        val (screenW, screenH, dpi) = getRealScreenDimensions()
        val (curW, curH) = captureMgr.getDimensions()
        if (screenW != curW || screenH != curH) {
            captureMgr.updateDimensions(screenW, screenH, dpi)
            clampBubbleWithinScreen(screenW, screenH)
        }

        // 1. Anti-Feedback Loop: hide translation overlay container right before frame capture
        seamlessOverlayRenderer?.setVisibility(false)
        delay(ANTI_FEEDBACK_VSYNC_DELAY_MS)

        // 2. Acquire raw full-screen game frame
        val fullBitmap: Bitmap? = captureMgr.acquireLatestBitmap()

        // 3. Restore translation overlay visibility immediately after frame acquisition
        seamlessOverlayRenderer?.setVisibility(true)

        if (fullBitmap == null) return

        val bubbleBounds = getBubbleBounds()

        // 4. Fast Full-Screen Frame Diffing: skip unchanged frames, ignoring floating bubble area
        val shouldForce = forceInstantPass
        forceInstantPass = false
        val hasChanged = if (shouldForce) true else frameDiffEngine.hasFrameChanged(fullBitmap, bubbleBounds)
        if (!hasChanged) {
            fullBitmap.recycle()
            return
        }

        // 5. Full-Screen On-Device ML Kit OCR Recognition (excluding bubble & HUD noise)
        val detectedBlocks = ocrEngine.recognizeText(fullBitmap, bubbleBounds)

        // 6. Auto-cleanup: If no text is detected on screen, clear overlays
        if (detectedBlocks.isEmpty()) {
            latestDetectedBlocks = emptyList()
            seamlessOverlayRenderer?.clear()
            fullBitmap.recycle()
            return
        }

        // 7. Non-blocking Asynchronous Translation through Glossary, Cache & Translation Engine
        withContext(Dispatchers.IO) {
            for (block in detectedBlocks) {
                val translated = translationManager.translate(block.text)
                block.translatedText = translated
            }
        }

        latestDetectedBlocks = detectedBlocks

        // 8. 100% Touch-Passthrough Seamless Overlay Rendering directly over original text bounding boxes
        seamlessOverlayRenderer?.renderTranslations(detectedBlocks, fullBitmap)
        fullBitmap.recycle()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        if (bubbleView != null) return

        try {
            bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
            ivBubbleIcon = bubbleView?.findViewById(R.id.ivBubbleIcon)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val bubbleSizePx = (56 * resources.displayMetrics.density).toInt()

            bubbleParams = WindowManager.LayoutParams(
                bubbleSizePx,
                bubbleSizePx,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 320
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoved = false
            var isLongPressed = false

            val longPressRunnable = Runnable {
                isLongPressed = true
                showToast("جاري إيقاف خدمة الترجمة...")
                stopSelf()
            }

            bubbleView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams?.x ?: 0
                        initialY = bubbleParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        isLongPressed = false
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (Math.abs(deltaX) > CLICK_DRAG_TOLERANCE || Math.abs(deltaY) > CLICK_DRAG_TOLERANCE) {
                            if (!isMoved) {
                                isMoved = true
                                mainHandler.removeCallbacks(longPressRunnable)
                            }
                        }
                        if (isMoved) {
                            bubbleParams?.x = (initialX + deltaX).toInt()
                            bubbleParams?.y = (initialY + deltaY).toInt()
                            bubbleParams?.let { windowManager.updateViewLayout(bubbleView, it) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (!isMoved && !isLongPressed) {
                            // Single tap: Toggle auto-translation ON / OFF
                            toggleTranslationState()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(bubbleView, bubbleParams)
            updateBubbleVisualState()
            Log.d(TAG, "Floating control bubble added to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager: ${e.message}", e)
            showToast("فشل إظهار الفقاعة العائمة: ${e.message}")
        }
    }

    private fun toggleTranslationState() {
        isAutoTranslationActive = !isAutoTranslationActive
        updateBubbleVisualState()

        if (isAutoTranslationActive) {
            forceInstantPass = true
            frameDiffEngine.reset()
            showToast("تم تفعيل الترجمة التلقائية للشاشة")
        } else {
            seamlessOverlayRenderer?.clear()
            showToast("تم إيقاف الترجمة التلقائية مؤقتاً")
        }
    }

    private fun updateBubbleVisualState() {
        mainHandler.post {
            val density = resources.displayMetrics.density
            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isAutoTranslationActive) {
                    // Bright emerald green when active
                    setColor(Color.parseColor("#00C853"))
                    setStroke((2 * density).toInt(), Color.parseColor("#FFFFFF"))
                } else {
                    // Dark slate with bright cyan border when paused
                    setColor(Color.parseColor("#263238"))
                    setStroke((2.5f * density).toInt(), Color.parseColor("#00E5FF"))
                }
            }
            bubbleView?.background = backgroundDrawable
            bubbleView?.alpha = if (isAutoTranslationActive) 1.0f else 0.90f
            ivBubbleIcon?.setColorFilter(Color.WHITE)
        }
    }

    fun getBubbleBounds(): Rect? {
        val params = bubbleParams ?: return null
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        return Rect(params.x, params.y, params.x + bubbleSize, params.y + bubbleSize)
    }

    fun getLatestDetectedBlocks(): List<OcrBlock> = latestDetectedBlocks

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
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

        mediaProjection?.stop()
        mediaProjection = null
    }
}

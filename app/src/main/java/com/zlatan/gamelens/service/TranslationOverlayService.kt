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
import com.zlatan.gamelens.audio.GameAudioTranslator
import com.zlatan.gamelens.capture.FrameDiffEngine
import com.zlatan.gamelens.capture.ScreenCaptureManager
import com.zlatan.gamelens.db.TranslationCacheManager
import com.zlatan.gamelens.diag.DiagnosticLogger
import com.zlatan.gamelens.glossary.GlossaryManager
import com.zlatan.gamelens.ocr.OcrBlock
import com.zlatan.gamelens.ocr.OcrEngine
import com.zlatan.gamelens.overlay.EdgeColorSampler
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
        private const val FRAME_INTERVAL_MS = 200L // 200ms for fast subtitle responsiveness
        private const val ANTI_FEEDBACK_VSYNC_DELAY_MS = 20L
        private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF]")
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
    private var isAudioModeActive = false

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
    private var gameAudioTranslator: GameAudioTranslator? = null
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
            DiagnosticLogger.log("Service: onCreate")
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
        DiagnosticLogger.log("Service started (action=${intent.action})")

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
                    val config = loadTranslationConfigFromPreferences()
                    translationManager.updateConfig(config)

                    // Strict Offline Model Gate Check: reject start if model not ready in offline mode
                    if (config.mode == TranslationMode.OFFLINE_MLKIT) {
                        TranslationManager.verifyArabicModelDownloaded { isDownloaded ->
                            if (!isDownloaded) {
                                DiagnosticLogger.log("Startup blocked: Offline Arabic model is not downloaded")
                                showToast("تنبيه: يجب تنزيل نموذج الترجمة بدون إنترنت أولاً من شاشة التطبيق")
                                stopSelf()
                            }
                        }
                    }

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

            // Initialize Internal Game Audio Translation (Sherpa-ONNX + AudioPlaybackCapture)
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val isAudioEnabled = prefs.getBoolean(MainActivity.KEY_GAME_AUDIO_ENABLED, false)
            if (isAudioEnabled && GameAudioTranslator.isAsrModelInstalled(this)) {
                isAudioModeActive = true
                gameAudioTranslator?.stop()
                gameAudioTranslator = GameAudioTranslator(
                    context = this,
                    mediaProjection = proj,
                    translationManager = translationManager,
                    onSubtitleReady = { subtitle ->
                        mainHandler.post {
                            seamlessOverlayRenderer?.renderSubtitle(subtitle)
                        }
                    }
                ).also {
                    it.start()
                }
            } else {
                isAudioModeActive = false
            }
            updateBubbleVisualState()
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
            DiagnosticLogger.log("Pipeline loop started")
            while (isActive) {
                try {
                    processFramePipeline()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in Pipeline: ${t.message}", t)
                    DiagnosticLogger.log("Pipeline error: ${t.javaClass.simpleName} - ${t.message}")
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private suspend fun processFramePipeline() {
        // When Audio Mode is active, NEVER run continuous background OCR to prevent texture clutter
        if (isAudioModeActive || !isAutoTranslationActive) {
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
            return
        }

        DiagnosticLogger.log("Frame captured: ${fullBitmap.width}x${fullBitmap.height}")

        // 5. Full-Screen On-Device ML Kit OCR Recognition (excluding bubble & HUD noise)
        val detectedBlocks = ocrEngine.recognizeText(fullBitmap, bubbleBounds)

        // 6. Auto-cleanup: If no text is detected on screen, clear overlays
        if (detectedBlocks.isEmpty()) {
            latestDetectedBlocks = emptyList()
            seamlessOverlayRenderer?.clear()
            return
        }

        DiagnosticLogger.log("OCR blocks: ${detectedBlocks.size}")

        // Synchronously sample background edge colors while fullBitmap is 100% valid and guaranteed alive
        for (block in detectedBlocks) {
            block.colorScheme = EdgeColorSampler.sampleEdgeColors(fullBitmap, block.boundingBox)
        }

        // 7. Non-blocking Asynchronous Translation through Glossary, Cache & Translation Engine
        withContext(Dispatchers.IO) {
            for (block in detectedBlocks) {
                val translated = translationManager.translate(block.text)
                block.translatedText = translated
            }
        }

        DiagnosticLogger.log("Translated: ${detectedBlocks.size}")

        // Filter out non-Arabic or unchanged translations (e.g. Latin app names or untranslated UI)
        val arabicBlocks = detectedBlocks.filter { block ->
            val trans = block.translatedText
            !trans.isNullOrBlank() &&
            !trans.trim().equals(block.text.trim(), ignoreCase = true) &&
            ARABIC_REGEX.containsMatchIn(trans)
        }

        latestDetectedBlocks = arabicBlocks

        if (arabicBlocks.isEmpty()) {
            seamlessOverlayRenderer?.clear()
            DiagnosticLogger.log("Rendered: 0 overlays (no valid Arabic text)")
            return
        }

        // 8. 100% Touch-Passthrough Seamless Overlay Rendering directly over original text bounding boxes
        // Passes valid Arabic blocks and dimensions for accurate coordinate scaling
        seamlessOverlayRenderer?.renderTranslations(arabicBlocks, screenW, screenH, fullBitmap.width, fullBitmap.height)
        DiagnosticLogger.log("Rendered: ${arabicBlocks.size} overlays")
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
                gravity = Gravity.TOP or Gravity.LEFT
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

            var lastTapTimestamp = 0L
            val DOUBLE_TAP_TIMEOUT_MS = 280L

            val singleTapRunnable = Runnable {
                handleSingleTap()
            }

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
                                mainHandler.removeCallbacks(singleTapRunnable)
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
                            val now = System.currentTimeMillis()
                            if (now - lastTapTimestamp <= DOUBLE_TAP_TIMEOUT_MS) {
                                // Double Tap detected: cancel single tap action and toggle Audio Mode
                                mainHandler.removeCallbacks(singleTapRunnable)
                                lastTapTimestamp = 0L
                                handleDoubleTap()
                            } else {
                                lastTapTimestamp = now
                                mainHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT_MS)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        mainHandler.removeCallbacks(singleTapRunnable)
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(bubbleView, bubbleParams)
            updateBubbleVisualState()
            DiagnosticLogger.log("Bubble attached at (${bubbleParams?.x}, ${bubbleParams?.y})")
            Log.d(TAG, "Floating control bubble added to WindowManager successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager: ${t.message}", t)
            DiagnosticLogger.log("Bubble attach failed: ${t.javaClass.simpleName} - ${t.message}")
            showToast("فشل إظهار الفقاعة العائمة: ${t.message}")
        }
    }

    private fun handleSingleTap() {
        if (seamlessOverlayRenderer?.hasPinnedMenuCards() == true) {
            seamlessOverlayRenderer?.clearMenuCards()
            showToast("تم إخفاء ترجمة القائمة والعودة للعب")
            updateBubbleVisualState()
        } else {
            triggerZeroSelfReadMenuScan()
        }
    }

    private fun handleDoubleTap() {
        toggleAudioMode()
    }

    private fun toggleAudioMode() {
        if (isAudioModeActive) {
            isAudioModeActive = false
            gameAudioTranslator?.stop()
            gameAudioTranslator = null
            seamlessOverlayRenderer?.renderSubtitle("")
            showToast("تم إيقاف ترجمة صوت اللعبة (وضع الاستعداد)")
            updateBubbleVisualState()
        } else {
            if (!GameAudioTranslator.isAsrModelInstalled(this)) {
                showToast("يلزم تنزيل وتثبيت نموذج الصوت أولاً من التطبيق (~122 MB)")
                return
            }

            val proj = mediaProjection
            if (proj == null) {
                showToast("إذن التقاط الشاشة/الصوت غير متاح")
                return
            }

            isAudioModeActive = true
            // Clear any pinned menu cards when activating audio subtitle mode
            seamlessOverlayRenderer?.clearMenuCards()

            gameAudioTranslator?.stop()
            gameAudioTranslator = GameAudioTranslator(
                context = this,
                mediaProjection = proj,
                translationManager = translationManager,
                onSubtitleReady = { subtitle ->
                    mainHandler.post {
                        seamlessOverlayRenderer?.renderSubtitle(subtitle)
                    }
                }
            ).also {
                it.start()
            }

            showToast("تم تفعيل ترجمة صوت اللعبة المباشرة (الشريط السينمائي)")
            updateBubbleVisualState()
        }
    }

    private fun triggerZeroSelfReadMenuScan() {
        showToast("جاري مسح وترجمة القوائم (Zero Self-Read)...")
        serviceScope.launch {
            try {
                val captureMgr = screenCaptureManager ?: run {
                    showToast("خدمة التقاط الشاشة غير متصلة")
                    return@launch
                }

                // 1. Temporarily hide active overlays to eliminate self-reading bug
                seamlessOverlayRenderer?.setVisibility(false)
                // 2. Wait 40ms for the display compositor / SurfaceFlinger to refresh clean game frame
                delay(40L)

                // 3. Acquire clean raw bitmap
                val fullBitmap = captureMgr.acquireLatestBitmap()

                // 4. Restore visibility
                seamlessOverlayRenderer?.setVisibility(true)

                if (fullBitmap == null) {
                    showToast("تعذر التقاط إطار الشاشة، يرجى المحاولة ثانية")
                    return@launch
                }

                val bubbleBounds = getBubbleBounds()

                // 5. ML Kit OCR with confidence >= 0.78 & English token linguistic filter (>= 60%)
                val detectedBlocks = ocrEngine.recognizeText(fullBitmap, bubbleBounds)
                if (detectedBlocks.isEmpty()) {
                    showToast("لم يتم العثور على نصوص واضحة في القائمة")
                    updateBubbleVisualState()
                    return@launch
                }

                // 6. Translate detected blocks
                withContext(Dispatchers.IO) {
                    for (block in detectedBlocks) {
                        val translated = translationManager.translate(block.text)
                        block.translatedText = translated
                    }
                }

                val arabicBlocks = detectedBlocks.filter { block ->
                    val trans = block.translatedText
                    !trans.isNullOrBlank() &&
                    !trans.trim().equals(block.text.trim(), ignoreCase = true) &&
                    ARABIC_REGEX.containsMatchIn(trans)
                }

                if (arabicBlocks.isEmpty()) {
                    showToast("لم تتوفر ترجمة عربية للنصوص المكتشفة")
                    updateBubbleVisualState()
                    return@launch
                }

                // 7. Render menu translations with opaque dark cards (#F210141D) pinned stably
                val (screenW, screenH, _) = getRealScreenDimensions()
                seamlessOverlayRenderer?.renderTranslations(
                    blocks = arabicBlocks,
                    screenW = screenW,
                    screenH = screenH,
                    bitmapW = fullBitmap.width,
                    bitmapH = fullBitmap.height
                )

                updateBubbleVisualState()
                showToast("تم تثبيت ترجمة القائمة (انقر نقرة واحدة للإخفاء)")
            } catch (e: Exception) {
                Log.e(TAG, "Zero-Self-Read scan error: ${e.message}", e)
                seamlessOverlayRenderer?.setVisibility(true)
                showToast("خطأ أثناء مسح القائمة: ${e.message}")
            }
        }
    }

    private fun updateBubbleVisualState() {
        mainHandler.post {
            val density = resources.displayMetrics.density
            val hasPinned = seamlessOverlayRenderer?.hasPinnedMenuCards() == true

            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    isAudioModeActive -> {
                        // Cyan when Audio Mode is active
                        setColor(Color.parseColor("#00838F"))
                        setStroke((2.5f * density).toInt(), Color.parseColor("#00E5FF"))
                    }
                    hasPinned -> {
                        // Green when Menu Overlays are pinned
                        setColor(Color.parseColor("#00C853"))
                        setStroke((2 * density).toInt(), Color.parseColor("#FFFFFF"))
                    }
                    else -> {
                        // Slate when standby
                        setColor(Color.parseColor("#263238"))
                        setStroke((2 * density).toInt(), Color.parseColor("#90A4AE"))
                    }
                }
            }
            bubbleView?.background = backgroundDrawable
            bubbleView?.alpha = if (isAudioModeActive || hasPinned) 1.0f else 0.85f
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
        DiagnosticLogger.log("Service: onDestroy")
        captureJob?.cancel()
        serviceScope.cancel()

        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        displayManager = null

        seamlessOverlayRenderer?.release()
        seamlessOverlayRenderer = null

        gameAudioTranslator?.stop()
        gameAudioTranslator = null

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

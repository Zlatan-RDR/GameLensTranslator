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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zlatan.gamelens.AppForegroundState
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.ProjectionReacquireActivity
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
import com.zlatan.gamelens.patch.PatchManager
import com.zlatan.gamelens.translation.TranslationConfig
import com.zlatan.gamelens.translation.TranslationManager
import com.zlatan.gamelens.translation.TranslationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationOverlayService : Service() {

    companion object {
        private const val TAG = "TranslationService"

        const val ACTION_START = "com.zlatan.gamelens.ACTION_START"
        const val ACTION_STOP = "com.zlatan.gamelens.ACTION_STOP"
        const val ACTION_CALIBRATE = "com.zlatan.gamelens.ACTION_CALIBRATE"
        const val ACTION_UPDATE_PROJECTION = "com.zlatan.gamelens.ACTION_UPDATE_PROJECTION"
        const val ACTION_UPDATE_FONT_SIZE = "com.zlatan.gamelens.ACTION_UPDATE_FONT_SIZE"
        const val ACTION_TEST_OVERLAY = "com.zlatan.gamelens.ACTION_TEST_OVERLAY"
        const val ACTION_RELOAD_SETTINGS = "com.zlatan.gamelens.ACTION_RELOAD_SETTINGS"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUTO_START_AUDIO = "extra_auto_start_audio"
        const val EXTRA_FONT_SIZE_SP = "extra_font_size_sp"

        private const val NOTIFICATION_CHANNEL_ID = "gamelens_translation_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLICK_DRAG_TOLERANCE = 15f
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val FRAME_INTERVAL_MS = 120L // 120ms for fast subtitle responsiveness
        private const val ANTI_FEEDBACK_VSYNC_DELAY_MS = 35L
        private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF]")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var ivBubbleIcon: ImageView? = null
    private var viewHeartbeatDot: View? = null

    // Single touchable control state
    @Volatile
    private var isAutoTranslationActive = true

    @Volatile
    private var isAudioModeActive = false

    @Volatile
    private var isCalibrationActive = false

    private var calibrationOverlay: com.zlatan.gamelens.overlay.CalibrationOverlay? = null
    private var quickMenuView: View? = null
    private var quickMenuParams: WindowManager.LayoutParams? = null

    private var typewriterPendingText = ""
    private var typewriterLastGrowthTime = 0L

    @Volatile
    private var forceInstantPass = true

    @Volatile
    private var lastDisplayedNormalizedText = ""

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
    @Volatile
    private var isProjectionValid = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system callback")
            DiagnosticLogger.log("MediaProjection stopped by system callback")
            isProjectionValid = false
            mediaProjection = null
            gameAudioTranslator?.stop()
            gameAudioTranslator = null
            screenCaptureManager?.release()
            screenCaptureManager = null
            mainHandler.post {
                updateBubbleVisualState()
            }
        }
    }

    private var screenCaptureManager: ScreenCaptureManager? = null
    private var gameAudioTranslator: GameAudioTranslator? = null
    private val frameDiffEngine = FrameDiffEngine()
    private val fastPixelDiffGate = com.zlatan.gamelens.ocr.FastPixelDiffGate()
    private val ocrEngine = OcrEngine()
    private val subtitleGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var activeTranslationJob: Job? = null
    private var lastOcrTimestamp: Long = 0L
    private var lastDisplayedEnglishText: String = ""
    private var candidateEnglishText: String = ""
    private var candidateTimestamp: Long = 0L
    private var pendingStreamingText: String = ""
    private var streamingStartTime: Long = 0L
    private var lastSubtitleDisplayTimestamp: Long = 0L
    private var lastOpaquePollTimestamp: Long = 0L
    private var isRoiSettling: Boolean = false
    private var roiSettleStartTime: Long = 0L

    @Volatile
    private var isMenuModalVisible: Boolean = false

    @Volatile
    private var isMenuScanInProgress: Boolean = false

    private var lastPatchEnglishText: String = ""
    private var lastDisplayWasPatch: Boolean = false
    private var lastPatchDisplayTimestamp: Long = 0L

    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var glossaryManager: GlossaryManager
    private lateinit var translationManager: TranslationManager

    private var seamlessOverlayRenderer: SeamlessOverlayRenderer? = null
    private var latestDetectedBlocks: List<OcrBlock> = emptyList()
    private var consecutiveEmptyRoiCount: Int = 0
    private var lastSubtitleVisibleOnScreenMs: Long = 0L
    private var lastResizeTimestampMs: Long = 0L
    private var serviceStartTimeMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            serviceStartTimeMs = android.os.SystemClock.elapsedRealtime()
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
            com.zlatan.gamelens.ocr.EnglishWordRepairer.init(applicationContext)

            // Initialize Seamless Full-Screen Overlay Renderer
            seamlessOverlayRenderer = SeamlessOverlayRenderer(this)
            seamlessOverlayRenderer?.onMenuModalDismissed = {
                isMenuModalVisible = false
                screenCaptureManager?.setMenuModalVisible(false)
                isMenuScanInProgress = false
                mainHandler.post {
                    updateBubbleVisualState()
                }
            }
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
            val bounds = windowManager.maximumWindowMetrics.bounds
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
        seamlessOverlayRenderer?.updateScreenDimensions()
        lastResizeTimestampMs = android.os.SystemClock.elapsedRealtime()
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
            ACTION_CALIBRATE -> {
                startVisualCalibration()
            }
            ACTION_UPDATE_FONT_SIZE -> {
                val fontSize = intent.getFloatExtra(EXTRA_FONT_SIZE_SP, 18f)
                seamlessOverlayRenderer?.updateFontSize(fontSize)
            }
            ACTION_UPDATE_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val autoStartAudio = intent.getBooleanExtra(EXTRA_AUTO_START_AUDIO, false)
                if (resultCode != 0 && resultData != null) {
                    initMediaProjectionAndCapture(resultCode, resultData)
                    if (autoStartAudio) {
                        startAudioTranslation()
                    }
                } else {
                    DiagnosticLogger.log("ACTION_UPDATE_PROJECTION: invalid result code or data")
                }
            }
            ACTION_TEST_OVERLAY -> {
                seamlessOverlayRenderer?.renderTestOverlay()
                showToast("جاري اختبار عرض طبقة الترجمة لمدة 3 ثوانٍ")
            }
            ACTION_RELOAD_SETTINGS -> {
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val fontSize = intent.getFloatExtra(EXTRA_FONT_SIZE_SP, prefs.getFloat(MainActivity.KEY_SUBTITLE_FONT_SIZE_SP, MainActivity.DEFAULT_FONT_SIZE_SP))
                seamlessOverlayRenderer?.updateFontSize(fontSize)
                seamlessOverlayRenderer?.reloadAppearanceSettings()
                val calRect = com.zlatan.gamelens.overlay.CalibrationOverlay.getCalibratedRect(prefs)
                updateBubbleVisualState()

                val activeProfId = com.zlatan.gamelens.profile.GameProfileManager.getActiveProfileId(this)
                val activeProf = com.zlatan.gamelens.profile.GameProfileManager.getProfiles(this).find { it.id == activeProfId }
                translationManager?.activeGameProfile = activeProf?.name ?: ""
                translationManager?.clearDialogueContext()

                DiagnosticLogger.log("Reloaded profile settings in service: ROI=$calRect, font=${fontSize}sp, profile=${activeProf?.name}")
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
            // Clean up stale projection if any
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {}
            mediaProjection?.stop()
            mediaProjection = null
            screenCaptureManager?.release()
            screenCaptureManager = null

            val proj = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (proj == null) {
                Log.e(TAG, "Unable to obtain MediaProjection from token")
                showToast("فشل استخراج MediaProjection من النظام")
                isProjectionValid = false
                return
            }
            mediaProjection = proj
            isProjectionValid = true
            proj.registerCallback(projectionCallback, mainHandler)

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
            val isAudioEnabled = prefs.getBoolean("audio_translation_enabled", false) && prefs.getBoolean(MainActivity.KEY_GAME_AUDIO_ENABLED, false)
            if (isAudioEnabled && GameAudioTranslator.isAsrModelInstalled(this)) {
                startAudioTranslation()
            } else {
                isAudioModeActive = false
                isAutoTranslationActive = true
            }
            updateBubbleVisualState()
        } catch (e: SecurityException) {
            isProjectionValid = false
            Log.e(TAG, "SecurityException acquiring MediaProjection: ${e.message}", e)
            showToast("خطأ أمني في إذن التقاط الشاشة: ${e.message}")
        } catch (e: Exception) {
            isProjectionValid = false
            Log.e(TAG, "Exception acquiring MediaProjection: ${e.message}", e)
            showToast("تعذر بدء التقاط الشاشة: ${e.message}")
        }
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.d(TAG, "Precision ROI OCR Pipeline active")
            DiagnosticLogger.log("Precision ROI OCR loop started (100ms)")
            while (isActive) {
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val isOpaqueCover = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OFFSET_ABOVE) == MainActivity.MODE_OPAQUE_COVER

                try {
                    processFramePipeline(isOpaqueCover)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in Pipeline: ${t.message}", t)
                    DiagnosticLogger.log("Pipeline error: ${t.javaClass.simpleName} - ${t.message}")
                }
                delay(100L)
            }
        }
    }

    private suspend fun captureFrameForOcr(isOpaqueCover: Boolean = true): Bitmap? {
        val captureMgr = screenCaptureManager ?: return null
        if (!isOpaqueCover) {
            // Offset Above Mode: capture immediately with zero overlay hiding (since Arabic bar sits cleanly above subtitle zone)
            return captureMgr.acquireLatestBitmap()
        }

        // Opaque Cover Mode: set invisible for 20ms right before capture, restore immediately
        withContext(Dispatchers.Main) {
            seamlessOverlayRenderer?.setInvisibleForCapture()
        }
        delay(20L)
        val bitmap = captureMgr.acquireLatestBitmap()
        withContext(Dispatchers.Main) {
            seamlessOverlayRenderer?.restoreVisibilityAfterCapture()
        }
        return bitmap
    }

    private suspend fun processFramePipeline(isOpaqueCover: Boolean) {
        if (isMenuModalVisible || isMenuScanInProgress) {
            return
        }

        val (screenW, screenH, dpi) = getRealScreenDimensions()

        // 1. Auto-pause during startup grace window (1500ms), rapid display resize transition, or when GameLens is in the foreground
        val nowRealtime = android.os.SystemClock.elapsedRealtime()
        if (nowRealtime - serviceStartTimeMs < 1500L) {
            return
        }
        if ((nowRealtime - lastResizeTimestampMs < 650L) ||
            AppForegroundState.isMainUiVisible.get() ||
            com.zlatan.gamelens.MainActivity.isAppInForeground
        ) {
            if (seamlessOverlayRenderer?.isSubtitleVisible() == true) {
                seamlessOverlayRenderer?.clear()
                lastDisplayedEnglishText = ""
                candidateEnglishText = ""
                pendingStreamingText = ""
                streamingStartTime = 0L
                lastDisplayWasPatch = false
                lastPatchEnglishText = ""
                consecutiveEmptyRoiCount = 0
            }
            return
        }

        // When Audio Mode is active, NEVER run continuous background OCR to prevent texture clutter
        if (isAudioModeActive || !isAutoTranslationActive) {
            return
        }

        val captureMgr = screenCaptureManager ?: return

        // 2. Synchronize capture dimensions with active display orientation
        val (curW, curH) = captureMgr.getDimensions()
        if (screenW != curW || screenH != curH) {
            captureMgr.updateDimensions(screenW, screenH, dpi)
            clampBubbleWithinScreen(screenW, screenH)
            seamlessOverlayRenderer?.updateScreenDimensions()
            lastResizeTimestampMs = android.os.SystemClock.elapsedRealtime()
        }

        if (android.os.SystemClock.elapsedRealtime() - lastResizeTimestampMs < 650L) {
            seamlessOverlayRenderer?.clear()
            lastDisplayedEnglishText = ""
            candidateEnglishText = ""
            pendingStreamingText = ""
            streamingStartTime = 0L
            lastDisplayWasPatch = false
            lastPatchEnglishText = ""
            consecutiveEmptyRoiCount = 0
            return
        }

        // 3. Stage 1: Strict Calibrated ROI Crop directly from ImageReader buffer
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val calRect = com.zlatan.gamelens.overlay.CalibrationOverlay.getCalibratedRect(prefs)
        val now = android.os.SystemClock.uptimeMillis()

        // In Offset Above mode, never hide overlay during capture (0% flicker, 0% self-OCR, instant 140ms capture).
        // In Opaque Cover mode, if subtitle is visible, poll at most every 2500ms (and never hide while reading hold is active):
        val isSubtitleVis = seamlessOverlayRenderer?.isSubtitleVisible() == true
        val holdActive = seamlessOverlayRenderer?.isHoldActive() == true
        val croppedRoi: Bitmap? = if (isOpaqueCover && isSubtitleVis) {
            if (now - lastOpaquePollTimestamp < 2500L || holdActive) {
                return
            }
            lastOpaquePollTimestamp = now
            withContext(Dispatchers.Main) {
                seamlessOverlayRenderer?.setInvisibleForCapture()
            }
            delay(65L)
            captureMgr.drainStaleFrame()
            val b = captureMgr.acquireCroppedRoi(calRect)
            withContext(Dispatchers.Main) {
                seamlessOverlayRenderer?.restoreVisibilityAfterCapture()
            }
            b
        } else {
            captureMgr.acquireCroppedRoi(calRect)
        }

        if (croppedRoi == null) return

        // 4. Stage 2: Settle-Then-Translate Pixel-Diff Gate (~1.5ms) & Heartbeat Scan
        val isOverlayShowing = seamlessOverlayRenderer?.isSubtitleVisible() == true
        val timeSinceLastOcr = now - lastOcrTimestamp
        val forceClearCheck = isOverlayShowing && (timeSinceLastOcr >= 350L)
        val forceHeartbeat = if (isOverlayShowing) (timeSinceLastOcr >= 350L) else (timeSinceLastOcr >= 560L) // 350ms heartbeat while subtitle is displayed

        val delta = fastPixelDiffGate.computeDiffRatio(croppedRoi)
        if (isRoiSettling && !forceInstantPass) {
            val waitElapsed = now - roiSettleStartTime
            if (delta < 0.0045f || waitElapsed >= 150L) {
                // Frame stabilized (< 0.45% delta) or max settling time reached (150ms / 1 frame) -> commit and proceed to OCR
                isRoiSettling = false
                fastPixelDiffGate.commitSamples(croppedRoi)
            } else {
                // Still settling within 1-frame window -> wait 1 frame
                if (!croppedRoi.isRecycled) croppedRoi.recycle()
                return
            }
        } else if (delta >= 0.0045f && !forceInstantPass) {
            // New motion detected: initiate 1-frame settling wait (150ms) so transitional half-faded frames settle
            isRoiSettling = true
            roiSettleStartTime = now
            fastPixelDiffGate.commitSamples(croppedRoi)
            if (!forceClearCheck && !forceHeartbeat) {
                if (!croppedRoi.isRecycled) croppedRoi.recycle()
                return
            }
        } else if (!forceClearCheck && !forceHeartbeat && !forceInstantPass) {
            // ROI static and no heartbeat/clear triggers -> skip OCR
            if (!croppedRoi.isRecycled) croppedRoi.recycle()
            return
        }

        // Proceeding with OCR: reset transient triggers & update last OCR timestamp
        isRoiSettling = false
        forceInstantPass = false
        lastOcrTimestamp = now

        // 5. Stage 3: Adaptive Precision OCR with Quality Scoring (Single-Pass Fast vs Dual-Pass High-Contrast)
        val activeProfile = com.zlatan.gamelens.profile.GameProfileManager.getActiveProfile(this)
        val ocrStartTime = android.os.SystemClock.uptimeMillis()

        val ocrInput = com.zlatan.gamelens.ocr.OcrProcessor.downscaleRoiIfNeeded(croppedRoi)
        if (ocrInput !== croppedRoi && !croppedRoi.isRecycled) {
            croppedRoi.recycle()
        }

        val rawLines = ocrEngine.recognizeRoi(ocrInput)
        val rawText = rawLines.joinToString(" ")
        val rawScore = ocrEngine.scoreOcrQuality(rawText)

        var lines = rawLines
        var filterSource = "Raw (q=%.2f)".format(java.util.Locale.US, rawScore)
        var bestScore = rawScore

        // Fast Single-Pass OCR: skip Mort Filter unless highContrastOcr toggle is active OR (rawLines not empty and rawScore < 0.80f)
        if (activeProfile.highContrastOcr || (rawLines.isNotEmpty() && rawScore < 0.80f)) {
            val mortBitmap = com.zlatan.gamelens.ocr.MortFilter.filter(ocrInput)
            if (mortBitmap != null) {
                val scaledMort = com.zlatan.gamelens.ocr.OcrProcessor.downscaleRoiIfNeeded(mortBitmap)
                if (scaledMort !== mortBitmap && !mortBitmap.isRecycled) mortBitmap.recycle()
                val mortLines = ocrEngine.recognizeRoi(scaledMort)
                if (!scaledMort.isRecycled) scaledMort.recycle()
                if (mortLines.isNotEmpty()) {
                    val mortText = mortLines.joinToString(" ")
                    val mortScore = ocrEngine.scoreOcrQuality(mortText)
                    if (mortScore > rawScore || rawLines.isEmpty()) {
                        lines = mortLines
                        bestScore = mortScore
                        filterSource = "Mort (q=%.2f)".format(java.util.Locale.US, mortScore)
                    }
                }
            }
        }

        val ocrDurationMs = android.os.SystemClock.uptimeMillis() - ocrStartTime

        if (!ocrInput.isRecycled) {
            ocrInput.recycle()
        }

        // Hard Quality Floor (0.55f): Unconditionally discard if bestScore < 0.55f (never queue for confirmation)
        if (bestScore < 0.55f) {
            if (lines.isNotEmpty()) {
                val dropMsg = "OCR skipped [low-quality-floor q=%.2f < 0.55]: \"${lines.joinToString(" ")}\"".format(java.util.Locale.US, bestScore)
                Log.d(TAG, dropMsg)
                DiagnosticLogger.log(dropMsg)
            }
            handleEmptyRoiDetection(isOpaqueCover)
            return
        }

        // 6. Stage 4: Spatial Line Grouping, HUD Filtering & Empty Checks
        val mergedRaw = lines.joinToString(" ") { it.trim() }
        val mergedEnglish = com.zlatan.gamelens.ocr.OcrTextCleaner.normalizeOcrArtifacts(mergedRaw)

        if (lines.isEmpty() ||
            mergedEnglish.isBlank() ||
            com.zlatan.gamelens.ocr.OcrTextCleaner.isNoiseOrPrompt(mergedEnglish) ||
            com.zlatan.gamelens.ocr.OcrTextCleaner.isGarbledDocumentNoise(mergedEnglish) ||
            com.zlatan.gamelens.ocr.OcrTextCleaner.isDiacriticSelfOcrGarble(mergedEnglish)
        ) {
            if (mergedRaw.isNotBlank()) {
                val dropReason = when {
                    com.zlatan.gamelens.ocr.OcrTextCleaner.isNoiseOrPrompt(mergedEnglish) -> "noise/prompt"
                    com.zlatan.gamelens.ocr.OcrTextCleaner.isGarbledDocumentNoise(mergedEnglish) -> "garbled-doc"
                    com.zlatan.gamelens.ocr.OcrTextCleaner.isDiacriticSelfOcrGarble(mergedEnglish) -> "diacritic-garble"
                    else -> "empty/blank"
                }
                Log.d(TAG, "OCR skipped [$dropReason]: \"$mergedRaw\"")
                DiagnosticLogger.log("OCR skipped [$dropReason]: \"$mergedRaw\"")
            }
            handleEmptyRoiDetection(isOpaqueCover)
            return
        }

        // Subtitle detected: reset empty counter and update last visible timestamp
        consecutiveEmptyRoiCount = 0
        lastSubtitleVisibleOnScreenMs = now
        seamlessOverlayRenderer?.keepSubtitleAlive()

        // Spaceless Jitter Gate: compare with currently displayed subtitle
        if (lastDisplayedEnglishText.isNotEmpty() && com.zlatan.gamelens.ocr.OcrTextCleaner.isSameActiveSubtitle(lastDisplayedEnglishText, mergedEnglish)) {
            consecutiveEmptyRoiCount = 0
            lastSubtitleVisibleOnScreenMs = now
            seamlessOverlayRenderer?.keepSubtitleAlive()

            // Quality-Upgrade Only Rule: skip re-translation UNLESS candidate genuinely upgrades dictionary coverage
            val allowUpgrade = !lastDisplayWasPatch && run {
                val candidateCov = com.zlatan.gamelens.ocr.EnglishWordRepairer.dictionaryCoverage(mergedEnglish)
                val displayedCov = com.zlatan.gamelens.ocr.EnglishWordRepairer.dictionaryCoverage(lastDisplayedEnglishText)
                (candidateCov - displayedCov) >= 0.12f
            }

            if (!allowUpgrade) {
                Log.d(TAG, "OCR skipped [spaceless-jitter/quality-gate]: \"$mergedEnglish\" (matches displayed: \"$lastDisplayedEnglishText\")")
                return
            }
            Log.d(TAG, "OCR quality-upgrade allowed for active subtitle: \"$lastDisplayedEnglishText\" -> \"$mergedEnglish\"")
        }

        // Continuous Anti-Pollution Lock: ignore shorter subsets while subtitle is continuously visible on screen (consecutiveEmptyRoiCount == 0) or within 5000ms
        if (lastDisplayedEnglishText.isNotEmpty() && (consecutiveEmptyRoiCount == 0 || now - lastSubtitleDisplayTimestamp < 5000L)) {
            if (com.zlatan.gamelens.ocr.OcrTextCleaner.isShorterSubsetOrPrefix(mergedEnglish, lastDisplayedEnglishText)) {
                val dropMsg = "OCR skipped [shorter-subset]: \"$mergedEnglish\" (subset of: \"$lastDisplayedEnglishText\")"
                Log.d(TAG, dropMsg)
                DiagnosticLogger.log(dropMsg)
                consecutiveEmptyRoiCount = 0
                lastSubtitleVisibleOnScreenMs = now
                seamlessOverlayRenderer?.keepSubtitleAlive()
                return
            }
        }

        // Clean-Superset Overwrite Guard & Delta-Aware Progressive Auto-Typewriter
        if (lastDisplayedEnglishText.isNotEmpty() && (consecutiveEmptyRoiCount == 0 || now - lastSubtitleDisplayTimestamp < 6000L)) {
            val verdict = com.zlatan.gamelens.ocr.OcrEngine.classifySupersetExpansion(lastDisplayedEnglishText, mergedEnglish)
            val isFuzzyPolluted = com.zlatan.gamelens.ocr.OcrEngine.isPollutedFuzzySuperset(lastDisplayedEnglishText, mergedEnglish)
            if ((verdict == com.zlatan.gamelens.ocr.OcrEngine.SupersetVerdict.CREDIT_POLLUTION || isFuzzyPolluted) &&
                PatchManager.lookup(mergedEnglish) == null
            ) {
                val dropMsg = "OCR skipped [credit-polluted-superset]: \"$mergedEnglish\" (polluted re-read of: \"$lastDisplayedEnglishText\")"
                Log.d(TAG, dropMsg)
                DiagnosticLogger.log(dropMsg)
                consecutiveEmptyRoiCount = 0
                lastSubtitleVisibleOnScreenMs = now
                seamlessOverlayRenderer?.keepSubtitleAlive()
                return
            }

            if (verdict == com.zlatan.gamelens.ocr.OcrEngine.SupersetVerdict.CLEAN_PROGRESSIVE_EXPANSION) {
                val isTypewriterEnabled = prefs.getBoolean(com.zlatan.gamelens.MainActivity.KEY_TYPEWRITER_MODE, false)
                if (isTypewriterEnabled) {
                    val hasEndingPunctuation = mergedEnglish.endsWith(".") || mergedEnglish.endsWith("!") || mergedEnglish.endsWith("?") ||
                            mergedEnglish.endsWith(".\"") || mergedEnglish.endsWith("!\"") || mergedEnglish.endsWith("?\"")

                    if (!hasEndingPunctuation) {
                        if (pendingStreamingText != mergedEnglish) {
                            pendingStreamingText = mergedEnglish
                            streamingStartTime = now
                            forceInstantPass = true
                            Log.d(TAG, "OCR streaming progressive expansion debounced (1-cycle wait): \"$mergedEnglish\"")
                            return
                        } else {
                            val streamElapsed = now - streamingStartTime
                            if (streamElapsed < 650L) {
                                forceInstantPass = true
                                Log.d(TAG, "OCR streaming progressive expansion debounced (wait=${streamElapsed}ms < 650ms): \"$mergedEnglish\"")
                                return
                            }
                        }
                    }
                }
                pendingStreamingText = ""
                streamingStartTime = 0L
            }
        }

        // Typewriter / Streaming Text Mode (JRPG / Visual Novels)
        val isTypewriterEnabled = prefs.getBoolean(com.zlatan.gamelens.MainActivity.KEY_TYPEWRITER_MODE, false)
        if (isTypewriterEnabled && PatchManager.lookup(mergedEnglish) == null) {
            val hasTerminal = mergedEnglish.endsWith(".") || mergedEnglish.endsWith("!") || mergedEnglish.endsWith("?") ||
                    mergedEnglish.endsWith(".\"") || mergedEnglish.endsWith("!\"") || mergedEnglish.endsWith("?\"") ||
                    mergedEnglish.endsWith(".'") || mergedEnglish.endsWith("!'") || mergedEnglish.endsWith("?'")

            val prefixLen = minOf(12, typewriterPendingText.length)
            val isGrowthExtension = typewriterPendingText.isNotEmpty() &&
                    mergedEnglish.length > typewriterPendingText.length &&
                    (prefixLen == 0 || mergedEnglish.startsWith(typewriterPendingText.take(prefixLen), ignoreCase = true))

            if (isGrowthExtension) {
                typewriterPendingText = mergedEnglish
                typewriterLastGrowthTime = now
                forceInstantPass = true
                Log.d(TAG, "Typewriter growth detected, holding translation: \"$mergedEnglish\"")
                return
            } else if (typewriterPendingText.isNotEmpty()) {
                val growthIdle = now - typewriterLastGrowthTime
                val holdDuration = if (hasTerminal) 150L else 350L
                if (growthIdle < holdDuration) {
                    forceInstantPass = true
                    return
                }
                typewriterPendingText = ""
            } else if (!hasTerminal) {
                typewriterPendingText = mergedEnglish
                typewriterLastGrowthTime = now
                forceInstantPass = true
                Log.d(TAG, "Typewriter initial chunk buffered: \"$mergedEnglish\"")
                return
            }
        }

        // 7. Normalization, Patch Matching (0ms snap) and Candidate Handling
        val promptNormalized = translationManager.normalizeControllerPrompts(mergedEnglish)
        val patchMatch = PatchManager.lookup(promptNormalized)

        // Active Patch-Lock Guard: Lock active patch lines for 8000ms against corrupted non-patch re-reads
        if (lastDisplayWasPatch && (now - lastPatchDisplayTimestamp < 8000L) && lastPatchEnglishText.isNotEmpty()) {
            if (patchMatch == null) {
                val patchWords = lastPatchEnglishText.lowercase(java.util.Locale.US).split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
                val incomingWords = promptNormalized.lowercase(java.util.Locale.US).split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
                if (patchWords.isNotEmpty() && incomingWords.isNotEmpty()) {
                    val commonWords = patchWords.intersect(incomingWords)
                    val overlap = commonWords.size.toFloat() / patchWords.size.toFloat()
                    if (overlap >= 0.55f) {
                        val dropMsg = "OCR dropped by Active Patch-Lock Guard (overlap=%.2f >= 0.55 with active patch \"$lastPatchEnglishText\")".format(java.util.Locale.US, overlap)
                        Log.d(TAG, dropMsg)
                        DiagnosticLogger.log(dropMsg)
                        consecutiveEmptyRoiCount = 0
                        lastSubtitleVisibleOnScreenMs = now
                        seamlessOverlayRenderer?.keepSubtitleAlive()
                        return
                    }
                }
            }
        }

        if (patchMatch != null) {
            // Patch matched: instant 0ms snap translation!
            candidateEnglishText = ""
            Log.d(TAG, "Patch snapped (0ms): \"$promptNormalized\"")
        } else if (bestScore >= 0.70f) {
            // High-confidence clean candidate (>= 0.70f): translate immediately on first pass (0ms delay)
            candidateEnglishText = ""
            Log.d(TAG, "High-confidence OCR passed directly (q=%.2f): \"$promptNormalized\"".format(java.util.Locale.US, bestScore))
        } else {
            // Low-confidence candidate (< 0.70f): require 2-frame stability confirmation within 1000ms
            val nowMs = android.os.SystemClock.uptimeMillis()
            val isCandidateMatch = candidateEnglishText.isNotEmpty() &&
                    (nowMs - candidateTimestamp <= 1000L) &&
                    (com.zlatan.gamelens.ocr.OcrTextCleaner.isSameActiveSubtitle(candidateEnglishText, promptNormalized) ||
                     com.zlatan.gamelens.ocr.OcrTextCleaner.isSimilarForCache(promptNormalized, candidateEnglishText, 0.80f))

            if (!isCandidateMatch) {
                // First frame of low-confidence candidate: store and force instant pass on the very next 140ms poll
                candidateEnglishText = promptNormalized
                candidateTimestamp = nowMs
                forceInstantPass = true
                val dropMsg = "OCR skipped [pending-confirmation q=%.2f]: \"$promptNormalized\" (waiting 2nd frame <= 1000ms)".format(java.util.Locale.US, bestScore)
                Log.d(TAG, dropMsg)
                DiagnosticLogger.log(dropMsg)
                consecutiveEmptyRoiCount = 0
                lastSubtitleVisibleOnScreenMs = now
                seamlessOverlayRenderer?.keepSubtitleAlive()
                return
            }
            // 2nd frame confirmed within 1000ms! Clear candidate and proceed to translate
            candidateEnglishText = ""
            Log.d(TAG, "Low-confidence OCR confirmed on 2nd frame: \"$promptNormalized\"")
        }

        // Final guard against orientation flip, capture resize cooldown, or foreground switch right before translation
        val (finalW, finalH, _) = getRealScreenDimensions()
        val (finalCurW, finalCurH) = captureMgr.getDimensions()
        val finalRealtime = android.os.SystemClock.elapsedRealtime()
        if ((finalRealtime - lastResizeTimestampMs < 650L) ||
            AppForegroundState.isMainUiVisible.get() ||
            com.zlatan.gamelens.MainActivity.isAppInForeground
        ) {
            return
        }

        // Decoupled asynchronous translation with Generation Epoch
        val capturedGen = subtitleGeneration.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = serviceScope.launch {
            val transStartTime = android.os.SystemClock.uptimeMillis()
            val speakerResult = com.zlatan.gamelens.translation.SpeakerDetector.detect(promptNormalized)
            val textToTranslate = if (speakerResult.hasSpeaker) speakerResult.dialogue else promptNormalized

            val rawArabic = if (patchMatch != null) {
                patchMatch
            } else {
                val patchBody = PatchManager.lookup(textToTranslate)
                if (patchBody != null) {
                    patchBody
                } else {
                    val memMatch = translationManager.getFromMemoryCache(textToTranslate)
                    if (memMatch != null) {
                        memMatch
                    } else {
                        withContext(Dispatchers.IO) {
                            translationManager.translate(textToTranslate)
                        }
                    }
                }
            }

            val transDurationMs = android.os.SystemClock.uptimeMillis() - transStartTime

            if (capturedGen != subtitleGeneration.get()) {
                Log.d(TAG, "Translation dropped due to newer generation (gen=$capturedGen, curr=${subtitleGeneration.get()}): \"$promptNormalized\"")
                return@launch
            }

            val arabicTranslation = if (patchMatch != null) rawArabic else translationManager.repairGluedArabicWords(rawArabic)

            if (arabicTranslation.isBlank() || arabicTranslation.trim().equals(textToTranslate.trim(), ignoreCase = true) || !ARABIC_REGEX.containsMatchIn(arabicTranslation)) {
                val dropMsg = "OCR skipped [empty-or-non-arabic]: \"$promptNormalized\" -> \"$arabicTranslation\""
                Log.d(TAG, dropMsg)
                DiagnosticLogger.log(dropMsg)
                return@launch
            }

            val speakerDisplay = if (speakerResult.hasSpeaker && speakerResult.speaker != null) {
                val curProfile = com.zlatan.gamelens.profile.GameProfileManager.getActiveProfile(this@TranslationOverlayService)
                val glossaryEntries = com.zlatan.gamelens.translation.GameGlossaryManager.parse(curProfile.glossaryText)
                val matchedEntry = glossaryEntries.find { it.term.equals(speakerResult.speaker, ignoreCase = true) }
                matchedEntry?.target ?: com.zlatan.gamelens.translation.TranslationManager.DEFAULT_PROTECTED_GLOSSARY[speakerResult.speaker] ?: speakerResult.speaker
            } else null

            withContext(Dispatchers.Main) {
                if (capturedGen != subtitleGeneration.get()) return@withContext

                lastDisplayedEnglishText = mergedEnglish
                lastSubtitleDisplayTimestamp = android.os.SystemClock.uptimeMillis()
                lastSubtitleVisibleOnScreenMs = android.os.SystemClock.uptimeMillis()
                consecutiveEmptyRoiCount = 0
                pendingStreamingText = ""
                streamingStartTime = 0L

                if (patchMatch != null) {
                    lastPatchEnglishText = promptNormalized
                    lastDisplayWasPatch = true
                    lastPatchDisplayTimestamp = android.os.SystemClock.uptimeMillis()
                } else {
                    lastPatchEnglishText = ""
                    lastDisplayWasPatch = false
                    lastPatchDisplayTimestamp = 0L
                }

                // Render onto the solid opaque bar with persisted font size and dynamic reading speed duration
                seamlessOverlayRenderer?.renderSubtitle(arabicTranslation, promptNormalized, speakerDisplay)
                pulseHeartbeat()
                DiagnosticLogger.log("Precision OCR [$filterSource (ocr=${ocrDurationMs}ms, trans=${transDurationMs}ms)]: \"$promptNormalized\" -> \"$arabicTranslation\"")
                Log.i(TAG, "Precision OCR [$filterSource (ocr=${ocrDurationMs}ms, trans=${transDurationMs}ms)]: \"$promptNormalized\" -> \"$arabicTranslation\"")
            }
        }
    }

    private suspend fun handleEmptyRoiDetection(isOpaqueCover: Boolean) {
        consecutiveEmptyRoiCount++
        val isSubtitleShowing = seamlessOverlayRenderer?.isSubtitleVisible() == true
        if (isSubtitleShowing) {
            if (consecutiveEmptyRoiCount == 1) {
                // Cancel stale in-flight translation immediately
                subtitleGeneration.incrementAndGet()
                activeTranslationJob?.cancel()

                delay(60L)
                val verifyBmp = captureFrameForOcr(isOpaqueCover)
                if (verifyBmp != null) {
                    val scaledVerify = com.zlatan.gamelens.ocr.OcrProcessor.downscaleRoiIfNeeded(verifyBmp)
                    val verifyLines = ocrEngine.recognizeRoi(scaledVerify)
                    if (scaledVerify !== verifyBmp && !verifyBmp.isRecycled) verifyBmp.recycle()
                    if (!scaledVerify.isRecycled) scaledVerify.recycle()

                    val verifyClean = verifyLines.map { com.zlatan.gamelens.ocr.OcrTextCleaner.normalizeOcrArtifacts(it) }
                        .filter { it.isNotBlank() && !com.zlatan.gamelens.ocr.OcrTextCleaner.isNoiseOrPrompt(it) }
                    if (verifyClean.isEmpty()) {
                        seamlessOverlayRenderer?.clear()
                        lastDisplayedEnglishText = ""
                        candidateEnglishText = ""
                        pendingStreamingText = ""
                        streamingStartTime = 0L
                        lastDisplayWasPatch = false
                        lastPatchEnglishText = ""
                        consecutiveEmptyRoiCount = 0
                    }
                }
            } else if (consecutiveEmptyRoiCount >= 2) {
                seamlessOverlayRenderer?.clear()
                lastDisplayedEnglishText = ""
                candidateEnglishText = ""
                pendingStreamingText = ""
                streamingStartTime = 0L
                lastDisplayWasPatch = false
                lastPatchEnglishText = ""
                consecutiveEmptyRoiCount = 0
            }
        }
    }

    private fun normalizeForJitterGate(text: String): String {
        return text.lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0) return len2
        if (len2 == 0) return len1
        if (Math.abs(len1 - len2) > 2) return Math.abs(len1 - len2)

        val dp = IntArray(len2 + 1) { it }
        for (i in 1..len1) {
            var prevDiag = dp[0]
            dp[0] = i
            val c1 = s1[i - 1]
            for (j in 1..len2) {
                val temp = dp[j]
                val cost = if (c1 == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prevDiag + cost)
                prevDiag = temp
            }
        }
        return dp[len2]
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        if (bubbleView != null) return

        try {
            bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
            ivBubbleIcon = bubbleView?.findViewById(R.id.ivBubbleIcon)
            viewHeartbeatDot = bubbleView?.findViewById(R.id.viewHeartbeatDot)

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
                showQuickActionMenu()
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
                        val clickTolerance = 12f * resources.displayMetrics.density
                        if (Math.abs(deltaX) > clickTolerance || Math.abs(deltaY) > clickTolerance) {
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
                            handleSingleTap()
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
        if (isMenuScanInProgress) return

        if (isMenuModalVisible || seamlessOverlayRenderer?.hasPinnedMenuCards() == true) {
            seamlessOverlayRenderer?.clearMenuCards()
            isMenuModalVisible = false
            screenCaptureManager?.setMenuModalVisible(false)
            showToast("تم إخفاء ترجمة القائمة والعودة للعب")
            updateBubbleVisualState()
        } else {
            triggerZeroSelfReadMenuScan()
        }
    }

    private fun handleDoubleTap() {
        toggleAudioMode()
    }

    private fun requestProjectionReacquisition(autoStartAudio: Boolean) {
        try {
            DiagnosticLogger.log("Requesting MediaProjection reacquisition (autoStartAudio=$autoStartAudio)")
            val intent = Intent(this, ProjectionReacquireActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_AUTO_START_AUDIO, autoStartAudio)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ProjectionReacquireActivity: ${e.message}", e)
            showToast("تعذر فتح نافذة تجديد إذن التقاط الشاشة")
        }
    }

    private fun startAudioTranslation() {
        if (!GameAudioTranslator.isAsrModelInstalled(this)) {
            showToast("يلزم تنزيل وتثبيت نموذج الصوت أولاً من التطبيق (~122 MB)")
            return
        }

        val proj = mediaProjection
        if (proj == null || !isProjectionValid) {
            requestProjectionReacquisition(autoStartAudio = true)
            return
        }

        isAudioModeActive = true
        // Clear all overlays and pinned cards when activating audio subtitle mode
        seamlessOverlayRenderer?.clearAllOverlays()

        gameAudioTranslator?.stop()
        gameAudioTranslator = GameAudioTranslator(
            context = this,
            mediaProjection = proj,
            translationManager = translationManager,
            onSubtitleReady = { subtitle ->
                mainHandler.post {
                    seamlessOverlayRenderer?.renderSubtitle(subtitle)
                }
            },
            onStateChanged = {
                mainHandler.post {
                    updateBubbleVisualState()
                }
            }
        ).also {
            it.start()
        }

        showToast("تم تفعيل ترجمة صوت اللعبة المباشرة (الشريط السينمائي)")
        updateBubbleVisualState()
    }

    private fun stopAudioTranslation() {
        isAudioModeActive = false
        gameAudioTranslator?.stop()
        gameAudioTranslator = null
        seamlessOverlayRenderer?.clearAllOverlays()
        showToast("تم إيقاف ترجمة صوت اللعبة (وضع الاستعداد)")
        updateBubbleVisualState()
    }

    private fun toggleAudioMode() {
        if (isAudioModeActive) {
            stopAudioTranslation()
        } else {
            if (mediaProjection == null || !isProjectionValid) {
                DiagnosticLogger.log("toggleAudioMode: Projection invalid, requesting re-acquisition")
                requestProjectionReacquisition(autoStartAudio = true)
                return
            }
            startAudioTranslation()
        }
    }

    private fun triggerZeroSelfReadMenuScan() {
        if (isMenuScanInProgress) return
        if (mediaProjection == null || !isProjectionValid) {
            showToast("إذن التقاط الشاشة غير نشط، جاري التجديد...")
            requestProjectionReacquisition(autoStartAudio = false)
            return
        }

        isMenuScanInProgress = true
        updateBubbleVisualState()

        serviceScope.launch {
            try {
                val captureMgr = screenCaptureManager ?: run {
                    showToast("خدمة التقاط الشاشة غير متصلة")
                    isMenuScanInProgress = false
                    updateBubbleVisualState()
                    return@launch
                }

                // Zero-Interference Capture: Hide bubble and overlay cleanly without pre-capture Toast
                withContext(Dispatchers.Main) {
                    bubbleView?.visibility = View.INVISIBLE
                    seamlessOverlayRenderer?.setInvisibleForCapture()
                }
                captureMgr.clearFullScreenCache()
                delay(120L)

                val fullBitmap = captureMgr.acquireFullScreenBitmap()

                withContext(Dispatchers.Main) {
                    bubbleView?.visibility = View.VISIBLE
                    seamlessOverlayRenderer?.restoreVisibilityAfterCapture()
                }

                if (fullBitmap == null) {
                    DiagnosticLogger.log("MenuScan: acquireFullScreenBitmap returned null")
                    showToast("تعذر التقاط إطار الشاشة، يرجى المحاولة ثانية")
                    isMenuScanInProgress = false
                    updateBubbleVisualState()
                    return@launch
                }

                val (screenW, screenH, _) = getRealScreenDimensions()

                // Dedicated Full-Screen Menu / Popup OCR Mode (no ROI cropping, preserves headers)
                val detectedBlocks = ocrEngine.recognizeFullScreenMenu(fullBitmap)
                try {
                    if (!fullBitmap.isRecycled) {
                        fullBitmap.recycle()
                    }
                } catch (_: Throwable) {}
                DiagnosticLogger.log("MenuScan: captured ${fullBitmap.width}x${fullBitmap.height}, detected blocks=${detectedBlocks.size}")
                detectedBlocks.forEachIndexed { i, b ->
                    DiagnosticLogger.log("MenuBlock #$i: ${b.text.take(100).replace("\n", " ")}")
                }

                if (detectedBlocks.isEmpty()) {
                    DiagnosticLogger.log("MenuScan: No text blocks detected on screen")
                    showToast("لم يتم العثور على نصوص واضحة في القائمة")
                    isMenuScanInProgress = false
                    updateBubbleVisualState()
                    return@launch
                }

                // Parallel block translation (coroutineScope { blocks.map { async { ... } }.awaitAll() })
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        detectedBlocks.map { block ->
                            async {
                                val cleanedText = com.zlatan.gamelens.ocr.OcrTextCleaner.cleanMenuScanArtifacts(block.text)
                                val repairedText = com.zlatan.gamelens.ocr.EnglishWordRepairer.repair(cleanedText)
                                val translated = translationManager.translate(repairedText)
                                val polished = com.zlatan.gamelens.translation.TranslationQualityPolisher.polishArabic(translated)
                                block.translatedText = polished
                            }
                        }.awaitAll()
                    }
                }

                val arabicBlocks = detectedBlocks.filter { block ->
                    val trans = block.translatedText
                    !trans.isNullOrBlank() &&
                    !trans.trim().equals(block.text.trim(), ignoreCase = true) &&
                    ARABIC_REGEX.containsMatchIn(trans)
                }

                if (arabicBlocks.isEmpty()) {
                    DiagnosticLogger.log("MenuScan: Translation returned no Arabic text")
                    showToast("لم تتوفر ترجمة عربية لنصوص القائمة")
                    isMenuScanInProgress = false
                    updateBubbleVisualState()
                    return@launch
                }

                isMenuModalVisible = true
                screenCaptureManager?.setMenuModalVisible(true)
                isMenuScanInProgress = false

                // Render professional RTL Arabic modal card with explicit close button
                seamlessOverlayRenderer?.renderFullScreenMenuModal(
                    blocks = arabicBlocks,
                    screenW = screenW,
                    screenH = screenH
                )
                pulseHeartbeat()
                updateBubbleVisualState()
                DiagnosticLogger.log("MenuScan: Rendered modal with ${arabicBlocks.size} blocks successfully")
            } catch (e: Exception) {
                isMenuScanInProgress = false
                Log.e(TAG, "Zero-Self-Read scan error: ${e.message}", e)
                DiagnosticLogger.log("MenuScan error: ${e.javaClass.simpleName} - ${e.message}")
                withContext(Dispatchers.Main) {
                    bubbleView?.visibility = View.VISIBLE
                    seamlessOverlayRenderer?.restoreVisibilityAfterCapture()
                    showToast("حدث خطأ أثناء مسح القائمة")
                    updateBubbleVisualState()
                }
            }
        }
    }

    private fun startVisualCalibration() {
        mainHandler.post {
            if (isCalibrationActive) return@post
            isCalibrationActive = true
            updateBubbleVisualState()
            calibrationOverlay?.dismiss()
            calibrationOverlay = com.zlatan.gamelens.overlay.CalibrationOverlay(this) {
                isCalibrationActive = false
                updateBubbleVisualState()
                showToast("تم تحديث وحفظ منطقة معايرة القراءة")
            }.also {
                it.show()
            }
        }
    }

    private fun updateBubbleVisualState() {
        mainHandler.post {
            val density = resources.displayMetrics.density
            val hasPinned = isMenuModalVisible || seamlessOverlayRenderer?.hasPinnedMenuCards() == true

            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    isCalibrationActive -> {
                        // Amber when Calibration is Active
                        setColor(Color.parseColor("#FFB300"))
                        setStroke((2.5f * density).toInt(), Color.WHITE)
                    }
                    isAudioModeActive -> {
                        // Cyan (#00BCD4): Live Voice ASR active
                        setColor(Color.parseColor("#00BCD4"))
                        setStroke((2.5f * density).toInt(), Color.parseColor("#E0F7FA"))
                    }
                    isAutoTranslationActive || hasPinned -> {
                        // Emerald Green (#10B981): Visual Subtitle OCR active
                        setColor(Color.parseColor("#10B981"))
                        setStroke((2 * density).toInt(), Color.WHITE)
                    }
                    else -> {
                        // Slate (#475569): Paused / Standby
                        setColor(Color.parseColor("#475569"))
                        setStroke((1.5f * density).toInt(), Color.parseColor("#94A3B8"))
                    }
                }
            }
            bubbleView?.background = backgroundDrawable
            bubbleView?.alpha = if (isAudioModeActive || hasPinned || isAutoTranslationActive) 1.0f else 0.85f
            ivBubbleIcon?.setColorFilter(Color.WHITE)
        }
    }

    private fun pulseHeartbeat() {
        mainHandler.post {
            val dot = viewHeartbeatDot ?: return@post
            dot.visibility = View.VISIBLE
            dot.alpha = 1.0f
            dot.scaleX = 1.3f
            dot.scaleY = 1.3f
            dot.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(0.0f)
                .setDuration(800L)
                .withEndAction {
                    dot.visibility = View.GONE
                }
                .start()
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

    private fun showQuickActionMenu() {
        mainHandler.post {
            dismissQuickActionMenu()
            try {
                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.overlay_quick_menu, null)
                val density = resources.displayMetrics.density

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    (320 * density).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                val btnClose = view.findViewById<View>(R.id.btnQuickClose)
                val btnPauseResume = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuickPauseResume)
                val btnRecentSubs = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuickRecentSubtitles)
                val tvRecentSubs = view.findViewById<TextView>(R.id.tvQuickRecentSubs)
                val btnToggleDisplayMode = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuickToggleDisplayMode)
                val btnRecalibrate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuickRecalibrate)

                // 1. Pause / Resume state
                btnPauseResume.text = if (isAutoTranslationActive) "إيقاف مؤقت للترجمة" else "استئناف الترجمة"
                btnPauseResume.setOnClickListener {
                    isAutoTranslationActive = !isAutoTranslationActive
                    updateBubbleVisualState()
                    btnPauseResume.text = if (isAutoTranslationActive) "إيقاف مؤقت للترجمة" else "استئناف الترجمة"
                    showToast(if (isAutoTranslationActive) "تم استئناف الترجمة" else "تم إيقاف الترجمة مؤقتاً")
                }

                // 2. Recent Subtitles
                btnRecentSubs.setOnClickListener {
                    if (tvRecentSubs.visibility == View.VISIBLE) {
                        tvRecentSubs.visibility = View.GONE
                    } else {
                        val recent = com.zlatan.gamelens.diag.SubtitleHistoryManager.historyFlow.value.takeLast(5)
                        tvRecentSubs.text = if (recent.isEmpty()) "لا توجد حوارات مسجلة بعد" else recent.joinToString("\n\n") { "[${it.timestamp}]\nEN: ${it.source}\nAR: ${it.arabic}" }
                        tvRecentSubs.visibility = View.VISIBLE
                    }
                }

                // 3. Display Mode Toggle
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val curMode = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OFFSET_ABOVE) ?: MainActivity.MODE_OFFSET_ABOVE
                var isCover = curMode == MainActivity.MODE_OPAQUE_COVER
                btnToggleDisplayMode.text = if (isCover) "نمط العرض: غطاء معتم (انقر للتبديل)" else "نمط العرض: فوق الحوار (انقر للتبديل)"
                btnToggleDisplayMode.setOnClickListener {
                    isCover = !isCover
                    val newMode = if (isCover) MainActivity.MODE_OPAQUE_COVER else MainActivity.MODE_OFFSET_ABOVE
                    prefs.edit().putString(MainActivity.KEY_OVERLAY_RENDER_MODE, newMode).apply()
                    btnToggleDisplayMode.text = if (isCover) "نمط العرض: غطاء معتم (انقر للتبديل)" else "نمط العرض: فوق الحوار (انقر للتبديل)"
                    showToast(if (isCover) "تم تفعيل نمط الغطاء المعتم (Cover)" else "تم تفعيل نمط فوق الحوار (Offset)")
                }

                // 4. Quick ROI Recalibration
                btnRecalibrate.setOnClickListener {
                    dismissQuickActionMenu()
                    startVisualCalibration()
                }

                // 5. Toggle Game Audio ASR
                val btnToggleAudio = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuickToggleAudio)
                btnToggleAudio?.text = if (isAudioModeActive) "إيقاف ترجمة صوت اللعبة" else "تفعيل ترجمة صوت اللعبة"
                btnToggleAudio?.setOnClickListener {
                    toggleAudioMode()
                    btnToggleAudio.text = if (isAudioModeActive) "إيقاف ترجمة صوت اللعبة" else "تفعيل ترجمة صوت اللعبة"
                }

                btnClose.setOnClickListener {
                    dismissQuickActionMenu()
                }

                view.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        dismissQuickActionMenu()
                        true
                    } else false
                }

                quickMenuView = view
                quickMenuParams = params
                windowManager.addView(view, params)
                Log.d(TAG, "Quick action floating menu displayed")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to display quick action menu: ${t.message}", t)
            }
        }
    }

    private fun dismissQuickActionMenu() {
        mainHandler.post {
            quickMenuView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
                quickMenuView = null
                quickMenuParams = null
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            DiagnosticLogger.log("TranslationOverlayService: onTrimMemory level=$level")
            screenCaptureManager?.clearFullScreenCache()
            translationManager.trimMemory()
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                fastPixelDiffGate.reset()
                frameDiffEngine.reset()
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticLogger.log("Service: onDestroy")
        dismissQuickActionMenu()
        captureJob?.cancel()
        serviceScope.cancel()

        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        displayManager = null

        seamlessOverlayRenderer?.release()
        seamlessOverlayRenderer = null

        calibrationOverlay?.dismiss()
        calibrationOverlay = null

        gameAudioTranslator?.stop()
        gameAudioTranslator = null

        screenCaptureManager?.release()
        screenCaptureManager = null

        ocrEngine.close()
        translationManager.close()
        com.zlatan.gamelens.diag.SubtitleHistoryManager.flush(this)
        com.zlatan.gamelens.patch.PatchManager.flushPendingSession(this)

        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            bubbleView = null
        }

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}
        mediaProjection?.stop()
        mediaProjection = null
        isProjectionValid = false
    }
}

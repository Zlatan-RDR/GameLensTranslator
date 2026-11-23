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
        const val ACTION_CALIBRATE = "com.zlatan.gamelens.ACTION_CALIBRATE"
        const val ACTION_UPDATE_PROJECTION = "com.zlatan.gamelens.ACTION_UPDATE_PROJECTION"
        const val ACTION_UPDATE_FONT_SIZE = "com.zlatan.gamelens.ACTION_UPDATE_FONT_SIZE"
        const val ACTION_TEST_OVERLAY = "com.zlatan.gamelens.ACTION_TEST_OVERLAY"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUTO_START_AUDIO = "extra_auto_start_audio"
        const val EXTRA_FONT_SIZE_SP = "extra_font_size_sp"

        private const val NOTIFICATION_CHANNEL_ID = "gamelens_translation_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLICK_DRAG_TOLERANCE = 15f
        private const val LONG_PRESS_TIMEOUT_MS = 750L
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
    private var lastOcrTimestamp: Long = 0L
    private var lastDisplayedEnglishText: String = ""
    private var candidateEnglishText: String = ""
    private var candidateTimestamp: Long = 0L

    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var glossaryManager: GlossaryManager
    private lateinit var translationManager: TranslationManager

    private var seamlessOverlayRenderer: SeamlessOverlayRenderer? = null
    private var latestDetectedBlocks: List<OcrBlock> = emptyList()
    private var consecutiveEmptyOcrPasses: Int = 0

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
            DiagnosticLogger.log("Precision ROI OCR loop started (140ms)")
            while (isActive) {
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val isOpaqueCover = prefs.getString(MainActivity.KEY_OVERLAY_RENDER_MODE, MainActivity.MODE_OPAQUE_COVER) == MainActivity.MODE_OPAQUE_COVER

                try {
                    processFramePipeline(isOpaqueCover)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in Pipeline: ${t.message}", t)
                    DiagnosticLogger.log("Pipeline error: ${t.javaClass.simpleName} - ${t.message}")
                }
                delay(140L)
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
        val (screenW, screenH, dpi) = getRealScreenDimensions()

        // 1. Auto-pause in Portrait or when GameLens is in the foreground
        if (screenH > screenW || com.zlatan.gamelens.MainActivity.isAppInForeground) {
            if (seamlessOverlayRenderer?.isSubtitleVisible() == true) {
                seamlessOverlayRenderer?.clear()
                lastDisplayedEnglishText = ""
                candidateEnglishText = ""
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
        }

        if (curH > curW) {
            seamlessOverlayRenderer?.clear()
            lastDisplayedEnglishText = ""
            candidateEnglishText = ""
            return
        }

        // 3. Stage 1: Strict Calibrated ROI Crop directly from ImageReader buffer
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val calRect = com.zlatan.gamelens.overlay.CalibrationOverlay.getCalibratedRect(prefs)

        // For Opaque Cover mode, if the solid black bar is currently visible, hide it for a 20ms pulse to read under it
        val croppedRoi: Bitmap? = if (isOpaqueCover && seamlessOverlayRenderer?.isSubtitleVisible() == true) {
            withContext(Dispatchers.Main) {
                seamlessOverlayRenderer?.setInvisibleForCapture()
            }
            delay(20L)
            val b = captureMgr.acquireCroppedRoi(calRect)
            withContext(Dispatchers.Main) {
                seamlessOverlayRenderer?.restoreVisibilityAfterCapture()
            }
            b
        } else {
            captureMgr.acquireCroppedRoi(calRect)
        }

        if (croppedRoi == null) return

        // 4. Stage 2: Fast Pixel-Diff Gate (~1.5ms) - skip unchanged frames unless subtitle is showing and clear-check (420ms) is due
        val isOverlayShowing = seamlessOverlayRenderer?.isSubtitleVisible() == true
        val now = android.os.SystemClock.uptimeMillis()
        val timeSinceLastOcr = now - lastOcrTimestamp
        val forceClearCheck = isOverlayShowing && (timeSinceLastOcr >= 420L)

        val hasChanged = fastPixelDiffGate.hasChanged(croppedRoi)
        if (!hasChanged && !forceClearCheck && !forceInstantPass) {
            if (!croppedRoi.isRecycled) croppedRoi.recycle()
            return
        }
        forceInstantPass = false
        lastOcrTimestamp = now

        // 5. Stage 3: MORT Subtitle Luminance Isolation Mask with Raw-Crop Fallback
        var filterSource = "Mort"
        val mortBitmap = com.zlatan.gamelens.ocr.MortFilter.filter(croppedRoi)
        var lines = if (mortBitmap != null) {
            val res = ocrEngine.recognizeRoi(mortBitmap)
            if (!mortBitmap.isRecycled) mortBitmap.recycle()
            res
        } else emptyList()

        if (lines.isEmpty()) {
            // Fallback once to raw cropped ROI
            lines = ocrEngine.recognizeRoi(croppedRoi)
            filterSource = "Raw"
        }

        if (!croppedRoi.isRecycled) {
            croppedRoi.recycle()
        }

        // 6. Stage 4: Spatial Line Grouping, HUD Filtering & Empty Checks
        if (lines.isEmpty()) {
            consecutiveEmptyOcrPasses++
            if (consecutiveEmptyOcrPasses >= 2) {
                seamlessOverlayRenderer?.clear()
                lastDisplayedEnglishText = ""
                candidateEnglishText = ""
            }
            return
        }

        val mergedEnglish = lines.joinToString(" ") { it.trim() }
        if (mergedEnglish.isBlank() ||
            com.zlatan.gamelens.ocr.OcrTextCleaner.isNoiseOrPrompt(mergedEnglish) ||
            com.zlatan.gamelens.ocr.OcrTextCleaner.isGarbledDocumentNoise(mergedEnglish)
        ) {
            consecutiveEmptyOcrPasses++
            if (consecutiveEmptyOcrPasses >= 2) {
                seamlessOverlayRenderer?.clear()
                lastDisplayedEnglishText = ""
                candidateEnglishText = ""
            }
            return
        }

        // Subtitle detected: reset empty counter
        consecutiveEmptyOcrPasses = 0

        // Jitter Gate: skip if >= 88% similar to currently displayed text (0ms, zero flicker)
        if (lastDisplayedEnglishText.isNotEmpty() && com.zlatan.gamelens.ocr.OcrTextCleaner.isSimilar(mergedEnglish, lastDisplayedEnglishText, 0.88f)) {
            return
        }

        // 7. Normalization, Patch Matching (0ms snap) and 1-Frame Stability Confirmation
        val promptNormalized = translationManager.normalizeControllerPrompts(mergedEnglish)
        val patchMatch = PatchManager.lookup(promptNormalized)

        if (patchMatch == null) {
            // Non-patch OCR string: require 1-frame stability confirmation (2 consecutive captures within 350ms with >= 80% similarity)
            val nowMs = android.os.SystemClock.uptimeMillis()
            val isCandidateMatch = candidateEnglishText.isNotEmpty() &&
                    (nowMs - candidateTimestamp <= 350L) &&
                    com.zlatan.gamelens.ocr.OcrTextCleaner.isSimilar(promptNormalized, candidateEnglishText, 0.80f)

            if (!isCandidateMatch) {
                // First frame: store candidate and wait for 2nd frame confirmation
                candidateEnglishText = promptNormalized
                candidateTimestamp = nowMs
                return
            }
            // 2nd frame confirmed within 350ms! Clear candidate and proceed to translate
            candidateEnglishText = ""
        } else {
            // Patch matched: instant 0ms snap translation!
            candidateEnglishText = ""
        }

        // Final guard against orientation flip or foreground switch right before translation
        val (finalW, finalH, _) = getRealScreenDimensions()
        if (finalH > finalW || com.zlatan.gamelens.MainActivity.isAppInForeground) {
            return
        }

        val arabicTranslation = if (patchMatch != null) {
            patchMatch
        } else {
            withContext(Dispatchers.IO) {
                translationManager.translate(promptNormalized)
            }
        }

        if (arabicTranslation.isBlank() || arabicTranslation.trim().equals(promptNormalized.trim(), ignoreCase = true) || !ARABIC_REGEX.containsMatchIn(arabicTranslation)) {
            return
        }

        lastDisplayedEnglishText = mergedEnglish

        // 8. Render onto the solid opaque bar with persisted font size and dynamic reading speed duration
        seamlessOverlayRenderer?.renderSubtitle(arabicTranslation, promptNormalized)
        pulseHeartbeat()
        DiagnosticLogger.log("Precision OCR [$filterSource]: \"$promptNormalized\" -> \"$arabicTranslation\"")
        Log.i(TAG, "Precision OCR [$filterSource]: \"$promptNormalized\" -> \"$arabicTranslation\"")
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
        if (mediaProjection == null || !isProjectionValid) {
            showToast("إذن التقاط الشاشة غير نشط، جاري التجديد...")
            requestProjectionReacquisition(autoStartAudio = false)
            return
        }
        showToast("جاري مسح وترجمة القوائم (Zero Self-Read)...")
        serviceScope.launch {
            try {
                val captureMgr = screenCaptureManager ?: run {
                    showToast("خدمة التقاط الشاشة غير متصلة")
                    return@launch
                }

                // Clean frame capture: hide overlay synchronously, wait 35ms, acquire clean frame, restore visibility
                val fullBitmap = captureFrameForOcr()

                if (fullBitmap == null) {
                    showToast("تعذر التقاط إطار الشاشة، يرجى المحاولة ثانية")
                    return@launch
                }

                val bubbleBounds = getBubbleBounds()

                // 5. Restrict OCR strictly to Calibrated Reading Box if configured
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val calRect = com.zlatan.gamelens.overlay.CalibrationOverlay.getCalibratedRect(prefs)

                val cropX = (calRect.left * fullBitmap.width).toInt().coerceIn(0, fullBitmap.width - 1)
                val cropY = (calRect.top * fullBitmap.height).toInt().coerceIn(0, fullBitmap.height - 1)
                val cropW = (calRect.width() * fullBitmap.width).toInt().coerceIn(1, fullBitmap.width - cropX)
                val cropH = (calRect.height() * fullBitmap.height).toInt().coerceIn(1, fullBitmap.height - cropY)

                val isCropped = (cropW < fullBitmap.width || cropH < fullBitmap.height)
                val ocrBitmap = if (isCropped) {
                    Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                } else {
                    fullBitmap
                }

                val (screenW, screenH, _) = getRealScreenDimensions()

                // ML Kit OCR with confidence >= 0.78 & English token linguistic filter (>= 60%)
                val detectedBlocks = ocrEngine.recognizeText(
                    bitmap = ocrBitmap,
                    excludedBounds = bubbleBounds,
                    cropLeft = if (isCropped) cropX else 0,
                    cropTop = if (isCropped) cropY else 0,
                    scaleFactor = 1.0f,
                    screenWidth = screenW,
                    screenHeight = screenH
                )
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
                seamlessOverlayRenderer?.renderTranslations(
                    blocks = arabicBlocks,
                    screenW = screenW,
                    screenH = screenH,
                    bitmapW = fullBitmap.width,
                    bitmapH = fullBitmap.height
                )
                pulseHeartbeat()

                updateBubbleVisualState()
                showToast("تم تثبيت ترجمة القائمة (انقر نقرة واحدة للإخفاء)")
            } catch (e: Exception) {
                Log.e(TAG, "Zero-Self-Read scan error: ${e.message}", e)
                seamlessOverlayRenderer?.setVisibility(true)
                showToast("خطأ أثناء مسح القائمة: ${e.message}")
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
            val hasPinned = seamlessOverlayRenderer?.hasPinnedMenuCards() == true

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

        calibrationOverlay?.dismiss()
        calibrationOverlay = null

        gameAudioTranslator?.stop()
        gameAudioTranslator = null

        screenCaptureManager?.release()
        screenCaptureManager = null

        ocrEngine.close()
        translationManager.close()
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

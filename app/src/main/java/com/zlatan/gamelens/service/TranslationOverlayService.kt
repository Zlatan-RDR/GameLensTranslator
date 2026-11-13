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
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
    private var isRoiVisible = true

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()

        // Initialize Database, Cache, Glossary and Translation Managers
        cacheManager = TranslationCacheManager(this)
        glossaryManager = GlossaryManager(this)
        val config = loadTranslationConfigFromPreferences()
        translationManager = TranslationManager(this, cacheManager, glossaryManager, config)

        // Initialize Phase 4 Seamless Overlay Renderer with Touch Pass-Through
        seamlessOverlayRenderer = SeamlessOverlayRenderer(this)
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
                startForegroundWithNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                // Refresh config on service start
                translationManager.updateConfig(loadTranslationConfigFromPreferences())

                setupFloatingBubble()
                setupRoiFrame()

                if (resultCode != 0 && resultData != null) {
                    initMediaProjectionAndCapture(resultCode, resultData)
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
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GameLens Translator Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "قناة إشعارات خدمة ترجمة الألعاب الفورية"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun initMediaProjectionAndCapture(resultCode: Int, resultData: Intent) {
        val proj = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            Log.e(TAG, "Unable to obtain MediaProjection from token")
            return
        }
        mediaProjection = proj

        val metrics = resources.displayMetrics
        screenCaptureManager = ScreenCaptureManager(
            context = this,
            mediaProjection = proj,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            screenDensityDpi = metrics.densityDpi
        )

        startCaptureLoop()
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

        val currentRoi = getCurrentRoiRect() ?: return
        val captureMgr = screenCaptureManager ?: return

        // 1. Anti-Feedback Loop: temporarily hide translation overlay container right before frame capture
        seamlessOverlayRenderer?.setVisibility(false)
        delay(ANTI_FEEDBACK_VSYNC_DELAY_MS)

        // 2. Acquire raw full-screen game frame
        val fullBitmap: Bitmap? = captureMgr.acquireLatestBitmap()

        // 3. Restore translation overlay visibility immediately after frame acquisition
        seamlessOverlayRenderer?.setVisibility(true)

        if (fullBitmap == null) return

        // 4. Crop strictly to active user-selected ROI
        val croppedBitmap: Bitmap? = frameDiffEngine.cropToRoi(fullBitmap, currentRoi)
        if (croppedBitmap == null) {
            fullBitmap.recycle()
            return
        }

        // 5. Fast Frame Diffing: skip unchanged frames to save CPU and battery
        val hasChanged = frameDiffEngine.hasFrameChanged(croppedBitmap, currentRoi)
        if (!hasChanged) {
            croppedBitmap.recycle()
            fullBitmap.recycle()
            return
        }

        // 6. On-Device ML Kit OCR Recognition
        val detectedBlocks = ocrEngine.recognizeText(croppedBitmap, currentRoi)
        croppedBitmap.recycle()

        // 7. Auto-cleanup: If text in ROI has disappeared or changed to empty, clear overlay
        if (detectedBlocks.isEmpty()) {
            latestDetectedBlocks = emptyList()
            seamlessOverlayRenderer?.clear()
            fullBitmap.recycle()
            return
        }

        // 8. Asynchronous Translation through Glossary, Cache & Hybrid Engine on Dispatchers.IO
        withContext(Dispatchers.IO) {
            for (block in detectedBlocks) {
                val translated = translationManager.translate(block.text)
                block.translatedText = translated
            }
        }

        latestDetectedBlocks = detectedBlocks

        // 9. Seamless Overlay Rendering: Edge color sampling + Native RTL Arabic TextView placement
        seamlessOverlayRenderer?.renderTranslations(detectedBlocks, fullBitmap)
        fullBitmap.recycle()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        if (bubbleView != null) return

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
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupRoiFrame() {
        if (roiView != null) return

        roiView = LayoutInflater.from(this).inflate(R.layout.overlay_roi_frame, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val initialWidth = (displayMetrics.widthPixels * 0.75f).toInt()
        val initialHeight = (displayMetrics.heightPixels * 0.35f).toInt()

        roiParams = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - initialWidth) / 2
            y = (displayMetrics.heightPixels - initialHeight) / 3
        }

        val header = roiView?.findViewById<View>(R.id.roiHeader)
        val btnHide = roiView?.findViewById<ImageView>(R.id.btnHideRoi)
        val resizeHandle = roiView?.findViewById<ImageView>(R.id.ivResizeHandle)

        // Dragging ROI Frame via header
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

        // Resizing ROI Frame via bottom-right handle
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

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        serviceScope.cancel()

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

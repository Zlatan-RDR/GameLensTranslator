package com.zlatan.gamelens.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.R
import com.zlatan.gamelens.audio.AsrDownloadState
import com.zlatan.gamelens.audio.AsrModelDownloader
import com.zlatan.gamelens.diag.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Resumable Foreground Service for downloading the Sherpa-ONNX streaming ASR model (~122 MB).
 * Maintains a persistent system progress notification so Android and HyperOS never abort the socket
 * during background transitions or screen locks.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        const val ACTION_START_DOWNLOAD = "com.zlatan.gamelens.action.START_DOWNLOAD"
        const val ACTION_STOP_DOWNLOAD = "com.zlatan.gamelens.action.STOP_DOWNLOAD"

        private const val NOTIFICATION_CHANNEL_ID = "gamelens_model_download_channel"
        private const val NOTIFICATION_ID = 2002

        private val _downloadStateFlow = MutableStateFlow<AsrDownloadState>(AsrDownloadState.Idle)
        val downloadStateFlow: StateFlow<AsrDownloadState> = _downloadStateFlow.asStateFlow()

        fun startOrResume(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_STOP_DOWNLOAD
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var notificationManager: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                startForegroundWithNotification(0, 0, 122 * 1024 * 1024L)
                beginDownloadJob()
            }
            ACTION_STOP_DOWNLOAD -> {
                stopDownload()
            }
            else -> {
                if (downloadJob == null || downloadJob?.isCompleted == true) {
                    startForegroundWithNotification(0, 0, 122 * 1024 * 1024L)
                    beginDownloadJob()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginDownloadJob() {
        if (downloadJob?.isActive == true) {
            Log.d(TAG, "Download job already running in foreground")
            return
        }

        downloadJob = serviceScope.launch {
            DiagnosticLogger.log("ModelDownloadService: Starting resumable download in foreground...")
            val success = AsrModelDownloader.downloadAndExtract(this@ModelDownloadService) { state ->
                _downloadStateFlow.value = state
                when (state) {
                    is AsrDownloadState.Downloading -> {
                        updateNotificationProgress(state.progressPercent, state.bytesDownloaded, state.totalBytes)
                    }
                    is AsrDownloadState.Extracting -> {
                        updateNotificationExtracting()
                    }
                    is AsrDownloadState.Completed -> {
                        updateNotificationCompleted()
                    }
                    is AsrDownloadState.Error -> {
                        updateNotificationError(state.message)
                    }
                    AsrDownloadState.Idle -> {}
                }
            }

            if (success) {
                DiagnosticLogger.log("ModelDownloadService: Model installed and verified successfully")
            } else {
                DiagnosticLogger.log("ModelDownloadService: Download stopped or failed (resumable)")
            }

            // Stop foreground service cleanly once done
            stopSelf()
        }
    }

    private fun stopDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadStateFlow.value = AsrDownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Game Voice Model Downloader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تحميل وتثبيت نموذج تفريغ صوت اللعبة أوفلاين"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun getPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(title: String, content: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(getPendingIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (max > 0 || indeterminate) {
            builder.setProgress(max, progress, indeterminate)
        }

        return builder.build()
    }

    private fun startForegroundWithNotification(percent: Int, downloadedBytes: Long, totalBytes: Long) {
        val downloadedMb = downloadedBytes / (1024.0 * 1024.0)
        val totalMb = if (totalBytes > 0) totalBytes / (1024.0 * 1024.0) else 122.0
        val text = String.format(java.util.Locale.US, "Downloading Game Voice Model: %d%% (%.1f / %.1f MB)", percent, downloadedMb, totalMb)

        val notification = buildNotification(
            title = "GameLens Voice Model",
            content = text,
            progress = percent,
            max = 100,
            indeterminate = false
        )

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    private fun updateNotificationProgress(percent: Int, downloadedBytes: Long, totalBytes: Long) {
        val downloadedMb = downloadedBytes / (1024.0 * 1024.0)
        val totalMb = if (totalBytes > 0) totalBytes / (1024.0 * 1024.0) else 122.0
        val text = String.format(java.util.Locale.US, "Downloading Game Voice Model: %d%% (%.1f / %.1f MB)", percent, downloadedMb, totalMb)

        val notification = buildNotification(
            title = "GameLens Voice Model",
            content = text,
            progress = percent,
            max = 100,
            indeterminate = false
        )
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationExtracting() {
        val notification = buildNotification(
            title = "GameLens Voice Model",
            content = "Extracting Zipformer int8 weights (~22 MB on disk)...",
            progress = 0,
            max = 0,
            indeterminate = true
        )
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationCompleted() {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("GameLens Voice Model")
            .setContentText("Game Voice Model ready on disk (~22 MB)")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(getPendingIntent())
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }

    private fun updateNotificationError(message: String) {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("GameLens Voice Model")
            .setContentText("Download paused: $message (Tap to resume)")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(getPendingIntent())
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        serviceScope.cancel()
    }
}

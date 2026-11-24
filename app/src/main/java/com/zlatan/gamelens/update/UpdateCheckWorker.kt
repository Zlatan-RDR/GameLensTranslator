package com.zlatan.gamelens.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zlatan.gamelens.MainActivity
import com.zlatan.gamelens.R
import com.zlatan.gamelens.diag.DiagnosticLogger
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 2001
        private const val THROTTLE_WINDOW_MS = 12 * 60 * 60 * 1000L // 12 hours

        fun schedulePeriodicCheck(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "gamelens_update_check",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                Log.d(TAG, "Scheduled 24h background update check work")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to schedule periodic update check: ${t.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheckMs = prefs.getLong(AppUpdateManager.PREFS_KEY_LAST_CHECK_MS, 0L)

        // Throttle check (max once per 12 hours unless manual)
        if (now - lastCheckMs < THROTTLE_WINDOW_MS) {
            Log.d(TAG, "Skipping update check: throttled (last check ${(now - lastCheckMs) / 60000}m ago)")
            return Result.success()
        }

        prefs.edit().putLong(AppUpdateManager.PREFS_KEY_LAST_CHECK_MS, now).apply()

        return when (val result = AppUpdateManager.checkForUpdate()) {
            is UpdateCheckResult.UpdateAvailable -> {
                val lastNotifiedTag = prefs.getString(AppUpdateManager.PREFS_KEY_LAST_NOTIFIED_TAG, "")
                if (lastNotifiedTag == result.release.tagName) {
                    Log.d(TAG, "Update ${result.release.tagName} already notified to user")
                    Result.success()
                } else {
                    notifyUpdateAvailable(result.release)
                    prefs.edit().putString(AppUpdateManager.PREFS_KEY_LAST_NOTIFIED_TAG, result.release.tagName).apply()
                    Result.success()
                }
            }
            is UpdateCheckResult.UpToDate -> {
                Log.d(TAG, "App is up to date")
                Result.success()
            }
            is UpdateCheckResult.Error -> {
                Log.w(TAG, "Background update check encountered error: ${result.message}")
                Result.retry()
            }
        }
    }

    private fun notifyUpdateAvailable(release: ReleaseInfo) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "تحديثات التطبيق / App Updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "إشعارات توفر إصدارات جديدة من GameLens Translator"
                    enableLights(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    Log.w(TAG, "Cannot post update notification: POST_NOTIFICATIONS not granted")
                    return
                }
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AppUpdateManager.EXTRA_OPEN_UPDATE_DIALOG, true)
                putExtra("release_tag", release.tagName)
                putExtra("release_body", release.releaseNotes)
                putExtra("release_url", release.downloadUrl)
                putExtra("release_size", release.apkSizeBytes)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("تحديث جديد متوفر: ${release.tagName}")
                .setContentText("انقر لتثبيت أحدث إصدار من GameLens Translator مباشرة")
                .setStyle(NotificationCompat.BigTextStyle().bigText(release.releaseNotes.take(200)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            DiagnosticLogger.log("Update: Posted update notification for ${release.tagName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post update notification: ${t.message}", t)
        }
    }
}

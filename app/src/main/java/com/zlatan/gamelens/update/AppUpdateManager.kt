package com.zlatan.gamelens.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.zlatan.gamelens.BuildConfig
import com.zlatan.gamelens.diag.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/Zlatan-RDR/GameLensTranslator/releases/latest"

    const val EXTRA_OPEN_UPDATE_DIALOG = "extra_open_update_dialog"
    const val PREFS_KEY_LAST_NOTIFIED_TAG = "key_update_last_notified_tag"
    const val PREFS_KEY_LAST_CHECK_MS = "key_update_last_check_ms"

    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Checks the GitHub Releases API for a newer version than the currently running build.
     */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GameLens-Android")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val bodyString = response.body?.use { it.string() }

            val currentVersion = try {
                BuildConfig.VERSION_NAME
            } catch (_: Throwable) {
                "1.0.0"
            }

            parseReleaseResponse(code, bodyString, currentVersion)
        } catch (t: Throwable) {
            val err = "Update check failed: ${t.message}"
            Log.e(TAG, err, t)
            DiagnosticLogger.log("Update check error: ${t.message}")
            UpdateCheckResult.Error(err)
        }
    }

    /**
     * Parses the GitHub Releases API response, treating HTTP 404 gracefully as UpToDate.
     */
    fun parseReleaseResponse(
        code: Int,
        bodyString: String?,
        currentVersion: String = "1.0.0"
    ): UpdateCheckResult {
        if (code == 404) {
            Log.i(TAG, "No GitHub releases published yet (HTTP 404). Treating as up to date.")
            DiagnosticLogger.log("Update check: No releases published yet (HTTP 404)")
            return UpdateCheckResult.UpToDate
        }

        if (code !in 200..299) {
            val err = "GitHub API returned HTTP $code"
            Log.w(TAG, err)
            return UpdateCheckResult.Error(err)
        }

        if (bodyString.isNullOrBlank()) {
            return UpdateCheckResult.Error("Empty response body")
        }

        return try {
            val json = JSONObject(bodyString)
            val tagName = json.optString("tag_name", "").trim()
            val releaseNotes = json.optString("body", "").trim()
            val assetsArray = json.optJSONArray("assets")

            if (tagName.isEmpty() || assetsArray == null || assetsArray.length() == 0) {
                return UpdateCheckResult.Error("No release assets found in release $tagName")
            }

            // Find first .apk asset
            var apkDownloadUrl = ""
            var apkSize = 0L
            for (i in 0 until assetsArray.length()) {
                val assetObj = assetsArray.getJSONObject(i)
                val assetName = assetObj.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkDownloadUrl = assetObj.optString("browser_download_url", "")
                    apkSize = assetObj.optLong("size", 0L)
                    break
                }
            }

            if (apkDownloadUrl.isEmpty()) {
                return UpdateCheckResult.Error("No APK asset attached to release $tagName")
            }

            // Validate download URL host security
            val parsedUrl = URL(apkDownloadUrl)
            if (parsedUrl.protocol != "https" || !ALLOWED_DOWNLOAD_HOSTS.contains(parsedUrl.host.lowercase())) {
                val err = "Untrusted APK download host: ${parsedUrl.host}"
                Log.e(TAG, err)
                DiagnosticLogger.log("Update: Security validation failed for URL: $apkDownloadUrl")
                return UpdateCheckResult.Error(err)
            }

            val isNewer = isNewerVersion(tagName, currentVersion)
            Log.d(TAG, "Update check: local=$currentVersion, remote=$tagName, isNewer=$isNewer")
            DiagnosticLogger.log("Update check: local=$currentVersion, remote=$tagName, isNewer=$isNewer")

            if (isNewer) {
                UpdateCheckResult.UpdateAvailable(
                    ReleaseInfo(
                        tagName = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = apkDownloadUrl,
                        apkSizeBytes = apkSize
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (t: Throwable) {
            UpdateCheckResult.Error("Failed to parse release: ${t.message}")
        }
    }

    /**
     * Strict semantic version comparison:
     * Returns true if remote is strictly greater than local.
     */
    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val remoteClean = remoteTag.trim().trimStart('v', 'V')
        val localClean = localVersion.trim().trimStart('v', 'V')

        val remoteParts = remoteClean.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val localParts = localClean.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    /**
     * Cleans up any previously downloaded update APKs from the cache directory.
     */
    fun cleanStaleUpdates(context: Context) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (updateDir.exists()) {
                updateDir.listFiles()?.forEach { file ->
                    try {
                        file.delete()
                    } catch (_: Exception) {}
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error cleaning stale updates: ${t.message}")
        }
    }

    /**
     * Streams the APK file to cacheDir/updates/gamelens_update.apk with progress reporting.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val destinationFile = File(updateDir, "gamelens_update.apk")
            if (destinationFile.exists()) destinationFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "GameLens-Android")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed with HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        onProgress(totalRead, contentLength)
                    }
                    output.flush()
                }
            }

            DiagnosticLogger.log("Update: APK downloaded (${destinationFile.length()} bytes)")
            destinationFile
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during APK download: ${t.message}", t)
            DiagnosticLogger.log("Update download error: ${t.message}")
            null
        }
    }

    /**
     * Launches Android's PackageInstaller via FileProvider.
     * If Android 8.0+ unknown sources permission is missing, prompts Settings.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    DiagnosticLogger.log("Update: Prompted ACTION_MANAGE_UNKNOWN_APP_SOURCES")
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            DiagnosticLogger.log("Update: Dispatched ACTION_VIEW install intent")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch package installer: ${t.message}", t)
            DiagnosticLogger.log("Update install launch failed: ${t.message}")
        }
    }
}

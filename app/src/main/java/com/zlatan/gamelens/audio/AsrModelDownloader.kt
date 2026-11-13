package com.zlatan.gamelens.audio

import android.content.Context
import android.util.Log
import com.zlatan.gamelens.diag.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class AsrDownloadState {
    object Idle : AsrDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : AsrDownloadState()
    object Extracting : AsrDownloadState()
    object Completed : AsrDownloadState()
    data class Error(val message: String) : AsrDownloadState()
}

/**
 * Downloads and extracts the official Sherpa-ONNX streaming Zipformer English ASR model (~20MB)
 * into internal app storage for zero-internet, offline game voice translation.
 */
object AsrModelDownloader {

    private const val TAG = "AsrModelDownloader"
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadAndExtract(
        context: Context,
        onStateChange: (AsrDownloadState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destDir = GameAudioTranslator.getAsrModelDir(context)
        val tempArchive = File(context.cacheDir, "zipformer-model.tar.bz2")

        try {
            DiagnosticLogger.log("ASR Downloader: Starting model fetch (~22MB)...")
            onStateChange(AsrDownloadState.Downloading(0, 0L, -1L))

            val request = Request.Builder()
                .url(MODEL_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("HTTP response failed: code=${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body from model server")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            tempArchive.parentFile?.mkdirs()
            FileOutputStream(tempArchive).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onStateChange(
                                    AsrDownloadState.Downloading(
                                        percent,
                                        downloadedBytes,
                                        totalBytes
                                    )
                                )
                            }
                        }
                    }
                }
            }

            DiagnosticLogger.log("ASR Downloader: Download finished ($downloadedBytes bytes). Extracting...")
            onStateChange(AsrDownloadState.Extracting)

            extractTarBz2(tempArchive, destDir)

            // Cleanup downloaded archive
            tempArchive.delete()

            val isVerified = GameAudioTranslator.isAsrModelInstalled(context)
            if (isVerified) {
                DiagnosticLogger.log("ASR Downloader: All model files verified on disk")
                onStateChange(AsrDownloadState.Completed)
                return@withContext true
            } else {
                throw IOException("Verification failed: Required ONNX weights or tokens.txt missing after unpack")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading or unpacking ASR model: ${e.message}", e)
            DiagnosticLogger.log("ASR Downloader Error: ${e.message}")
            tempArchive.delete()
            onStateChange(AsrDownloadState.Error(e.message ?: "Unknown download error"))
            return@withContext false
        }
    }

    private fun extractTarBz2(archiveFile: File, outputDir: File) {
        outputDir.mkdirs()
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            val entryName = entry.name.substringAfterLast("/")
                            if (!entry.isDirectory && entryName.isNotBlank()) {
                                // Extract weights and tokens into destination
                                val targetFile = File(outputDir, entryName)
                                FileOutputStream(targetFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                                Log.d(TAG, "Extracted: ${targetFile.name} (${targetFile.length()} bytes)")

                                // Normalize canonical aliases for instant lookup
                                when {
                                    entryName.contains("encoder", ignoreCase = true) -> {
                                        val canonical = File(outputDir, "encoder.int8.onnx")
                                        if (canonical.canonicalPath != targetFile.canonicalPath) {
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                    }
                                    entryName.contains("decoder", ignoreCase = true) -> {
                                        val canonical = File(outputDir, "decoder.int8.onnx")
                                        if (canonical.canonicalPath != targetFile.canonicalPath) {
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                    }
                                    entryName.contains("joiner", ignoreCase = true) -> {
                                        val canonical = File(outputDir, "joiner.int8.onnx")
                                        if (canonical.canonicalPath != targetFile.canonicalPath) {
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                    }
                                    entryName.equals("tokens.txt", ignoreCase = true) -> {
                                        val canonical = File(outputDir, "tokens.txt")
                                        if (canonical.canonicalPath != targetFile.canonicalPath) {
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                    }
                                }
                            }
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }
        }
    }
}

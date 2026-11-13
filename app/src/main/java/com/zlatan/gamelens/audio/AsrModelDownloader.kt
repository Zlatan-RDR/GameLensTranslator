package com.zlatan.gamelens.audio

import android.content.Context
import android.util.Log
import com.zlatan.gamelens.diag.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.TimeUnit

sealed class AsrDownloadState {
    object Idle : AsrDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : AsrDownloadState()
    object Extracting : AsrDownloadState()
    object Completed : AsrDownloadState()
    data class Error(val message: String, val canResume: Boolean = true) : AsrDownloadState()
}

/**
 * Downloads and extracts the official Sherpa-ONNX streaming Zipformer English ASR model (~122 MB compressed archive).
 * Supports HTTP Range resume into `asr_model.tar.bz2.part` so connection interruptions or app restarts
 * never lose downloaded bytes.
 * Extracts ONLY the 4 required int8 files (~22 MB total) and deletes the archive immediately.
 */
object AsrModelDownloader {

    private const val TAG = "AsrModelDownloader"
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"
    private const val PART_FILE_NAME = "asr_model.tar.bz2.part"
    private const val EXPECTED_ARCHIVE_SIZE_ESTIMATE = 122L * 1024L * 1024L // ~122 MB

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun getPartFile(context: Context): File {
        return File(context.filesDir, PART_FILE_NAME)
    }

    fun hasResumablePart(context: Context): Boolean {
        val part = getPartFile(context)
        return part.exists() && part.length() > 0 && !GameAudioTranslator.isAsrModelInstalled(context)
    }

    fun getPartFileSizeBytes(context: Context): Long {
        val part = getPartFile(context)
        return if (part.exists()) part.length() else 0L
    }

    suspend fun downloadAndExtract(
        context: Context,
        onStateChange: (AsrDownloadState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destDir = GameAudioTranslator.getAsrModelDir(context)
        val partFile = getPartFile(context)

        // If model already fully installed, mark complete
        if (GameAudioTranslator.isAsrModelInstalled(context)) {
            DiagnosticLogger.log("ASR Downloader: Model already installed and verified on disk")
            onStateChange(AsrDownloadState.Completed)
            return@withContext true
        }

        try {
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            DiagnosticLogger.log("ASR Downloader: Starting fetch (existingBytes=$existingBytes, target ~122 MB)...")

            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                DiagnosticLogger.log("ASR Downloader: Requesting Range bytes=$existingBytes-")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseCode = response.code

            val appendMode: Boolean
            var totalBytes: Long
            var downloadedBytes: Long

            when (responseCode) {
                206 -> {
                    // Partial Content accepted by server
                    appendMode = true
                    downloadedBytes = existingBytes
                    val remaining = response.body?.contentLength() ?: -1L
                    totalBytes = if (remaining > 0) existingBytes + remaining else EXPECTED_ARCHIVE_SIZE_ESTIMATE
                    DiagnosticLogger.log("ASR Downloader: Resuming from byte $existingBytes (remaining: $remaining)")
                }
                200 -> {
                    // Server responded with full content
                    appendMode = false
                    downloadedBytes = 0L
                    totalBytes = response.body?.contentLength() ?: EXPECTED_ARCHIVE_SIZE_ESTIMATE
                    DiagnosticLogger.log("ASR Downloader: Starting fresh download (total: $totalBytes)")
                }
                416 -> {
                    // Range Not Satisfiable: partFile might already be complete
                    response.close()
                    if (existingBytes > 20_000_000L) {
                        DiagnosticLogger.log("ASR Downloader: Range 416 received with $existingBytes bytes. Attempting extraction...")
                        onStateChange(AsrDownloadState.Extracting)
                        val extracted = extractTarBz2(partFile, destDir)
                        if (extracted && GameAudioTranslator.isAsrModelInstalled(context)) {
                            partFile.delete()
                            onStateChange(AsrDownloadState.Completed)
                            return@withContext true
                        }
                    }
                    // If extraction failed or incomplete, reset part file and restart
                    partFile.delete()
                    existingBytes = 0L
                    return@withContext downloadAndExtract(context, onStateChange)
                }
                else -> {
                    response.close()
                    throw IOException("HTTP response failed with code $responseCode")
                }
            }

            val body = response.body ?: throw IOException("Empty response body from server")

            // Write incoming stream into part file
            FileOutputStream(partFile, appendMode).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    var lastPercent = -1

                    val initialPercent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                    onStateChange(AsrDownloadState.Downloading(initialPercent, downloadedBytes, totalBytes))

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onStateChange(AsrDownloadState.Downloading(percent, downloadedBytes, totalBytes))
                            }
                        }
                    }
                }
            }

            DiagnosticLogger.log("ASR Downloader: Download stream complete ($downloadedBytes bytes). Extracting...")
            onStateChange(AsrDownloadState.Extracting)

            // Extract ONLY the 4 int8 ONNX files and tokens
            val extracted = extractTarBz2(partFile, destDir)
            if (!extracted) {
                throw IOException("Archive extraction did not unpack all required int8 ONNX files")
            }

            // Immediately delete archive part to conserve flash storage
            if (partFile.exists()) {
                partFile.delete()
                DiagnosticLogger.log("ASR Downloader: Deleted temporary archive part to save storage")
            }

            val isVerified = GameAudioTranslator.isAsrModelInstalled(context)
            if (isVerified) {
                DiagnosticLogger.log("ASR Downloader: All 4 int8 files verified on disk (~22 MB)")
                onStateChange(AsrDownloadState.Completed)
                return@withContext true
            } else {
                throw IOException("Verification failed: Required Zipformer int8 weights missing after unpack")
            }

        } catch (e: Exception) {
            val isSocket = e is SocketException || e.cause is SocketException
            Log.e(TAG, "ASR Download/Unpack error: ${e.message} (SocketException=$isSocket)", e)
            DiagnosticLogger.log("ASR Downloader error: ${e.message} (Resumable from ${partFile.length()} bytes)")
            // Keep partFile intact for HTTP Range resume!
            onStateChange(AsrDownloadState.Error(e.message ?: "Unknown download interruption", canResume = true))
            return@withContext false
        }
    }

    /**
     * Streams through the bzip2-compressed tar archive and extracts ONLY the 4 essential int8 model files:
     * 1. encoder-epoch-99-avg-1.int8.onnx (also creates canonical alias encoder.int8.onnx)
     * 2. decoder-epoch-99-avg-1.int8.onnx (also creates canonical alias decoder.int8.onnx)
     * 3. joiner-epoch-99-avg-1.int8.onnx (also creates canonical alias joiner.int8.onnx)
     * 4. tokens.txt
     */
    private fun extractTarBz2(archiveFile: File, outputDir: File): Boolean {
        if (!archiveFile.exists() || archiveFile.length() == 0L) {
            Log.e(TAG, "Archive file does not exist or is empty: ${archiveFile.absolutePath}")
            return false
        }

        outputDir.mkdirs()

        try {
            FileInputStream(archiveFile).use { fis ->
                BufferedInputStream(fis, 65536).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextTarEntry
                            while (entry != null) {
                                val entryName = entry.name.substringAfterLast("/")
                                val isTarget = entryName == "encoder-epoch-99-avg-1.int8.onnx" ||
                                        entryName == "decoder-epoch-99-avg-1.int8.onnx" ||
                                        entryName == "joiner-epoch-99-avg-1.int8.onnx" ||
                                        entryName == "tokens.txt"

                                if (!entry.isDirectory && isTarget) {
                                    val targetFile = File(outputDir, entryName)
                                    FileOutputStream(targetFile).use { fos ->
                                        tarIn.copyTo(fos)
                                    }
                                    Log.d(TAG, "Extracted target: ${targetFile.name} (${targetFile.length()} bytes)")

                                    // Create canonical aliases for instant zero-overhead lookups
                                    when (entryName) {
                                        "encoder-epoch-99-avg-1.int8.onnx" -> {
                                            val canonical = File(outputDir, "encoder.int8.onnx")
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                        "decoder-epoch-99-avg-1.int8.onnx" -> {
                                            val canonical = File(outputDir, "decoder.int8.onnx")
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                        "joiner-epoch-99-avg-1.int8.onnx" -> {
                                            val canonical = File(outputDir, "joiner.int8.onnx")
                                            targetFile.copyTo(canonical, overwrite = true)
                                        }
                                    }
                                }
                                entry = tarIn.nextTarEntry
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting tar.bz2 archive: ${e.message}", e)
            DiagnosticLogger.log("ASR Extract error: ${e.message}")
            return false
        }
    }
}

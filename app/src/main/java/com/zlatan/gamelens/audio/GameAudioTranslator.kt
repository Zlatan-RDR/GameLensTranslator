package com.zlatan.gamelens.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.zlatan.gamelens.diag.DiagnosticLogger
import com.zlatan.gamelens.translation.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/**
 * Captures internal game audio via Android 10+ AudioPlaybackCaptureConfiguration
 * and transcribes English game dialog using Sherpa-ONNX streaming ASR,
 * translating phrases to Arabic for cinematic subtitles.
 */
class GameAudioTranslator(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val translationManager: TranslationManager,
    private val onSubtitleReady: (String) -> Unit
) {

    companion object {
        private const val TAG = "GameAudioTranslator"
        private const val SAMPLE_RATE = 16000
        private const val READ_BUFFER_SIZE = 2048
        private const val RMS_SILENCE_THRESHOLD = 250.0
        private const val MAX_PHRASE_DURATION_MS = 3200L // Fast 3.2s phrase segmentation window

        fun getAsrModelDir(context: Context): File {
            val dir = File(context.filesDir, "asr-model")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        fun isAsrModelInstalled(context: Context): Boolean {
            val dir = getAsrModelDir(context)
            val hasEncoder = File(dir, "encoder.onnx").exists() || File(dir, "encoder.int8.onnx").exists() ||
                    File(dir, "encoder-epoch-99-avg-1.int8.onnx").exists() || File(dir, "encoder-epoch-99-avg-1.onnx").exists()
            val hasDecoder = File(dir, "decoder.onnx").exists() || File(dir, "decoder.int8.onnx").exists() ||
                    File(dir, "decoder-epoch-99-avg-1.int8.onnx").exists() || File(dir, "decoder-epoch-99-avg-1.onnx").exists()
            val hasJoiner = File(dir, "joiner.onnx").exists() || File(dir, "joiner.int8.onnx").exists() ||
                    File(dir, "joiner-epoch-99-avg-1.int8.onnx").exists() || File(dir, "joiner-epoch-99-avg-1.onnx").exists()
            val hasTokens = File(dir, "tokens.txt").exists()
            return hasEncoder && hasDecoder && hasJoiner && hasTokens
        }
    }

    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10 (API 29) or higher.")
            DiagnosticLogger.log("GameAudio: Requires Android 10+")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted.")
            DiagnosticLogger.log("GameAudio: RECORD_AUDIO permission missing")
            return
        }

        isRunning = true
        DiagnosticLogger.log("GameAudio: Initializing internal audio capture...")

        initSherpaRecognizer()
        initAudioCaptureAndLoop()
    }

    private fun initSherpaRecognizer() {
        val dir = getAsrModelDir(context)
        val encoder = File(dir, "encoder.int8.onnx").takeIf { it.exists() }
            ?: File(dir, "encoder-epoch-99-avg-1.int8.onnx").takeIf { it.exists() }
            ?: File(dir, "encoder.onnx").takeIf { it.exists() }
            ?: File(dir, "encoder-epoch-99-avg-1.onnx").takeIf { it.exists() }

        val decoder = File(dir, "decoder.int8.onnx").takeIf { it.exists() }
            ?: File(dir, "decoder-epoch-99-avg-1.int8.onnx").takeIf { it.exists() }
            ?: File(dir, "decoder.onnx").takeIf { it.exists() }
            ?: File(dir, "decoder-epoch-99-avg-1.onnx").takeIf { it.exists() }

        val joiner = File(dir, "joiner.int8.onnx").takeIf { it.exists() }
            ?: File(dir, "joiner-epoch-99-avg-1.int8.onnx").takeIf { it.exists() }
            ?: File(dir, "joiner.onnx").takeIf { it.exists() }
            ?: File(dir, "joiner-epoch-99-avg-1.onnx").takeIf { it.exists() }

        val tokens = File(dir, "tokens.txt").takeIf { it.exists() }

        if (encoder != null && decoder != null && joiner != null && tokens != null) {
            try {
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                )
                val config = OnlineRecognizerConfig(
                    modelConfig = modelConfig,
                    enableEndpoint = true,
                    decodingMethod = "greedy_search"
                )
                val rec = OnlineRecognizer(assetManager = null, config = config)
                recognizer = rec
                stream = rec.createStream()
                DiagnosticLogger.log("GameAudio: Sherpa-ONNX streaming ASR loaded successfully")
                Log.i(TAG, "Sherpa-ONNX streaming recognizer initialized with local model files")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Sherpa-ONNX: ${e.message}", e)
                DiagnosticLogger.log("GameAudio: Sherpa-ONNX init failed: ${e.message}")
            }
        } else {
            DiagnosticLogger.log("GameAudio: Model pack not in filesDir/asr-model/ (ASR pending pack)")
            Log.w(TAG, "Sherpa-ONNX model files not found in ${dir.absolutePath}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun initAudioCaptureAndLoop() {
        audioJob?.cancel()
        audioJob = audioScope.launch {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = (minBufferSize * 4).coerceAtLeast(4096)

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord = record

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    DiagnosticLogger.log("GameAudio: AudioRecord init failed")
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }

                record.startRecording()
                DiagnosticLogger.log("GameAudio: Playback capture active (16kHz PCM)")
                Log.i(TAG, "AudioRecord started recording game playback audio")

                val shortBuffer = ShortArray(READ_BUFFER_SIZE)
                var lastRecognizedPhrase = ""
                var speechFrames = 0
                var phraseStartTimestamp = 0L

                while (isActive && isRunning) {
                    val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts <= 0) continue

                    // Calculate RMS energy for VAD (Voice Activity Detection)
                    var sumSquare = 0.0
                    val floatSamples = FloatArray(readShorts)
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i]
                        floatSamples[i] = s / 32768.0f
                        sumSquare += (s * s).toDouble()
                    }
                    val rms = sqrt(sumSquare / readShorts)

                    val rec = recognizer
                    val st = stream

                    if (rms > RMS_SILENCE_THRESHOLD) {
                        speechFrames++
                        if (phraseStartTimestamp == 0L) {
                            phraseStartTimestamp = System.currentTimeMillis()
                        }

                        if (rec != null && st != null) {
                            st.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE)
                            while (rec.isReady(st)) {
                                rec.decode(st)
                            }
                            val text = rec.getResult(st).text.trim()
                            if (text.isNotBlank() && text != lastRecognizedPhrase) {
                                lastRecognizedPhrase = text
                            }

                            val phraseDuration = System.currentTimeMillis() - phraseStartTimestamp
                            val isEndpoint = rec.isEndpoint(st)
                            // Segment every 2.5s-3.5s at natural word boundary or on endpoint
                            val isTimeSegmentExceeded = phraseDuration >= MAX_PHRASE_DURATION_MS && lastRecognizedPhrase.length >= 8 && lastRecognizedPhrase.contains(" ")

                            if (isEndpoint || isTimeSegmentExceeded) {
                                if (lastRecognizedPhrase.isNotBlank()) {
                                    flushAndTranslate(lastRecognizedPhrase, phraseDuration)
                                }
                                rec.reset(st)
                                lastRecognizedPhrase = ""
                                speechFrames = 0
                                phraseStartTimestamp = 0L
                            }
                        }
                    } else {
                        // Silence detected
                        if (speechFrames > 0 && rec != null && st != null && lastRecognizedPhrase.isNotBlank()) {
                            val phraseDuration = if (phraseStartTimestamp > 0L) System.currentTimeMillis() - phraseStartTimestamp else 0L
                            flushAndTranslate(lastRecognizedPhrase, phraseDuration)
                            rec.reset(st)
                            lastRecognizedPhrase = ""
                            speechFrames = 0
                            phraseStartTimestamp = 0L
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in GameAudio capture loop: ${t.message}", t)
                DiagnosticLogger.log("GameAudio Error: ${t.message}")
            }
        }
    }

    private suspend fun flushAndTranslate(phrase: String, durationMs: Long) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return

        // Normalize ASR unpunctuated ALL CAPS to clean sentence case with terminal period
        val normalizedEn = normalizeAsrText(trimmed)
        DiagnosticLogger.log("ASR Voice ($durationMs ms): \"$normalizedEn\"")

        val arabicTranslation = translationManager.translate(normalizedEn)
        val sanitizedArabic = translationManager.deduplicateRepetitions(arabicTranslation)

        if (sanitizedArabic.isNotBlank() && !sanitizedArabic.equals(normalizedEn, ignoreCase = true)) {
            DiagnosticLogger.log("ASR Subtitle: \"$sanitizedArabic\"")
            onSubtitleReady(sanitizedArabic)
        }
    }

    private fun normalizeAsrText(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        val sentenceCase = lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (!sentenceCase.endsWith(".") && !sentenceCase.endsWith("?") && !sentenceCase.endsWith("!")) {
            "$sentenceCase."
        } else {
            sentenceCase
        }
    }

    fun stop() {
        isRunning = false
        audioJob?.cancel()
        audioJob = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            stream?.release()
        } catch (_: Exception) {}
        stream = null

        try {
            recognizer?.release()
        } catch (_: Exception) {}
        recognizer = null

        audioScope.cancel()
        DiagnosticLogger.log("GameAudio: Stopped and released")
    }
}

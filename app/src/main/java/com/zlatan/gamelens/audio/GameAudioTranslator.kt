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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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
import kotlin.math.max
import kotlin.math.min
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
    private val onSubtitleReady: (String) -> Unit,
    private val onStateChanged: (() -> Unit)? = null
) {

    val isCapturing: Boolean
        get() = isRunning && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    companion object {
        private const val TAG = "GameAudioTranslator"
        private const val SAMPLE_RATE = 16000
        private const val READ_BUFFER_SIZE = 2048

        fun getAsrModelDir(context: Context): File {
            val dir = File(context.filesDir, "asr-model")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        fun getSileroVadFile(context: Context): File {
            return File(getAsrModelDir(context), "silero_vad.onnx")
        }

        fun hasZipformerFiles(context: Context): Boolean {
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

        fun isAsrModelInstalled(context: Context): Boolean {
            val vadFile = getSileroVadFile(context)
            val hasVad = vadFile.exists() && vadFile.length() > 500_000L
            return hasZipformerFiles(context) && hasVad
        }
    }

    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var vad: Vad? = null
    private var pendingEnglishBuffer: String = ""
    private var lastSegmentEndTimestampMs: Long = 0L
    private var lastMatchedPatchTargetNormalized: String = ""
    private var lastMatchedPatchTimestampMs: Long = 0L

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
        val vadFile = getSileroVadFile(context).takeIf { it.exists() && it.length() > 500_000L }

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
                    enableEndpoint = false,
                    decodingMethod = "greedy_search"
                )
                val rec = OnlineRecognizer(assetManager = null, config = config)
                recognizer = rec
                stream = rec.createStream()
                DiagnosticLogger.log("GameAudio: Sherpa-ONNX streaming ASR loaded (enableEndpoint=false)")
                Log.i(TAG, "Sherpa-ONNX streaming recognizer initialized with local model files")

                if (vadFile != null) {
                    val sileroConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.44f,
                        minSilenceDuration = 0.28f,
                        minSpeechDuration = 0.18f,
                        windowSize = 512,
                        maxSpeechDuration = 8.0f
                    )
                    val vadConfig = VadModelConfig(
                        sileroVadModelConfig = sileroConfig,
                        sampleRate = SAMPLE_RATE,
                        numThreads = 2,
                        provider = "cpu",
                        debug = false
                    )
                    vad = Vad(assetManager = null, config = vadConfig)
                    DiagnosticLogger.log("GameAudio: Neural Silero VAD initialized (${vadFile.name})")
                    Log.i(TAG, "Sherpa-ONNX Silero VAD initialized successfully")
                } else {
                    DiagnosticLogger.log("GameAudio: Silero VAD missing in ${dir.absolutePath}")
                    Log.w(TAG, "Silero VAD file not found on disk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Sherpa-ONNX or Vad: ${e.message}", e)
                DiagnosticLogger.log("GameAudio: Sherpa/Vad init failed: ${e.message}")
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
                onStateChanged?.invoke()
                DiagnosticLogger.log("GameAudio: Playback capture active (16kHz PCM)")
                Log.i(TAG, "AudioRecord started recording game playback audio")

                val shortBuffer = ShortArray(READ_BUFFER_SIZE)

                while (isActive && isRunning) {
                    val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts <= 0) continue

                    val floatSamples = FloatArray(readShorts)
                    for (i in 0 until readShorts) {
                        floatSamples[i] = shortBuffer[i] / 32768.0f
                    }

                    val activeVad = vad
                    val rec = recognizer
                    val st = stream

                    if (activeVad != null && rec != null && st != null) {
                        activeVad.acceptWaveform(floatSamples)
                        val isSpeech = activeVad.isSpeechDetected()

                        // Incremental live decoding while speaking without rendering partial UI text
                        if (isSpeech) {
                            st.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE)
                            while (rec.isReady(st)) {
                                rec.decode(st)
                            }
                        }

                        while (!activeVad.empty()) {
                            val segment = activeVad.front()
                            activeVad.pop()
                            val samples = segment.samples
                            if (samples.isNotEmpty()) {
                                // Compact 0.25s trailing silence tail (4000 floats @ 16kHz) to flush Zipformer final state
                                val trailingSilence = FloatArray(4000)
                                st.acceptWaveform(trailingSilence, sampleRate = SAMPLE_RATE)
                                st.inputFinished()
                                while (rec.isReady(st)) {
                                    rec.decode(st)
                                }
                                val text = rec.getResult(st).text.trim()
                                rec.reset(st)

                                if (text.isNotBlank()) {
                                    val durationMs = (samples.size * 1000L) / SAMPLE_RATE
                                    flushAndTranslate(text, durationMs)
                                }
                            } else {
                                rec.reset(st)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in GameAudio capture loop: ${t.message}", t)
                DiagnosticLogger.log("GameAudio Error: ${t.message}")
            } finally {
                isRunning = false
                onStateChanged?.invoke()
            }
        }
    }

    private suspend fun flushAndTranslate(phrase: String, durationMs: Long) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return

        val nowMs = System.currentTimeMillis()
        val normalizedRaw = normalizeAsrText(trimmed)
        val currentText = translationManager.normalizeAsrTranscript(normalizedRaw).trim()
        if (currentText.isBlank()) return

        // 0. Suppress Post-Patch Tail Fragments:
        // If a new short utterance (<= 4 words) arrives within 2800ms of a patch match
        // and its last word matches the suffix of lastMatchedPatchTargetNormalized (e.g. "vanish"), discard it.
        if (lastMatchedPatchTargetNormalized.isNotEmpty() && (nowMs - lastMatchedPatchTimestampMs) <= 2800L) {
            val words = currentText.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size <= 4) {
                val cleanLastWord = words.last().lowercase().replace(Regex("[^a-z0-9]"), "")
                val targetWords = lastMatchedPatchTargetNormalized.split(Regex("\\s+")).filter { it.isNotBlank() }
                val targetLastWord = targetWords.lastOrNull() ?: ""
                if (cleanLastWord.isNotEmpty() && (cleanLastWord == targetLastWord || lastMatchedPatchTargetNormalized.endsWith(cleanLastWord))) {
                    DiagnosticLogger.log("ASR Tail Echo Discarded (${nowMs - lastMatchedPatchTimestampMs}ms, word='$cleanLastWord'): \"$currentText\"")
                    pendingEnglishBuffer = ""
                    lastSegmentEndTimestampMs = nowMs
                    return
                }
            }
        }

        val gapMs = if (lastSegmentEndTimestampMs > 0L) nowMs - lastSegmentEndTimestampMs else Long.MAX_VALUE

        val shouldStitch = pendingEnglishBuffer.isNotEmpty() && (
            (translationManager.isSyntacticallyIncomplete(pendingEnglishBuffer) && gapMs <= 2200L) ||
            (translationManager.isContinuationClause(currentText) && gapMs <= 2000L) ||
            (gapMs <= 1350L && (pendingEnglishBuffer.length + currentText.length) <= 170)
        )

        val mergedEnglish: String
        if (shouldStitch) {
            val prevClean = if (pendingEnglishBuffer.endsWith(".")) {
                val base = pendingEnglishBuffer.dropLast(1).trim()
                if (base.split(Regex("\\s+")).size <= 3) "$base," else pendingEnglishBuffer
            } else {
                pendingEnglishBuffer
            }
            mergedEnglish = "$prevClean $currentText"
            pendingEnglishBuffer = mergedEnglish
            DiagnosticLogger.log("ASR Clause Stitch (gap=${gapMs}ms): \"$mergedEnglish\"")
        } else {
            mergedEnglish = currentText
            pendingEnglishBuffer = currentText
        }
        lastSegmentEndTimestampMs = nowMs

        DiagnosticLogger.log("ASR Voice ($durationMs ms): \"$mergedEnglish\"")

        // 1. Check Fuzzy Patch Auto-Correction (snaps ASR phonetic typos like "caver" -> "cover")
        val patchMatch = com.zlatan.gamelens.patch.PatchManager.findAsrMatch(mergedEnglish)
        val finalArabic = if (patchMatch != null) {
            val (correctedEn, patchArabic) = patchMatch
            if (patchArabic.isBlank()) {
                DiagnosticLogger.log("ASR Duplicate Patch Span Suppressed: \"$mergedEnglish\" for \"$correctedEn\"")
                pendingEnglishBuffer = ""
                return
            }
            lastMatchedPatchTargetNormalized = com.zlatan.gamelens.patch.PatchManager.normalizeText(correctedEn)
            lastMatchedPatchTimestampMs = nowMs
            DiagnosticLogger.log("ASR Patch Snap: \"$mergedEnglish\" -> \"$correctedEn\" = \"$patchArabic\"")
            patchArabic
        } else {
            val arabicTranslation = translationManager.translate(mergedEnglish, isFinal = true)
            translationManager.deduplicateRepetitions(arabicTranslation)
        }

        if (finalArabic.isNotBlank() && !finalArabic.equals(mergedEnglish, ignoreCase = true)) {
            DiagnosticLogger.log("ASR Subtitle: \"$finalArabic\"")
            com.zlatan.gamelens.diag.SubtitleHistoryManager.addEntry(mergedEnglish, finalArabic)
            onSubtitleReady(finalArabic)
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
            vad?.clear()
        } catch (_: Exception) {}
        try {
            vad?.release()
        } catch (_: Exception) {}
        vad = null

        try {
            stream?.release()
        } catch (_: Exception) {}
        stream = null

        try {
            recognizer?.release()
        } catch (_: Exception) {}
        recognizer = null

        pendingEnglishBuffer = ""
        lastSegmentEndTimestampMs = 0L
        lastMatchedPatchTargetNormalized = ""
        lastMatchedPatchTimestampMs = 0L

        audioScope.cancel()
        DiagnosticLogger.log("GameAudio: Stopped and released")
        onStateChanged?.invoke()
    }
}

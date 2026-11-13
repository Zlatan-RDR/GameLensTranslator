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
    private val onSubtitleReady: (String) -> Unit
) {

    companion object {
        private const val TAG = "GameAudioTranslator"
        private const val SAMPLE_RATE = 16000
        private const val READ_BUFFER_SIZE = 2048
        private const val MIN_ACTIVE_SPEECH_MS = 350L // 350ms speech duration before finalization
        private const val SILENCE_PAUSE_FLUSH_MS = 250L // 250ms pause triggers finalization
        private const val MAX_MONOLOGUE_HARD_CAP_MS = 5500L // 5.5s hard cap for continuous monologues
        private const val PARTIAL_POLL_INTERVAL_MS = 250L // 250ms live streaming partial poll
        private const val PARTIAL_MIN_WORDS_DIFF = 3 // Translate partial text when >= 3 new words added

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
                var lastTranslatedWordCount = 0
                var lastPartialPollTimeMs = 0L
                var speechFrames = 0
                var speechStartTimeMs = 0L
                var lastSpeechDetectedTimeMs = 0L
                var silenceStartTimeMs = 0L
                var noiseFloorRms = 0.005f

                while (isActive && isRunning) {
                    val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts <= 0) continue

                    // Calculate normalized float RMS energy for Adaptive Noise-Floor VAD
                    var sumSquare = 0.0
                    val floatSamples = FloatArray(readShorts)
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i]
                        val f = s / 32768.0f
                        floatSamples[i] = f
                        sumSquare += (f * f).toDouble()
                    }
                    val currentRms = sqrt(sumSquare / readShorts).toFloat()

                    // Track exponential moving average (EMA) of ambient noise/music floor
                    if (speechStartTimeMs == 0L) {
                        noiseFloorRms = noiseFloorRms * 0.95f + currentRms * 0.05f
                    } else {
                        noiseFloorRms = noiseFloorRms * 0.99f + min(currentRms, noiseFloorRms * 1.25f) * 0.01f
                    }

                    // Dynamic threshold: music cutscenes won't stall ASR because cutoff adapts to background music
                    val silenceCutoff = max(noiseFloorRms * 1.40f, 0.008f)
                    val isSpeechRms = currentRms >= silenceCutoff

                    val rec = recognizer
                    val st = stream
                    val now = System.currentTimeMillis()

                    if (isSpeechRms) {
                        speechFrames++
                        if (speechStartTimeMs == 0L) {
                            speechStartTimeMs = now
                            lastPartialPollTimeMs = now
                        }
                        lastSpeechDetectedTimeMs = now
                        silenceStartTimeMs = 0L

                        if (rec != null && st != null) {
                            st.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE)
                            while (rec.isReady(st)) {
                                rec.decode(st)
                            }
                            val text = rec.getResult(st).text.trim()
                            if (text.isNotBlank() && text != lastRecognizedPhrase) {
                                lastRecognizedPhrase = text
                            }

                            // Live Partial Translation While Speaking: Poll every 250ms; if >= 3 new words, translate in-place!
                            if (now - lastPartialPollTimeMs >= PARTIAL_POLL_INTERVAL_MS && lastRecognizedPhrase.isNotBlank()) {
                                lastPartialPollTimeMs = now
                                val words = lastRecognizedPhrase.split(Regex("\\s+")).filter { it.isNotBlank() }
                                if (words.size - lastTranslatedWordCount >= PARTIAL_MIN_WORDS_DIFF) {
                                    lastTranslatedWordCount = words.size
                                    translatePartialInPlace(lastRecognizedPhrase)
                                }
                            }

                            val phraseDuration = now - speechStartTimeMs
                            val isEndpoint = rec.isEndpoint(st)
                            val isMonologueHardCap = phraseDuration >= MAX_MONOLOGUE_HARD_CAP_MS && lastRecognizedPhrase.isNotBlank()

                            if (isEndpoint || isMonologueHardCap) {
                                if (phraseDuration >= MIN_ACTIVE_SPEECH_MS && lastRecognizedPhrase.isNotBlank()) {
                                    flushAndTranslate(lastRecognizedPhrase, phraseDuration)
                                }
                                rec.reset(st)
                                lastRecognizedPhrase = ""
                                lastTranslatedWordCount = 0
                                lastPartialPollTimeMs = 0L
                                speechFrames = 0
                                speechStartTimeMs = 0L
                                silenceStartTimeMs = 0L
                            }
                        }
                    } else {
                        // Ambient floor or silence
                        if (speechStartTimeMs > 0L) {
                            if (silenceStartTimeMs == 0L) {
                                silenceStartTimeMs = now
                            }
                            val silenceDuration = now - silenceStartTimeMs
                            val speechDuration = lastSpeechDetectedTimeMs - speechStartTimeMs

                            if (rec != null && st != null) {
                                st.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE)
                                while (rec.isReady(st)) {
                                    rec.decode(st)
                                }
                                val text = rec.getResult(st).text.trim()
                                if (text.isNotBlank() && text != lastRecognizedPhrase) {
                                    lastRecognizedPhrase = text
                                }

                                val isEndpoint = rec.isEndpoint(st)

                                // Trigger finalization when silenceDuration >= 250ms (after >= 350ms speech) OR isEndpoint
                                if ((silenceDuration >= SILENCE_PAUSE_FLUSH_MS && speechDuration >= MIN_ACTIVE_SPEECH_MS) || isEndpoint) {
                                    if (speechDuration >= MIN_ACTIVE_SPEECH_MS && lastRecognizedPhrase.isNotBlank()) {
                                        flushAndTranslate(lastRecognizedPhrase, speechDuration)
                                    }
                                    rec.reset(st)
                                    lastRecognizedPhrase = ""
                                    lastTranslatedWordCount = 0
                                    lastPartialPollTimeMs = 0L
                                    speechFrames = 0
                                    speechStartTimeMs = 0L
                                    silenceStartTimeMs = 0L
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in GameAudio capture loop: ${t.message}", t)
                DiagnosticLogger.log("GameAudio Error: ${t.message}")
            }
        }
    }

    private suspend fun translatePartialInPlace(phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return

        val normalizedEn = normalizeAsrText(trimmed)
        val patchMatch = com.zlatan.gamelens.patch.PatchManager.findAsrMatch(normalizedEn)
        val arabicText = if (patchMatch != null) {
            patchMatch.second
        } else {
            val trans = translationManager.translate(normalizedEn)
            translationManager.deduplicateRepetitions(trans)
        }

        if (arabicText.isNotBlank() && !arabicText.equals(normalizedEn, ignoreCase = true)) {
            onSubtitleReady(arabicText)
        }
    }

    private suspend fun flushAndTranslate(phrase: String, durationMs: Long) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return

        // Normalize ASR unpunctuated ALL CAPS to clean sentence case with terminal period
        val normalizedEn = normalizeAsrText(trimmed)
        DiagnosticLogger.log("ASR Voice ($durationMs ms): \"$normalizedEn\"")

        // 1. Check Fuzzy Patch Auto-Correction (snaps ASR phonetic typos like "caver" -> "cover")
        val patchMatch = com.zlatan.gamelens.patch.PatchManager.findAsrMatch(normalizedEn)
        val finalArabic = if (patchMatch != null) {
            val (correctedEn, patchArabic) = patchMatch
            DiagnosticLogger.log("ASR Patch Snap: \"$normalizedEn\" -> \"$correctedEn\" = \"$patchArabic\"")
            patchArabic
        } else {
            val arabicTranslation = translationManager.translate(normalizedEn)
            translationManager.deduplicateRepetitions(arabicTranslation)
        }

        if (finalArabic.isNotBlank() && !finalArabic.equals(normalizedEn, ignoreCase = true)) {
            DiagnosticLogger.log("ASR Subtitle: \"$finalArabic\"")
            onSubtitleReady(finalArabic)
            // Note: translationManager.translate already logs to Session Exporter.
            // Duplicate write avoided for patch matches and repeated calls.
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

package com.zlatan.gamelens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zlatan.gamelens.audio.AsrDownloadState
import com.zlatan.gamelens.audio.AsrModelDownloader
import com.zlatan.gamelens.audio.GameAudioTranslator
import com.zlatan.gamelens.databinding.ActivityMainBinding
import com.zlatan.gamelens.db.TranslationCacheManager
import com.zlatan.gamelens.diag.DiagnosticLogger
import com.zlatan.gamelens.glossary.GlossaryManager
import com.zlatan.gamelens.overlay.CalibrationOverlay
import com.zlatan.gamelens.patch.PatchManager
import com.zlatan.gamelens.service.ModelDownloadService
import com.zlatan.gamelens.service.TranslationOverlayService
import com.zlatan.gamelens.translation.ModelStatus
import com.zlatan.gamelens.translation.TranslationConfig
import com.zlatan.gamelens.translation.TranslationManager
import com.zlatan.gamelens.translation.TranslationMode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "gamelens_prefs"
        const val KEY_TRANSLATION_MODE = "key_translation_mode"
        const val KEY_API_KEY = "key_api_key"
        const val KEY_API_ENDPOINT = "key_api_endpoint"
        const val KEY_GAME_AUDIO_ENABLED = "key_game_audio_enabled"
        const val KEY_OVERLAY_RENDER_MODE = "overlay_render_mode"
        const val MODE_OPAQUE_COVER = "opaque_cover"
        const val MODE_OFFSET_ABOVE = "offset_above"

        const val MODE_OFFLINE = "mode_offline"
        const val MODE_CLOUD = "mode_cloud"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var glossaryManager: GlossaryManager
    private lateinit var translationManager: TranslationManager

    private var isServiceRunning = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStates()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "إشعار الخدمة مطلوب لضمان استمرار التشغيل في الخلفية", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, true).apply()
            binding.switchGameAudio.isChecked = true
            Toast.makeText(this, "تم تفعيل الترجمة الصوتية الداخلية للألعاب", Toast.LENGTH_SHORT).show()
        } else {
            binding.switchGameAudio.isChecked = false
            prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, false).apply()
            Toast.makeText(this, "إذن تسجيل الصوت مطلوب لترجمة صوت الألعاب", Toast.LENGTH_SHORT).show()
        }
        updateAudioModelStatus()
    }

    private var pendingEnglishUri: Uri? = null
    private var pendingEnglishFileName: String? = null

    private val pairArabicFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { arUri ->
        val enUri = pendingEnglishUri
        val enName = pendingEnglishFileName
        pendingEnglishUri = null
        pendingEnglishFileName = null
        if (arUri != null && enUri != null && enName != null) {
            val arName = getFileNameFromUri(arUri)
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "جاري دمج ملفات الباتش ($enName + $arName)...", Toast.LENGTH_SHORT).show()
                val count = PatchManager.pairAndLoadDualFiles(this@MainActivity, enUri, arUri, enName, arName)
                if (count > 0) {
                    Toast.makeText(this@MainActivity, "تم دمج وتفعيل باتش اللعبة بنجاح ($count زوج مترجم)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "تعذر دمج الملفين أو لا توجد نصوص متطابقة", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(this, "تم إلغاء اختيار الملف العربي", Toast.LENGTH_SHORT).show()
        }
    }

    private val pairEnglishFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { enUri ->
        if (enUri != null) {
            pendingEnglishUri = enUri
            pendingEnglishFileName = getFileNameFromUri(enUri)
            Toast.makeText(this, getString(R.string.patch_pick_arabic_prompt), Toast.LENGTH_LONG).show()
            pairArabicFilePickerLauncher.launch(arrayOf("*/*"))
        } else {
            Toast.makeText(this, "تم إلغاء اختيار الملف الإنجليزي", Toast.LENGTH_SHORT).show()
        }
    }

    private val patchFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = getFileNameFromUri(uri)
            lifecycleScope.launch {
                val count = PatchManager.loadPatchFromUri(this@MainActivity, uri, fileName)
                if (count > 0) {
                    Toast.makeText(this@MainActivity, "تم تحميل باتش اللعبة بنجاح ($count نص)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "تعذر قراءة نصوص الترجمة من الملف المختار", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "game_patch"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startTranslationService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "تم إلغاء إذن التقاط الشاشة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        cacheManager = TranslationCacheManager(this)
        glossaryManager = GlossaryManager(this)
        val initialConfig = TranslationConfig(
            mode = if (prefs.getString(KEY_TRANSLATION_MODE, MODE_OFFLINE) == MODE_CLOUD) TranslationMode.CLOUD_OPENAI else TranslationMode.OFFLINE_MLKIT,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            apiEndpoint = prefs.getString(KEY_API_ENDPOINT, "https://api.openai.com/v1/chat/completions") ?: ""
        )
        translationManager = TranslationManager(this, cacheManager, glossaryManager, initialConfig)

        loadSettings()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        observeDiagnosticLogs()
        observeModelStatus()
        observeModelDownloadState()
        observePatchStatus()
        PatchManager.loadPersistedActivePatch(this)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        refreshDiagnosticLogs()
        translationManager.checkModelState()
        updateAudioModelStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        translationManager.close()
    }

    private fun observeModelStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                translationManager.modelStatusFlow.collect { status ->
                    updateModelGateUi(status)
                }
            }
        }
    }

    private fun updateModelGateUi(status: ModelStatus) {
        val isOffline = binding.rbOfflineMlKit.isChecked
        when (status) {
            ModelStatus.READY -> {
                binding.tvModelStatusBadge.text = getString(R.string.model_status_ready)
                binding.tvModelStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.pbModelDownload.visibility = View.GONE
                binding.tvModelHint.visibility = View.GONE
                binding.btnDownloadModel.isEnabled = false
                binding.btnDownloadModel.alpha = 0.5f
                binding.btnDownloadModel.text = "النموذج جاهز"
                if (!isServiceRunning) {
                    binding.btnToggleService.isEnabled = true
                    binding.btnToggleService.alpha = 1.0f
                }
            }
            ModelStatus.DOWNLOADING -> {
                binding.tvModelStatusBadge.text = getString(R.string.model_status_downloading)
                binding.tvModelStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.pbModelDownload.visibility = View.VISIBLE
                binding.tvModelHint.visibility = View.VISIBLE
                binding.btnDownloadModel.isEnabled = false
                binding.btnDownloadModel.alpha = 0.5f
                binding.btnDownloadModel.text = "جاري التنزيل..."
                if (isOffline && !isServiceRunning) {
                    binding.btnToggleService.isEnabled = false
                    binding.btnToggleService.alpha = 0.5f
                }
            }
            ModelStatus.NOT_DOWNLOADED -> {
                binding.tvModelStatusBadge.text = getString(R.string.model_status_not_downloaded)
                binding.tvModelStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
                binding.pbModelDownload.visibility = View.GONE
                binding.tvModelHint.visibility = View.GONE
                binding.btnDownloadModel.isEnabled = true
                binding.btnDownloadModel.alpha = 1.0f
                binding.btnDownloadModel.text = getString(R.string.model_btn_download)
                if (isOffline && !isServiceRunning) {
                    binding.btnToggleService.isEnabled = false
                    binding.btnToggleService.alpha = 0.5f
                }
            }
            ModelStatus.ERROR -> {
                binding.tvModelStatusBadge.text = getString(R.string.model_status_error)
                binding.tvModelStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
                binding.pbModelDownload.visibility = View.GONE
                binding.tvModelHint.visibility = View.VISIBLE
                binding.btnDownloadModel.isEnabled = true
                binding.btnDownloadModel.alpha = 1.0f
                binding.btnDownloadModel.text = "إعادة المحاولة"
                if (isOffline && !isServiceRunning) {
                    binding.btnToggleService.isEnabled = false
                    binding.btnToggleService.alpha = 0.5f
                }
            }
        }
    }

    private fun updateAudioModelStatus() {
        val isInstalled = GameAudioTranslator.isAsrModelInstalled(this)
        if (isInstalled) {
            binding.tvAudioModelStatus.text = getString(R.string.game_audio_status_ready)
            binding.tvAudioModelStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.btnDownloadAsrPack.isEnabled = false
            binding.btnDownloadAsrPack.alpha = 0.5f
            binding.btnDownloadAsrPack.text = "النموذج الصوتي مثبت"
            binding.pbAsrDownload.visibility = View.GONE
            binding.tvAsrDownloadProgress.visibility = View.GONE
        } else {
            val hasPart = AsrModelDownloader.hasResumablePart(this)
            val partMb = AsrModelDownloader.getPartFileSizeBytes(this) / (1024.0 * 1024.0)
            if (hasPart) {
                binding.tvAudioModelStatus.text = String.format(java.util.Locale.US, "تنزيل جزئي محفوظ على الذاكرة: %.1f MB (جاهز للاستئناف)", partMb)
                binding.tvAudioModelStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.btnDownloadAsrPack.isEnabled = true
                binding.btnDownloadAsrPack.alpha = 1.0f
                binding.btnDownloadAsrPack.text = getString(R.string.btn_resume_asr_pack)
            } else {
                binding.tvAudioModelStatus.text = getString(R.string.game_audio_status_pending)
                binding.tvAudioModelStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.btnDownloadAsrPack.isEnabled = true
                binding.btnDownloadAsrPack.alpha = 1.0f
                binding.btnDownloadAsrPack.text = getString(R.string.btn_download_asr_pack)
            }
        }
    }

    private fun observeModelDownloadState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadService.downloadStateFlow.collect { state ->
                    when (state) {
                        is AsrDownloadState.Downloading -> {
                            binding.pbAsrDownload.visibility = View.VISIBLE
                            binding.pbAsrDownload.isIndeterminate = false
                            binding.pbAsrDownload.progress = state.progressPercent
                            val downloadedMb = state.bytesDownloaded / (1024.0 * 1024.0)
                            val totalMb = if (state.totalBytes > 0) state.totalBytes / (1024.0 * 1024.0) else 122.0
                            binding.tvAsrDownloadProgress.visibility = View.VISIBLE
                            binding.tvAsrDownloadProgress.text = String.format(
                                java.util.Locale.US,
                                "جاري التنزيل في الخلفية: %d%% (%.1f MB / %.1f MB)",
                                state.progressPercent,
                                downloadedMb,
                                totalMb
                            )
                            binding.tvAudioModelStatus.text = getString(R.string.game_audio_status_downloading)
                            binding.btnDownloadAsrPack.isEnabled = false
                            binding.btnDownloadAsrPack.alpha = 0.5f
                            binding.btnDownloadAsrPack.text = "جاري التنزيل بالخدمة الخلفية..."
                        }
                        is AsrDownloadState.Extracting -> {
                            binding.pbAsrDownload.visibility = View.VISIBLE
                            binding.pbAsrDownload.isIndeterminate = true
                            binding.tvAsrDownloadProgress.visibility = View.VISIBLE
                            binding.tvAsrDownloadProgress.text = "جاري فك ضغط وتثبيت ملفات Zipformer int8..."
                            binding.tvAudioModelStatus.text = getString(R.string.game_audio_status_extracting)
                            binding.btnDownloadAsrPack.isEnabled = false
                            binding.btnDownloadAsrPack.alpha = 0.5f
                            binding.btnDownloadAsrPack.text = "جاري التثبيت..."
                        }
                        is AsrDownloadState.Completed -> {
                            binding.pbAsrDownload.visibility = View.GONE
                            binding.tvAsrDownloadProgress.visibility = View.GONE
                            updateAudioModelStatus()
                            Toast.makeText(this@MainActivity, "تم اكتمال تنزيل وتثبيت نموذج صوت الألعاب بنجاح!", Toast.LENGTH_SHORT).show()
                            prefs.edit()
                                .putBoolean(KEY_GAME_AUDIO_ENABLED, true)
                                .putBoolean("audio_translation_enabled", true)
                                .apply()
                            binding.switchGameAudio.isChecked = true
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        is AsrDownloadState.Error -> {
                            binding.pbAsrDownload.visibility = View.GONE
                            binding.tvAsrDownloadProgress.visibility = View.VISIBLE
                            binding.tvAsrDownloadProgress.text = "توقف التنزيل: ${state.message} (يمكن الاستئناف)"
                            binding.btnDownloadAsrPack.isEnabled = true
                            binding.btnDownloadAsrPack.alpha = 1.0f
                            binding.btnDownloadAsrPack.text = getString(R.string.btn_resume_asr_pack)
                            updateAudioModelStatus()
                        }
                        AsrDownloadState.Idle -> {
                            updateAudioModelStatus()
                        }
                    }
                }
            }
        }
    }

    private fun observeDiagnosticLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiagnosticLogger.logsStateFlow.collect { list ->
                    binding.tvDiagnosticLogs.text = if (list.isEmpty()) {
                        "لا توجد سجلات بعد (No logs recorded yet)"
                    } else {
                        list.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun observePatchStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    PatchManager.activePatchNameFlow,
                    PatchManager.patchCountFlow
                ) { name, count ->
                    Pair(name, count)
                }.collect { (name, count) ->
                    if (name != null && count > 0) {
                        val formattedCount = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(count)
                        binding.tvActivePatchBadge.text = "Patch Active: $name ($formattedCount lines)"
                        binding.tvActivePatchBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
                        binding.btnClearPatch.visibility = View.VISIBLE
                    } else {
                        binding.tvActivePatchBadge.text = getString(R.string.patch_badge_empty)
                        binding.tvActivePatchBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        binding.btnClearPatch.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun refreshDiagnosticLogs() {
        binding.tvDiagnosticLogs.text = DiagnosticLogger.getFormattedLogs()
    }

    private fun loadSettings() {
        val mode = prefs.getString(KEY_TRANSLATION_MODE, MODE_OFFLINE)
        if (mode == MODE_CLOUD) {
            binding.rbCloudOpenAi.isChecked = true
            binding.layoutCloudSettings.visibility = View.VISIBLE
        } else {
            binding.rbOfflineMlKit.isChecked = true
            binding.layoutCloudSettings.visibility = View.GONE
        }

        binding.etApiKey.setText(prefs.getString(KEY_API_KEY, ""))
        binding.etApiEndpoint.setText(prefs.getString(KEY_API_ENDPOINT, "https://api.openai.com/v1/chat/completions"))

        binding.switchGameAudio.isChecked = prefs.getBoolean(KEY_GAME_AUDIO_ENABLED, false)

        val coverMode = prefs.getString(KEY_OVERLAY_RENDER_MODE, MODE_OPAQUE_COVER)
        if (coverMode == MODE_OFFSET_ABOVE) {
            binding.rbOffsetAbove.isChecked = true
        } else {
            binding.rbOpaqueCover.isChecked = true
        }
    }

    private fun setupListeners() {
        binding.rgTranslationMode.setOnCheckedChangeListener { _, checkedId ->
            val isCloud = (checkedId == R.id.rbCloudOpenAi)
            binding.layoutCloudSettings.visibility = if (isCloud) View.VISIBLE else View.GONE

            if (!isServiceRunning) {
                if (!isCloud) {
                    val isReady = (translationManager.modelStatusFlow.value == ModelStatus.READY)
                    binding.btnToggleService.isEnabled = isReady
                    binding.btnToggleService.alpha = if (isReady) 1.0f else 0.5f
                } else {
                    binding.btnToggleService.isEnabled = true
                    binding.btnToggleService.alpha = 1.0f
                }
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            val selectedMode = if (binding.rbCloudOpenAi.isChecked) MODE_CLOUD else MODE_OFFLINE
            val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            val apiEndpoint = binding.etApiEndpoint.text?.toString()?.trim() ?: ""

            prefs.edit()
                .putString(KEY_TRANSLATION_MODE, selectedMode)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_API_ENDPOINT, if (apiEndpoint.isBlank()) "https://api.openai.com/v1/chat/completions" else apiEndpoint)
                .apply()

            translationManager.updateConfig(
                TranslationConfig(
                    mode = if (selectedMode == MODE_CLOUD) TranslationMode.CLOUD_OPENAI else TranslationMode.OFFLINE_MLKIT,
                    apiKey = apiKey,
                    apiEndpoint = if (apiEndpoint.isBlank()) "https://api.openai.com/v1/chat/completions" else apiEndpoint
                )
            )

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        binding.btnDownloadModel.setOnClickListener {
            Toast.makeText(this, "جاري بدء تحميل نموذج الترجمة الإنجليزية -> العربية...", Toast.LENGTH_SHORT).show()
            DiagnosticLogger.log("User triggered offline model download")
            translationManager.downloadOfflineModel(
                onSuccess = {
                    Toast.makeText(this, "اكتمل تنزيل نموذج الترجمة بنجاح!", Toast.LENGTH_SHORT).show()
                    DiagnosticLogger.log("Offline translation model ready")
                },
                onFailure = { e ->
                    Toast.makeText(this, "فشل تنزيل النموذج: ${e.message}", Toast.LENGTH_LONG).show()
                    DiagnosticLogger.log("Model download failed: ${e.message}")
                }
            )
        }

        binding.switchGameAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Toast.makeText(this, "التقاط الصوت الداخلي يتطلب أندرويد 10 فأحدث", Toast.LENGTH_SHORT).show()
                    binding.switchGameAudio.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, true).apply()
                    Toast.makeText(this, "تم تفعيل الترجمة الصوتية الداخلية للألعاب", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, false).apply()
                Toast.makeText(this, "تم إيقاف الترجمة الصوتية", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDownloadAsrPack.setOnClickListener {
            binding.btnDownloadAsrPack.isEnabled = false
            binding.btnDownloadAsrPack.alpha = 0.5f
            binding.pbAsrDownload.visibility = View.VISIBLE
            binding.pbAsrDownload.isIndeterminate = true
            binding.tvAsrDownloadProgress.visibility = View.VISIBLE
            binding.tvAsrDownloadProgress.text = "جاري بدء التنزيل في خدمة الخلفية..."

            DiagnosticLogger.log("MainActivity: Triggering ModelDownloadService startOrResume")
            ModelDownloadService.startOrResume(this@MainActivity)
            Toast.makeText(this@MainActivity, "بدأ تنزيل نموذج الصوت في الخلفية مع إمكانية الاستئناف", Toast.LENGTH_SHORT).show()
        }

        binding.btnLoadGamePatch.setOnClickListener {
            patchFilePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnPairDualPatch.setOnClickListener {
            Toast.makeText(this, getString(R.string.patch_pick_english_prompt), Toast.LENGTH_SHORT).show()
            pairEnglishFilePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnClearPatch.setOnClickListener {
            PatchManager.clearPatch(this)
            Toast.makeText(this, "تم إلغاء الباتش والعودة للترجمة التلقائية", Toast.LENGTH_SHORT).show()
        }

        binding.rgCoverMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbOffsetAbove) MODE_OFFSET_ABOVE else MODE_OPAQUE_COVER
            prefs.edit().putString(KEY_OVERLAY_RENDER_MODE, mode).apply()
        }

        binding.btnCalibrateBox.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "يرجى منح إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isServiceRunning) {
                val intent = Intent(this, TranslationOverlayService::class.java).apply {
                    action = TranslationOverlayService.ACTION_CALIBRATE
                }
                startService(intent)
            } else {
                CalibrationOverlay(this) {}.show()
            }
            Toast.makeText(this, "اسحب المربع لتحديد منطقة قراءة النصوص ثم اضغط حفظ", Toast.LENGTH_SHORT).show()
        }

        binding.btnPresetFull.setOnClickListener {
            CalibrationOverlay.saveCalibration(prefs, CalibrationOverlay.PRESET_FULL_SCREEN)
            Toast.makeText(this, "تم ضبط المعايرة: كامل الشاشة", Toast.LENGTH_SHORT).show()
        }

        binding.btnPresetCenter.setOnClickListener {
            CalibrationOverlay.saveCalibration(prefs, CalibrationOverlay.PRESET_CENTER_DIALOGS)
            Toast.makeText(this, "تم ضبط المعايرة: وسط الشاشة", Toast.LENGTH_SHORT).show()
        }

        binding.btnPresetBottom.setOnClickListener {
            CalibrationOverlay.saveCalibration(prefs, CalibrationOverlay.PRESET_BOTTOM_SUBTITLES)
            Toast.makeText(this, "تم ضبط المعايرة: شريط الترجمة السفلي", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }

        binding.btnHyperOsPerm.setOnClickListener {
            openHyperOsPermissionSettings()
        }

        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopTranslationService()
            } else {
                initiateServiceStart()
            }
        }

        binding.btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GameLens Logs", DiagnosticLogger.getFormattedLogs()))
            Toast.makeText(this, "تم نسخ سجل التشخيص للحافظة", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLog.setOnClickListener {
            DiagnosticLogger.clear()
            refreshDiagnosticLogs()
            Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHyperOsPermissionSettings() {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "يرجى فتح إعدادات التطبيق وتفعيل إذن عرض النوافذ المنبثقة بالخلفية", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updatePermissionStates() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvOverlayStatus.text = getString(R.string.permission_granted)
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.alpha = 0.5f
        } else {
            binding.tvOverlayStatus.text = "غير ممنوح"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.alpha = 1.0f
        }
    }

    private fun initiateServiceStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "يرجى منح إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Strict Offline Model Gate check
        if (binding.rbOfflineMlKit.isChecked && translationManager.modelStatusFlow.value != ModelStatus.READY) {
            Toast.makeText(this, "يرجى تنزيل نموذج الترجمة بدون إنترنت أولاً والانتظار حتى يكتمل", Toast.LENGTH_LONG).show()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startTranslationService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
            action = TranslationOverlayService.ACTION_START
            putExtra(TranslationOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslationOverlayService.EXTRA_RESULT_DATA, data)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunning = true
            updateUiForRunningService()
            Toast.makeText(this, "تم تشغيل خدمة الترجمة الفورية بنجاح", Toast.LENGTH_SHORT).show()
            // Delay moveTaskToBack by 800ms so HyperOS completes bubble window attachment in foreground
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    moveTaskToBack(true)
                }
            }, 800L)
        } catch (e: Exception) {
            Toast.makeText(this, "فشل بدء الخدمة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopTranslationService() {
        val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
            action = TranslationOverlayService.ACTION_STOP
        }
        startService(serviceIntent)
        isServiceRunning = false
        updateUiForStoppedService()
        Toast.makeText(this, "تم إيقاف خدمة الترجمة الفورية", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiForRunningService() {
        binding.btnToggleService.text = getString(R.string.btn_stop_service)
        binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
        binding.tvMediaStatus.text = "الخدمة نشطة"
        binding.tvMediaStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
    }

    private fun updateUiForStoppedService() {
        binding.btnToggleService.text = getString(R.string.btn_start_service)
        binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        binding.tvMediaStatus.text = "جاهز للطلب"
        binding.tvMediaStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
    }
}

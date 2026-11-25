package com.zlatan.gamelens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import java.io.File
import java.util.Locale
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import com.zlatan.gamelens.diag.SubtitleHistoryManager
import com.zlatan.gamelens.glossary.GlossaryManager
import com.zlatan.gamelens.ocr.EnglishWordRepairer
import com.zlatan.gamelens.overlay.CalibrationOverlay
import com.zlatan.gamelens.patch.PatchManager
import com.zlatan.gamelens.profile.GameProfile
import com.zlatan.gamelens.profile.GameProfileManager
import com.zlatan.gamelens.security.SecureApiKeyStorage
import com.zlatan.gamelens.service.ModelDownloadService
import com.zlatan.gamelens.service.TranslationOverlayService
import com.zlatan.gamelens.translation.ModelStatus
import com.zlatan.gamelens.translation.TranslationConfig
import com.zlatan.gamelens.translation.TranslationManager
import com.zlatan.gamelens.translation.TranslationMode
import com.zlatan.gamelens.update.AppUpdateManager
import com.zlatan.gamelens.update.ReleaseInfo
import com.zlatan.gamelens.update.UpdateCheckResult
import com.zlatan.gamelens.update.UpdateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Global foreground tracker to prevent self-capture when GameLens UI is visible.
 */
object AppForegroundState {
    val isMainUiVisible = java.util.concurrent.atomic.AtomicBoolean(false)
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "gamelens_prefs"
        const val KEY_TRANSLATION_MODE = "key_translation_mode"
        const val KEY_API_KEY = "key_api_key"
        const val KEY_API_ENDPOINT = "key_api_endpoint"
        const val KEY_MODEL_NAME = "key_model_name"
        const val KEY_GAME_AUDIO_ENABLED = "key_game_audio_enabled"
        const val KEY_OVERLAY_RENDER_MODE = "overlay_render_mode"
        const val KEY_ONLINE_TRANSLATE_ENHANCED = "key_online_translate_enhanced"
        const val KEY_SUBTITLE_FONT_SIZE_SP = "key_subtitle_font_size_sp"
        const val KEY_SUBTITLE_TEXT_COLOR = "key_subtitle_text_color"
        const val KEY_SUBTITLE_BG_OPACITY = "key_subtitle_bg_opacity"
        const val KEY_SUBTITLE_TEXT_SHADOW = "key_subtitle_text_shadow"
        const val KEY_DEVELOPER_MODE = "key_developer_mode"
        const val KEY_AUTO_SUBTITLE_TRACKING = "key_auto_subtitle_tracking"
        const val DEFAULT_FONT_SIZE_SP = 18f
        const val KEY_TYPEWRITER_MODE = "typewriter_mode_enabled"
        const val MODE_OPAQUE_COVER = "opaque_cover"
        const val MODE_OFFSET_ABOVE = "offset_above"
        const val KEY_MIGRATED_OFFSET_ABOVE_V2 = "migrated_offset_above_v2"

        const val MODE_OFFLINE = "mode_offline"
        const val MODE_CLOUD = "mode_cloud"
        const val MODE_AUTO = "mode_auto"

        @Volatile
        var isAppInForeground: Boolean = false
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var glossaryManager: GlossaryManager
    private lateinit var translationManager: TranslationManager

    private var isServiceRunning = false
    private var isUpdatingProfileUi = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStates()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "إشعار الخدمة مطلوب لإبقاء الترجمة نشطة بالخلفية", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, true).putBoolean("audio_translation_enabled", true).apply()
            binding.switchGameAudio.isChecked = true
            Toast.makeText(this, "تم منح إذن الميكروفون وتفعيل الترجمة الصوتية", Toast.LENGTH_SHORT).show()
        } else {
            binding.switchGameAudio.isChecked = false
            prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, false).putBoolean("audio_translation_enabled", false).apply()
            Toast.makeText(this, "يلزم إذن التسجيل لتفريغ وترجمة أصوات اللعبة", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, "تم تحميل الباتش بنجاح (${count} سطر حوار)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "تعذر قراءة ملف الباتش أو الملف فارغ", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pairArabicFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { arUri ->
        if (arUri != null && PatchManager.hasPendingEnglishFile()) {
            val enName = PatchManager.cachedPendingEnglishFileName ?: "english_source"
            val arName = getFileNameFromUri(arUri)
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "جاري دمج ملفات الباتش ($enName + $arName)...", Toast.LENGTH_SHORT).show()
                val count = PatchManager.pairAndLoadWithCachedEnglish(this@MainActivity, arUri, arName)
                if (count > 0) {
                    Toast.makeText(this@MainActivity, getString(R.string.patch_pair_success, count.toString()), Toast.LENGTH_LONG).show()
                } else {
                    val err = PatchManager.lastPairingError
                    val msg = if (!err.isNullOrBlank()) "فشل الدمج: $err" else "تعذر مطابقة الملفين أو لا توجد أسطر متوافقة"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            PatchManager.clearPendingEnglishFile()
            Toast.makeText(this, "تم إلغاء اختيار الملف العربي", Toast.LENGTH_SHORT).show()
        }
    }

    private val pairEnglishFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { enUri ->
        if (enUri != null) {
            val enName = getFileNameFromUri(enUri)
            lifecycleScope.launch(Dispatchers.IO) {
                val bytes = try {
                    contentResolver.openInputStream(enUri)?.use { it.readBytes() }
                } catch (_: Exception) { null }

                if (bytes != null && bytes.isNotEmpty()) {
                    PatchManager.setPendingEnglishFile(enName, bytes)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.patch_pick_arabic_prompt), Toast.LENGTH_SHORT).show()
                        pairArabicFilePickerLauncher.launch(arrayOf("*/*"))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "تعذر قراءة الملف الإنجليزي", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            PatchManager.clearPendingEnglishFile()
            Toast.makeText(this, "تم إلغاء اختيار الملف الإنجليزي", Toast.LENGTH_SHORT).show()
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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sanitizeSwitchCompats(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Idempotent dictionary loading for instant OCR repair across all lifecycles
        EnglishWordRepairer.init(applicationContext)

        // Clean stale update APKs & schedule 24h background update check
        AppUpdateManager.cleanStaleUpdates(this)
        UpdateCheckWorker.schedulePeriodicCheck(this)

        // Background cache telemetry scan (non-blocking)
        lifecycleScope.launch(Dispatchers.IO) {
            logStartupCacheDiagnostics(applicationContext)
        }

        cacheManager = TranslationCacheManager(this)
        glossaryManager = GlossaryManager(this)
        val initialConfig = TranslationConfig(
            mode = if (prefs.getString(KEY_TRANSLATION_MODE, MODE_AUTO) == MODE_OFFLINE) TranslationMode.OFFLINE_MLKIT else TranslationMode.CLOUD_OPENAI,
            apiKey = SecureApiKeyStorage.getApiKey(prefs, KEY_API_KEY),
            apiEndpoint = prefs.getString(KEY_API_ENDPOINT, "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            modelName = prefs.getString(KEY_MODEL_NAME, "gpt-4o-mini") ?: "gpt-4o-mini"
        )
        translationManager = TranslationManager(this, cacheManager, glossaryManager, initialConfig)

        setupBottomNavigation()
        loadSettings()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        observeDiagnosticLogs()
        observeSubtitleHistory()
        observeModelStatus()
        observeModelDownloadState()
        observePatchStatus()
        PatchManager.init(this)

        handleUpdateIntent(intent)
        checkUpdatesThrottled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AppUpdateManager.EXTRA_OPEN_UPDATE_DIALOG, false) == true) {
            val tag = intent.getStringExtra("release_tag") ?: return
            val body = intent.getStringExtra("release_body") ?: ""
            val url = intent.getStringExtra("release_url") ?: return
            val size = intent.getLongExtra("release_size", 0L)
            showUpdateDialog(ReleaseInfo(tag, body, url, size))
        }
    }

    private fun checkUpdatesThrottled() {
        val lastCheckMs = prefs.getLong(AppUpdateManager.PREFS_KEY_LAST_CHECK_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheckMs >= 12 * 60 * 60 * 1000L) {
            checkForUpdates(manual = false)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> {
                    binding.layoutTabHome.visibility = View.VISIBLE
                    binding.layoutTabAppearance.visibility = View.GONE
                    binding.layoutTabHistory.visibility = View.GONE
                    true
                }
                R.id.tab_appearance -> {
                    binding.layoutTabHome.visibility = View.GONE
                    binding.layoutTabAppearance.visibility = View.VISIBLE
                    binding.layoutTabHistory.visibility = View.GONE
                    true
                }
                R.id.tab_history -> {
                    binding.layoutTabHome.visibility = View.GONE
                    binding.layoutTabAppearance.visibility = View.GONE
                    binding.layoutTabHistory.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundState.isMainUiVisible.set(true)
        isAppInForeground = true
        updatePermissionStates()
        refreshDiagnosticLogs()
        refreshSubtitleHistory()
        translationManager.checkModelState()
        updateAudioModelStatus()
    }

    override fun onPause() {
        super.onPause()
        AppForegroundState.isMainUiVisible.set(false)
        isAppInForeground = false
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
                            binding.switchGameAudio.isChecked = prefs.getBoolean(KEY_GAME_AUDIO_ENABLED, false)
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
                    PatchManager.patchCountFlow,
                    PatchManager.patchDetailsFlow
                ) { name, count, details ->
                    Triple(name, count, details)
                }.collect { (name, count, details) ->
                    if (name != null && count > 0) {
                        val formattedCount = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(count)
                        val detailSuffix = if (!details.isNullOrBlank()) " | $details" else ""
                        binding.tvActivePatchBadge.text = "Patch Active: $name ($formattedCount lines$detailSuffix)"
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

    private fun observeSubtitleHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleHistoryManager.historyFlow.collect {
                    refreshSubtitleHistory()
                }
            }
        }
    }

    private fun refreshSubtitleHistory() {
        binding.tvSubtitleHistory.text = SubtitleHistoryManager.getFormattedHistory()
    }

    private fun loadSettings() {
        val mode = prefs.getString(KEY_TRANSLATION_MODE, MODE_AUTO)
        when (mode) {
            MODE_CLOUD -> {
                binding.rbCloudOpenAi.isChecked = true
                binding.layoutCloudSettings.visibility = View.VISIBLE
            }
            MODE_OFFLINE -> {
                binding.rbOfflineMlKit.isChecked = true
                binding.layoutCloudSettings.visibility = View.GONE
            }
            else -> {
                binding.rbAutoCloudOffline.isChecked = true
                binding.layoutCloudSettings.visibility = View.GONE
            }
        }

        binding.etApiKey.setText(SecureApiKeyStorage.getApiKey(prefs, KEY_API_KEY))
        binding.etApiEndpoint.setText(prefs.getString(KEY_API_ENDPOINT, "https://api.openai.com/v1"))
        binding.etModelName.setText(prefs.getString(KEY_MODEL_NAME, "gpt-4o-mini"))

        binding.switchGameAudio.isChecked = prefs.getBoolean(KEY_GAME_AUDIO_ENABLED, false)

        // One-time migration to Offset Above mode for 0% flicker
        if (!prefs.getBoolean(KEY_MIGRATED_OFFSET_ABOVE_V2, false)) {
            prefs.edit()
                .putString(KEY_OVERLAY_RENDER_MODE, MODE_OFFSET_ABOVE)
                .putBoolean(KEY_MIGRATED_OFFSET_ABOVE_V2, true)
                .apply()
        }

        val coverMode = prefs.getString(KEY_OVERLAY_RENDER_MODE, MODE_OFFSET_ABOVE)
        if (coverMode == MODE_OFFSET_ABOVE) {
            binding.rbOffsetAbove.isChecked = true
        } else {
            binding.rbOpaqueCover.isChecked = true
        }

        binding.switchOnlineEnhanced.isChecked = prefs.getBoolean(KEY_ONLINE_TRANSLATE_ENHANCED, false)
        binding.switchTypewriterMode.isChecked = prefs.getBoolean(KEY_TYPEWRITER_MODE, false)

        // Subtitle Appearance Customizer Settings
        val fontSizeSp = prefs.getFloat(KEY_SUBTITLE_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
        binding.sliderFontSize.progress = (fontSizeSp - 14f).toInt().coerceIn(0, 14)
        binding.tvFontSizeValue.text = getString(R.string.font_size_label, fontSizeSp.toInt())

        val colorHex = prefs.getString(KEY_SUBTITLE_TEXT_COLOR, "#FFFFFF") ?: "#FFFFFF"
        when (colorHex) {
            "#FDE047" -> binding.rbColorGold.isChecked = true
            "#67E8F9" -> binding.rbColorCyan.isChecked = true
            else -> binding.rbColorWhite.isChecked = true
        }

        val opacity = prefs.getInt(KEY_SUBTITLE_BG_OPACITY, 88)
        binding.sliderBgOpacity.progress = (opacity - 40).coerceIn(0, 60)
        binding.tvBgOpacityValue.text = getString(R.string.bg_opacity_label, opacity)

        binding.switchTextShadow.isChecked = prefs.getBoolean(KEY_SUBTITLE_TEXT_SHADOW, true)

        // Developer Mode
        val devMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false)
        DiagnosticLogger.isDevModeActive = devMode
        binding.switchDeveloperMode.isChecked = devMode
        binding.layoutDiagnosticConsole.visibility = if (devMode) View.VISIBLE else View.GONE

        // App Version
        binding.tvCurrentVersion.text = getString(R.string.current_version_label, BuildConfig.VERSION_NAME)

        updateSubtitlePreview()

        if (!isUpdatingProfileUi) {
            setupGameProfileSelector()
        }
    }

    private fun updateSubtitlePreview(notifyService: Boolean = true) {
        val fontSize = prefs.getFloat(KEY_SUBTITLE_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
        val textColorHex = prefs.getString(KEY_SUBTITLE_TEXT_COLOR, "#FFFFFF") ?: "#FFFFFF"
        val bgOpacity = prefs.getInt(KEY_SUBTITLE_BG_OPACITY, 88)
        val hasShadow = prefs.getBoolean(KEY_SUBTITLE_TEXT_SHADOW, true)
        val isOpaqueCover = prefs.getString(KEY_OVERLAY_RENDER_MODE, MODE_OFFSET_ABOVE) == MODE_OPAQUE_COVER

        binding.tvPreviewSubtitleText.textSize = fontSize
        try {
            binding.tvPreviewSubtitleText.setTextColor(Color.parseColor(textColorHex))
        } catch (_: Exception) {
            binding.tvPreviewSubtitleText.setTextColor(Color.WHITE)
        }

        if (hasShadow) {
            binding.tvPreviewSubtitleText.setShadowLayer(6f, 0f, 2f, Color.BLACK)
        } else {
            binding.tvPreviewSubtitleText.setShadowLayer(0f, 0f, 0f, 0)
        }

        val bgAlpha = (bgOpacity * 255 / 100).coerceIn(40, 255)
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            val colorBase = if (isOpaqueCover) Color.parseColor("#05070B") else Color.parseColor("#0D111A")
            setColor(Color.argb(bgAlpha, Color.red(colorBase), Color.green(colorBase), Color.blue(colorBase)))
            if (hasShadow && !isOpaqueCover) {
                setStroke((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.parseColor("#4DFFFFFF"))
            } else {
                setStroke(0, 0)
            }
        }
        binding.tvPreviewSubtitleText.background = bgDrawable

        if (notifyService && isServiceRunning) {
            val intent = Intent(this, TranslationOverlayService::class.java).apply {
                action = TranslationOverlayService.ACTION_RELOAD_SETTINGS
                putExtra(TranslationOverlayService.EXTRA_FONT_SIZE_SP, fontSize)
            }
            startService(intent)
        }
    }

    private fun autoSaveTranslationConfig() {
        val selectedMode = when {
            binding.rbAutoCloudOffline.isChecked -> MODE_AUTO
            binding.rbCloudOpenAi.isChecked -> MODE_CLOUD
            else -> MODE_OFFLINE
        }
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val apiEndpoint = binding.etApiEndpoint.text?.toString()?.trim() ?: ""
        val modelName = binding.etModelName.text?.toString()?.trim()?.lowercase() ?: "gpt-4o-mini"

        val finalEndpoint = if (apiEndpoint.isBlank()) "https://api.openai.com/v1" else apiEndpoint
        val (isValid, errorMsg) = SecureApiKeyStorage.isValidEndpointUrl(finalEndpoint)
        if (!isValid && (selectedMode == MODE_CLOUD || selectedMode == MODE_AUTO)) {
            binding.etApiEndpoint.error = errorMsg
        } else {
            binding.etApiEndpoint.error = null
        }

        SecureApiKeyStorage.saveApiKey(prefs, KEY_API_KEY, apiKey)
        prefs.edit()
            .putString(KEY_TRANSLATION_MODE, selectedMode)
            .putString(KEY_API_ENDPOINT, finalEndpoint)
            .putString(KEY_MODEL_NAME, if (modelName.isBlank()) "gpt-4o-mini" else modelName)
            .putBoolean(KEY_ONLINE_TRANSLATE_ENHANCED, binding.switchOnlineEnhanced.isChecked)
            .apply()

        translationManager.updateConfig(
            TranslationConfig(
                mode = if (selectedMode == MODE_OFFLINE) TranslationMode.OFFLINE_MLKIT else TranslationMode.CLOUD_OPENAI,
                apiKey = apiKey,
                apiEndpoint = finalEndpoint,
                modelName = if (modelName.isBlank()) "gpt-4o-mini" else modelName
            )
        )
    }

    private fun testApiConnection() {
        val endpoint = binding.etApiEndpoint.text?.toString()?.trim() ?: ""
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val model = binding.etModelName.text?.toString()?.trim() ?: ""

        val finalEndpoint = if (endpoint.isBlank()) "https://api.openai.com/v1" else endpoint
        val (isValid, errorMsg) = SecureApiKeyStorage.isValidEndpointUrl(finalEndpoint)
        if (!isValid) {
            binding.tvTestApiResult.visibility = View.VISIBLE
            binding.tvTestApiResult.setTextColor(Color.parseColor("#F87171"))
            binding.tvTestApiResult.text = "❌ $errorMsg"
            return
        }

        val isLocal = SecureApiKeyStorage.isLocalEndpoint(finalEndpoint)
        if (apiKey.isBlank() && !isLocal) {
            binding.tvTestApiResult.visibility = View.VISIBLE
            binding.tvTestApiResult.setTextColor(Color.parseColor("#FBBF24"))
            binding.tvTestApiResult.text = "⚠️ يرجى إدخال مفتاح API أولاً ثم إعادة الاختبار"
            return
        }

        binding.tvTestApiResult.visibility = View.VISIBLE
        binding.tvTestApiResult.setTextColor(Color.parseColor("#94A3B8"))
        binding.tvTestApiResult.text = "جارٍ اختبار الاتصال..."

        lifecycleScope.launch(Dispatchers.IO) {
            val testConfig = TranslationConfig(
                mode = TranslationMode.CLOUD_OPENAI,
                apiKey = apiKey,
                apiEndpoint = finalEndpoint,
                modelName = if (model.isBlank()) "gpt-4o-mini" else model
            )
            val tempManager = TranslationManager(applicationContext, cacheManager, glossaryManager, testConfig)
            val startTime = android.os.SystemClock.elapsedRealtime()
            try {
                val result = tempManager.testCustomAiConnection("Oh, you must be Victoria. Come on in, child.")
                val latency = android.os.SystemClock.elapsedRealtime() - startTime
                withContext(Dispatchers.Main) {
                    if (result.success && !result.translation.isNullOrBlank()) {
                        binding.tvTestApiResult.setTextColor(Color.parseColor("#34D399"))
                        binding.tvTestApiResult.text = "✅ ${latency}ms: ${result.translation}"
                    } else {
                        binding.tvTestApiResult.setTextColor(Color.parseColor("#F87171"))
                        val err = result.errorMessage ?: "فشل الاتصال: تأكد من صحة المفتاح والموديل والرابط."
                        binding.tvTestApiResult.text = if (err.startsWith("❌")) err else "❌ $err"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvTestApiResult.setTextColor(Color.parseColor("#F87171"))
                    binding.tvTestApiResult.text = "❌ خطأ: ${e.message}"
                }
            } finally {
                tempManager.close()
            }
        }
    }

    private fun setupListeners() {
        // Translation Engine Selection (Auto-Saved)
        binding.rgTranslationMode.setOnCheckedChangeListener { _, checkedId ->
            val showCloud = (checkedId == R.id.rbCloudOpenAi)
            binding.layoutCloudSettings.visibility = if (showCloud) View.VISIBLE else View.GONE
            autoSaveTranslationConfig()

            if (!isServiceRunning) {
                if (checkedId == R.id.rbOfflineMlKit) {
                    val isReady = (translationManager.modelStatusFlow.value == ModelStatus.READY)
                    binding.btnToggleService.isEnabled = isReady
                    binding.btnToggleService.alpha = if (isReady) 1.0f else 0.5f
                } else {
                    binding.btnToggleService.isEnabled = true
                    binding.btnToggleService.alpha = 1.0f
                }
            }
        }

        val autoSaveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                autoSaveTranslationConfig()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.etApiEndpoint.addTextChangedListener(autoSaveWatcher)
        binding.etApiKey.addTextChangedListener(autoSaveWatcher)
        binding.etModelName.addTextChangedListener(autoSaveWatcher)

        // One-Tap AI Provider Presets
        binding.btnPresetGemini.setOnClickListener {
            binding.etApiEndpoint.setText("https://generativelanguage.googleapis.com/v1beta/models")
            binding.etModelName.setText("gemini-3.5-flash")
            autoSaveTranslationConfig()
        }
        binding.btnPresetGroq.setOnClickListener {
            binding.etApiEndpoint.setText("https://api.groq.com/openai/v1/chat/completions")
            binding.etModelName.setText("llama-3.3-70b-versatile")
            autoSaveTranslationConfig()
        }
        binding.btnPresetOpenAi.setOnClickListener {
            binding.etApiEndpoint.setText("https://api.openai.com/v1/chat/completions")
            binding.etModelName.setText("gpt-4o-mini")
            autoSaveTranslationConfig()
        }
        binding.btnPresetClaude.setOnClickListener {
            binding.etApiEndpoint.setText("https://api.anthropic.com/v1/messages")
            binding.etModelName.setText("claude-3-5-haiku-latest")
            autoSaveTranslationConfig()
        }
        binding.btnPresetLocal.setOnClickListener {
            binding.etApiEndpoint.setText("http://127.0.0.1:8080/v1/chat/completions")
            binding.etModelName.setText("local-model")
            autoSaveTranslationConfig()
        }

        // Live API Connection Test
        binding.btnTestApi.setOnClickListener {
            testApiConnection()
        }

        binding.switchOnlineEnhanced.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ONLINE_TRANSLATE_ENHANCED, isChecked).apply()
            autoSaveTranslationConfig()
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
                    prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, true).putBoolean("audio_translation_enabled", true).apply()
                    Toast.makeText(this, "تم تفعيل الترجمة الصوتية الداخلية للألعاب", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean(KEY_GAME_AUDIO_ENABLED, false).putBoolean("audio_translation_enabled", false).apply()
                Toast.makeText(this, "تم إيقاف الترجمة الصوتية", Toast.LENGTH_SHORT).show()
            }
        }

        // Subtitle Appearance: Display Mode
        binding.rgCoverMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbOffsetAbove) MODE_OFFSET_ABOVE else MODE_OPAQUE_COVER
            prefs.edit().putString(KEY_OVERLAY_RENDER_MODE, mode).apply()
            updateSubtitlePreview()
        }

        // Subtitle Appearance: Font Size Slider
        binding.sliderFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fontSize = 14f + progress
                binding.tvFontSizeValue.text = getString(R.string.font_size_label, fontSize.toInt())
                if (fromUser) {
                    prefs.edit().putFloat(KEY_SUBTITLE_FONT_SIZE_SP, fontSize).apply()
                    updateSubtitlePreview(notifyService = false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isServiceRunning) {
                    val fontSize = prefs.getFloat(KEY_SUBTITLE_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
                    val intent = Intent(this@MainActivity, TranslationOverlayService::class.java).apply {
                        action = TranslationOverlayService.ACTION_RELOAD_SETTINGS
                        putExtra(TranslationOverlayService.EXTRA_FONT_SIZE_SP, fontSize)
                    }
                    startService(intent)
                }
            }
        })

        // Subtitle Appearance: Text Color Presets
        binding.rgTextColorPresets.setOnCheckedChangeListener { _, checkedId ->
            val colorHex = when (checkedId) {
                R.id.rbColorGold -> "#FDE047"
                R.id.rbColorCyan -> "#67E8F9"
                else -> "#FFFFFF"
            }
            prefs.edit().putString(KEY_SUBTITLE_TEXT_COLOR, colorHex).apply()
            updateSubtitlePreview()
        }

        // Subtitle Appearance: Background Opacity Slider
        binding.sliderBgOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val opacity = 40 + progress
                binding.tvBgOpacityValue.text = getString(R.string.bg_opacity_label, opacity)
                if (fromUser) {
                    prefs.edit().putInt(KEY_SUBTITLE_BG_OPACITY, opacity).apply()
                    updateSubtitlePreview(notifyService = false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isServiceRunning) {
                    val fontSize = prefs.getFloat(KEY_SUBTITLE_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
                    val intent = Intent(this@MainActivity, TranslationOverlayService::class.java).apply {
                        action = TranslationOverlayService.ACTION_RELOAD_SETTINGS
                        putExtra(TranslationOverlayService.EXTRA_FONT_SIZE_SP, fontSize)
                    }
                    startService(intent)
                }
            }
        })

        // Subtitle Appearance: Border / Shadow Toggle
        binding.switchTextShadow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SUBTITLE_TEXT_SHADOW, isChecked).apply()
            updateSubtitlePreview()
        }

        // Developer Mode Toggle
        binding.switchDeveloperMode.setOnCheckedChangeListener { _, isChecked ->
            DiagnosticLogger.isDevModeActive = isChecked
            prefs.edit().putBoolean(KEY_DEVELOPER_MODE, isChecked).apply()
            binding.layoutDiagnosticConsole.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // App Updates Check
        binding.btnCheckUpdates.setOnClickListener {
            checkForUpdates(manual = true)
        }

        // GitHub Repo Link
        binding.btnGithubRepo.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Zlatan-RDR/GameLensTranslator"))
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "تعذر فتح الرابط: ${t.message}", Toast.LENGTH_SHORT).show()
            }
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

        binding.btnTestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "يرجى منح إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isServiceRunning) {
                val intent = Intent(this, TranslationOverlayService::class.java).apply {
                    action = TranslationOverlayService.ACTION_TEST_OVERLAY
                }
                startService(intent)
            } else {
                Toast.makeText(this, "يرجى تشغيل الخدمة العائمة أولاً للتحقق من طبقة الترجمة", Toast.LENGTH_SHORT).show()
            }
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

        binding.btnCopySubtitleHistory.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GameLens Subtitles", SubtitleHistoryManager.getFormattedHistory()))
            Toast.makeText(this, "تم نسخ سجل الحوارات المترجمة", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearSubtitleHistory.setOnClickListener {
            SubtitleHistoryManager.clear()
            refreshSubtitleHistory()
            Toast.makeText(this, "تم مسح سجل الحوارات", Toast.LENGTH_SHORT).show()
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

        binding.switchTypewriterMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_TYPEWRITER_MODE, isChecked).apply()
            val curProfile = GameProfileManager.getActiveProfile(this)
            GameProfileManager.saveProfile(this, curProfile.copy(typewriterMode = isChecked))
            Toast.makeText(this, if (isChecked) "تم تفعيل وضع النص المتدفق (350ms)" else "تم إيقاف وضع النص المتدفق (استجابة فورية)", Toast.LENGTH_SHORT).show()
        }

        binding.btnExportSubtitleHistory.setOnClickListener {
            if (SubtitleHistoryManager.historyFlow.value.isEmpty()) {
                Toast.makeText(this, "لا توجد حوارات مسجلة بعد للتصدير", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val options = arrayOf("JSON Patch (تعديل اللعبة والمشاركة)", "SRT Subtitles (ملف ترجمة مرئي)")
            val dialog = AlertDialog.Builder(this)
                .setTitle("تصدير الحوارات المسجلة")
                .setItems(options) { _, which ->
                    val format = if (which == 0) SubtitleHistoryManager.ExportFormat.JSON else SubtitleHistoryManager.ExportFormat.SRT
                    val savedFileName = SubtitleHistoryManager.exportSessionToDownloads(this, format)
                    if (savedFileName != null) {
                        Toast.makeText(this, "تم الحفظ بنجاح: $savedFileName في مجلد Downloads/GameLens/Exports/", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "تعذر حفظ الملف", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .create()
            styleDarkDialog(dialog)
            dialog.show()
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, getString(R.string.checking_updates), Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            when (val result = AppUpdateManager.checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    showUpdateDialog(result.release)
                }
                is UpdateCheckResult.UpToDate -> {
                    if (manual) {
                        Toast.makeText(this@MainActivity, getString(R.string.app_up_to_date, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (manual) {
                        Toast.makeText(this@MainActivity, "خطأ في التحقق من التحديثات: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun styleDarkDialog(dialog: AlertDialog) {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 24f
        }
        dialog.window?.setBackgroundDrawable(bg)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#34D399"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#CBD5E1"))
            dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(Color.parseColor("#F8FAFC"))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.parseColor("#E2E8F0"))
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val sizeMb = release.apkSizeBytes / (1024.0 * 1024.0)
        val cleanNotes = AppUpdateManager.cleanReleaseNotes(release.releaseNotes)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, release.tagName))
            .setMessage("الحجم: %.1f MB\n\nالمميزات والإصلاحات:\n%s".format(java.util.Locale.US, sizeMb, cleanNotes))
            .setPositiveButton(R.string.btn_update_now, null)
            .setNegativeButton("لاحقاً", null)
            .create()

        styleDarkDialog(dialog)
        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            positiveBtn?.setTextColor(Color.parseColor("#34D399"))
            negativeBtn?.setTextColor(Color.parseColor("#CBD5E1"))
            dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(Color.parseColor("#F8FAFC"))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.parseColor("#E2E8F0"))

            positiveBtn?.setOnClickListener {
                positiveBtn.isEnabled = false
                dialog.setMessage("جاري تنزيل التحديث (%s)... يرجى الانتظار.".format(release.tagName))

                lifecycleScope.launch {
                    val downloadedApk = AppUpdateManager.downloadApk(this@MainActivity, release.downloadUrl) { down, total ->
                        val pct = if (total > 0) (down * 100 / total).toInt() else 0
                        runOnUiThread {
                            dialog.setMessage("جاري التنزيل: %d%% \u2066(%.1f MB / %.1f MB)\u2069".format(java.util.Locale.US, pct, down / (1024.0 * 1024.0), total / (1024.0 * 1024.0)))
                        }
                    }

                    if (downloadedApk != null && downloadedApk.exists()) {
                        dialog.dismiss()
                        AppUpdateManager.installApk(this@MainActivity, downloadedApk)
                    } else {
                        Toast.makeText(this@MainActivity, "فشل تنزيل ملف التحديث، يرجى المحاولة ثانية", Toast.LENGTH_LONG).show()
                        positiveBtn.isEnabled = true
                    }
                }
            }
        }

        dialog.show()
    }

    private fun setupGameProfileSelector() {
        val profiles = GameProfileManager.getProfiles(this)
        val profileNames = profiles.map { it.name }

        // Light text (#F1F5F9) on dark slate (#1E293B) custom spinner layouts
        val adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_game_profile,
            profileNames
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_game_profile_dropdown)
        }
        binding.spGameProfiles.adapter = adapter

        val activeProfileId = GameProfileManager.getActiveProfileId(this)
        val activeIndex = profiles.indexOfFirst { it.id == activeProfileId }.coerceAtLeast(0)
        binding.spGameProfiles.setSelection(activeIndex)

        val curActiveProfile = GameProfileManager.getActiveProfile(this)
        binding.switchHighContrastOcr.setOnCheckedChangeListener(null)
        binding.switchHighContrastOcr.isChecked = curActiveProfile.highContrastOcr
        binding.switchHighContrastOcr.setOnCheckedChangeListener { _, isChecked ->
            val p = GameProfileManager.getActiveProfile(this)
            if (p.highContrastOcr != isChecked) {
                GameProfileManager.saveProfile(this, p.copy(highContrastOcr = isChecked))
                Toast.makeText(this, if (isChecked) "تم تفعيل وضع التباين العالي (Mort Filter)" else "تم تعطيل وضع التباين العالي", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchAutoSubtitleTracking.setOnCheckedChangeListener(null)
        binding.switchAutoSubtitleTracking.isChecked = curActiveProfile.autoSubtitleTracking
        binding.btnCalibrateBox.isEnabled = !curActiveProfile.autoSubtitleTracking
        binding.btnCalibrateBox.alpha = if (curActiveProfile.autoSubtitleTracking) 0.5f else 1.0f
        binding.switchAutoSubtitleTracking.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_SUBTITLE_TRACKING, isChecked).apply()
            val cur = GameProfileManager.getActiveProfile(this)
            GameProfileManager.saveProfile(this, cur.copy(autoSubtitleTracking = isChecked))
            binding.btnCalibrateBox.isEnabled = !isChecked
            binding.btnCalibrateBox.alpha = if (isChecked) 0.5f else 1.0f
            Toast.makeText(this, if (isChecked) "تم تفعيل التتبع التلقائي لمكان الحوار (Auto-ROI)" else "تم العودة لمنطقة المعايرة اليدوية", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, TranslationOverlayService::class.java).apply {
                    action = TranslationOverlayService.ACTION_RELOAD_SETTINGS
                }
                startService(intent)
            } catch (_: Throwable) {}
        }

        binding.btnGlossary.setOnClickListener {
            showGlossaryDialog()
        }

        binding.spGameProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingProfileUi) return
                val selectedProfile = profiles.getOrNull(position) ?: return
                if (selectedProfile.id != GameProfileManager.getActiveProfileId(this@MainActivity)) {
                    GameProfileManager.applyProfile(this@MainActivity, selectedProfile.id)
                    binding.switchHighContrastOcr.isChecked = selectedProfile.highContrastOcr
                    binding.switchAutoSubtitleTracking.isChecked = selectedProfile.autoSubtitleTracking
                    binding.btnCalibrateBox.isEnabled = !selectedProfile.autoSubtitleTracking
                    binding.btnCalibrateBox.alpha = if (selectedProfile.autoSubtitleTracking) 0.5f else 1.0f
                    isUpdatingProfileUi = true
                    loadSettings()
                    isUpdatingProfileUi = false
                    Toast.makeText(this@MainActivity, "تم تطبيق ملف: ${selectedProfile.name}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnSaveNewProfile.setOnClickListener {
            val input = EditText(this).apply {
                hint = getString(R.string.dialog_new_profile_hint)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#94A3B8"))
                val border = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#0F172A"))
                    setStroke(2, Color.parseColor("#334155"))
                    cornerRadius = 16f
                }
                background = border
                setPadding(32, 24, 32, 24)
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 8)
                addView(input)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.dialog_new_profile_title)
                .setView(container)
                .setPositiveButton(R.string.btn_create) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val calRect = CalibrationOverlay.getCalibratedRect(prefs)
                        val displayMode = prefs.getString(KEY_OVERLAY_RENDER_MODE, MODE_OFFSET_ABOVE) ?: MODE_OFFSET_ABOVE
                        val fontSize = prefs.getFloat(KEY_SUBTITLE_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
                        val typewriter = prefs.getBoolean(KEY_TYPEWRITER_MODE, false)
                        val textColor = prefs.getString(KEY_SUBTITLE_TEXT_COLOR, "#FFFFFF") ?: "#FFFFFF"
                        val bgOpacity = prefs.getInt(KEY_SUBTITLE_BG_OPACITY, 88)
                        val shadow = prefs.getBoolean(KEY_SUBTITLE_TEXT_SHADOW, true)
                        val autoTracking = prefs.getBoolean(KEY_AUTO_SUBTITLE_TRACKING, false)
                        val newId = "profile_" + System.currentTimeMillis()
                        val newProfile = GameProfile(
                            id = newId,
                            name = name,
                            roiLeft = calRect.left,
                            roiTop = calRect.top,
                            roiRight = calRect.right,
                            roiBottom = calRect.bottom,
                            displayMode = displayMode,
                            fontSizeSp = fontSize,
                            typewriterMode = typewriter,
                            textColorHex = textColor,
                            bgOpacityPct = bgOpacity,
                            textShadow = shadow,
                            autoSubtitleTracking = autoTracking
                        )
                        GameProfileManager.saveProfile(this, newProfile)
                        GameProfileManager.applyProfile(this, newId)
                        setupGameProfileSelector()
                        Toast.makeText(this, "تم إنشاء وحفظ ملف: $name", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
            styleDarkDialog(dialog)
            dialog.show()
        }

        binding.btnDeleteProfile.setOnClickListener {
            val curId = GameProfileManager.getActiveProfileId(this)
            if (curId == "default") {
                Toast.makeText(this, "لا يمكن حذف الملف الافتراضي", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val curProfile = GameProfileManager.getActiveProfile(this)
            val dialog = AlertDialog.Builder(this)
                .setTitle("تأكيد الحذف")
                .setMessage("هل تريد بالتأكيد حذف ملف تعريف: ${curProfile.name}؟")
                .setPositiveButton("حذف") { _, _ ->
                    GameProfileManager.deleteProfile(this, curId)
                    setupGameProfileSelector()
                    loadSettings()
                    Toast.makeText(this, "تم حذف الملف بنجاح", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .create()
            styleDarkDialog(dialog)
            dialog.show()
        }
    }

    private fun showGlossaryDialog() {
        val active = GameProfileManager.getActiveProfile(this)
        val border = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#0F172A"))
            setStroke(2, Color.parseColor("#334155"))
            cornerRadius = 16f
        }
        val input = EditText(this).apply {
            hint = "أدخل المصطلحات واسم الشخصية وسياق الجنس (سطر لكل مصطلح):\nVictoria = فيكتوريا [أنثى]\nAgent 47 = العميل 47 [ذكر]\nDiana = ديانا [أنثى]"
            setText(active.glossaryText)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = border
            setPadding(32, 32, 32, 32)
        }

        val caption = TextView(this).apply {
            text = "ملاحظة: وسوم [أنثى]/[ذكر] تفعل التأنيث التلقائي الكامل مع محرك الذكاء الاصطناعي (مثل Gemini المجاني) وتصحح الجمل المذكور فيها الاسم على المحرك المجاني."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(8, 12, 8, 4)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(caption)
        }

        val scroll = ScrollView(this).apply {
            addView(container)
            setPadding(24, 16, 24, 16)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("قاموس اللعبة وتحديد الجنس (Glossary)")
            .setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                val updatedText = input.text.toString().trim()
                val updatedProfile = active.copy(glossaryText = updatedText)
                GameProfileManager.saveProfile(this, updatedProfile)
                Toast.makeText(this, "تم حفظ قاموس اللعبة بنجاح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .create()

        styleDarkDialog(dialog)
        dialog.show()
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
            binding.tvPermissionBannerBadge.text = getString(R.string.perm_banner_granted)
            binding.tvPermissionBannerBadge.setTextColor(Color.parseColor("#10B981"))
            binding.layoutPermissionButtons.visibility = View.GONE
        } else {
            binding.tvPermissionBannerBadge.text = getString(R.string.perm_banner_missing)
            binding.tvPermissionBannerBadge.setTextColor(Color.parseColor("#FF5252"))
            binding.layoutPermissionButtons.visibility = View.VISIBLE
        }

        val mfg = Build.MANUFACTURER.lowercase(java.util.Locale.US)
        if (mfg == "xiaomi" || mfg == "redmi" || mfg == "poco") {
            binding.cardHyperOsPermission.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        } else {
            binding.cardHyperOsPermission.visibility = View.GONE
        }

        if (!isServiceRunning) {
            val isReady = (translationManager.modelStatusFlow.value == ModelStatus.READY) ||
                    binding.rbCloudOpenAi.isChecked || binding.rbAutoCloudOffline.isChecked
            binding.btnToggleService.isEnabled = isReady
            binding.btnToggleService.alpha = if (isReady) 1.0f else 0.5f
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

    private fun sanitizeSwitchCompats(view: View) {
        if (view is SwitchCompat) {
            view.showText = false
            if (view.textOn == null) view.textOn = ""
            if (view.textOff == null) view.textOff = ""
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                sanitizeSwitchCompats(view.getChildAt(i))
            }
        }
    }

    private fun logStartupCacheDiagnostics(context: Context) {
        try {
            val targets = listOfNotNull(
                context.cacheDir to "cache",
                context.codeCacheDir to "code_cache",
                context.externalCacheDir to "ext_cache"
            )
            var totalBytes = 0L
            val largeEntries = mutableListOf<String>()

            fun computeDirSize(dir: File): Long {
                var size = 0L
                val files = dir.listFiles() ?: return 0L
                for (f in files) {
                    size += if (f.isDirectory) computeDirSize(f) else f.length()
                }
                return size
            }

            for ((dir, tag) in targets) {
                if (!dir.exists()) continue
                val entries = dir.listFiles() ?: continue
                for (entry in entries) {
                    val entrySize = if (entry.isDirectory) computeDirSize(entry) else entry.length()
                    totalBytes += entrySize
                    if (entrySize >= 1024 * 1024) {
                        val sizeMb = entrySize.toDouble() / (1024.0 * 1024.0)
                        largeEntries.add("$tag/${entry.name}=%.1fMB".format(Locale.US, sizeMb))
                    }
                }
            }

            val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)
            val details = if (largeEntries.isEmpty()) "none >= 1MB" else largeEntries.joinToString(", ")
            val logMsg = "StartupCache: total=%.1fMB [$details]".format(Locale.US, totalMb)
            Log.i("MainActivity", logMsg)
            DiagnosticLogger.log(logMsg)
        } catch (t: Throwable) {
            Log.w("MainActivity", "Failed to scan startup cache: ${t.message}")
        }
    }
}

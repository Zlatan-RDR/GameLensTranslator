package com.zlatan.gamelens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import com.zlatan.gamelens.databinding.ActivityMainBinding
import com.zlatan.gamelens.service.TranslationOverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "gamelens_prefs"
        const val KEY_TRANSLATION_MODE = "key_translation_mode"
        const val KEY_API_KEY = "key_api_key"
        const val KEY_API_ENDPOINT = "key_api_endpoint"

        const val MODE_OFFLINE = "mode_offline"
        const val MODE_CLOUD = "mode_cloud"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
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

        loadSettings()
        setupListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
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
    }

    private fun setupListeners() {
        binding.rgTranslationMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCloudOpenAi) {
                binding.layoutCloudSettings.visibility = View.VISIBLE
            } else {
                binding.layoutCloudSettings.visibility = View.GONE
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

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
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

        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopTranslationService()
            } else {
                initiateServiceStart()
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

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startTranslationService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
            action = TranslationOverlayService.ACTION_START
            putExtra(TranslationOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslationOverlayService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        updateUiForRunningService()
        Toast.makeText(this, "تم تشغيل خدمة الترجمة الفورية بنجاح", Toast.LENGTH_SHORT).show()
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

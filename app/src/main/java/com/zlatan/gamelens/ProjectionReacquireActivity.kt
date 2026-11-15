package com.zlatan.gamelens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zlatan.gamelens.diag.DiagnosticLogger
import com.zlatan.gamelens.service.TranslationOverlayService

/**
 * Transparent Trampoline Activity that re-acquires MediaProjection permission
 * seamlessly when the system invalidates it (e.g. during screen resize or onStop),
 * without needing the user to return to MainActivity.
 */
class ProjectionReacquireActivity : AppCompatActivity() {

    private var autoStartAudio: Boolean = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            DiagnosticLogger.log("ProjectionReacquireActivity: MediaProjection re-acquired successfully")
            val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
                action = TranslationOverlayService.ACTION_UPDATE_PROJECTION
                putExtra(TranslationOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(TranslationOverlayService.EXTRA_RESULT_DATA, result.data)
                putExtra(TranslationOverlayService.EXTRA_AUTO_START_AUDIO, autoStartAudio)
            }
            startService(serviceIntent)
        } else {
            DiagnosticLogger.log("ProjectionReacquireActivity: Re-acquisition cancelled or denied")
            Toast.makeText(this, "تم إلغاء إذن التقاط الشاشة/الصوت", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoStartAudio = intent.getBooleanExtra(TranslationOverlayService.EXTRA_AUTO_START_AUDIO, false)

        val mpMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpMgr != null) {
            projectionLauncher.launch(mpMgr.createScreenCaptureIntent())
        } else {
            Toast.makeText(this, "خدمة التقاط الشاشة غير متوفرة", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

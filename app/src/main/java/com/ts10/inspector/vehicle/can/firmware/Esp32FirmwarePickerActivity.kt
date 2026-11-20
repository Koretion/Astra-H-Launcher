package com.ts10.inspector.vehicle.can.firmware

import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.R
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.ts10.inspector.panel.ExclusiveLauncherSession

/**
 * Hosts the system document picker in a real Activity.
 *
 * The settings UI normally lives in [HybridOverlayService], so it cannot use Compose's
 * ActivityResult launcher directly. Keeping this boundary explicit also lets the system picker
 * stay above the launcher's application overlay on vendor Android builds.
 */
class Esp32FirmwarePickerActivity : ComponentActivity() {
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Esp32FirmwareUpdater.selectFirmware(applicationContext, uri)
        }
        finishPickerSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            runCatching {
                openDocument.launch(arrayOf("application/octet-stream", "application/x-binary", "*/*"))
            }.onFailure { error ->
                Log.e(TAG, "Unable to open firmware document picker", error)
                Toast.makeText(this, AppLocale.string(this, R.string.fw_picker_failed), Toast.LENGTH_LONG).show()
                finishPickerSession()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            launchPending = false
            ExclusiveLauncherSession.complete(this)
        }
        super.onDestroy()
    }

    private fun finishPickerSession() {
        launchPending = false
        ExclusiveLauncherSession.complete(this)
        finish()
    }

    companion object {
        private const val TAG = "Esp32FirmwarePicker"
        private const val LAUNCH_DELAY_MS = 120L

        @Volatile
        private var launchPending = false

        fun launch(context: Context) {
            if (launchPending) return
            launchPending = true
            ExclusiveLauncherSession.begin(context)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    val started = runCatching {
                        context.startActivity(
                            Intent(context, Esp32FirmwarePickerActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    }.getOrElse { error ->
                        Log.e(TAG, "Unable to start firmware picker activity", error)
                        false
                    }
                    if (!started) {
                        launchPending = false
                        ExclusiveLauncherSession.complete(context)
                        Toast.makeText(context, AppLocale.string(context, R.string.fw_picker_failed), Toast.LENGTH_LONG).show()
                    }
                },
                LAUNCH_DELAY_MS,
            )
        }
    }
}

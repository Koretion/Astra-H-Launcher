package com.ts10.inspector.vehicle.can.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ts10.inspector.R
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selector de documentos del sistema para importar o exportar perfiles CAN. Los Ajustes viven en
 * el overlay y no pueden usar ActivityResult directamente, igual que el selector de firmware.
 */
class VehicleProfileFileActivity : ComponentActivity() {
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult finishSession()
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { VehicleProfileManager.import(applicationContext, uri) }
            // En el idioma elegido en el launcher, no en el de Android.
            toast(
                (status as? UiText.Res)?.let { AppLocale.string(this@VehicleProfileFileActivity, it.id, *it.args.toTypedArray()) }
                    ?: status.resolve(this@VehicleProfileFileActivity),
            )
            finishSession()
        }
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult finishSession()
        val id = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(VehicleProfileManager.exportText(id).toByteArray(Charsets.UTF_8))
                    } ?: error("Android could not open the selected file")
                }
            }
            toast(
                if (result.isSuccess) {
                    AppLocale.string(this@VehicleProfileFileActivity, R.string.vprofile_exported)
                } else {
                    AppLocale.string(this@VehicleProfileFileActivity, R.string.export_failed, result.exceptionOrNull()?.message.orEmpty())
                },
            )
            finishSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        runCatching {
            if (intent.getBooleanExtra(EXTRA_EXPORT, false)) {
                createDocument.launch(VehicleProfileManager.suggestedFileName(intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()))
            } else {
                // Algunos gestores de archivos no asocian .json a application/json.
                openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }
        }.onFailure {
            toast(AppLocale.string(this, R.string.export_picker_failed))
            finishSession()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun finishSession() {
        ExclusiveLauncherSession.complete(this)
        finish()
    }

    companion object {
        private const val EXTRA_EXPORT = "com.ts10.inspector.extra.PROFILE_EXPORT"
        private const val EXTRA_PROFILE_ID = "com.ts10.inspector.extra.PROFILE_ID"

        fun launchImport(context: Context) = launch(context, Intent(context, VehicleProfileFileActivity::class.java))

        fun launchExport(context: Context, profileId: String) = launch(
            context,
            Intent(context, VehicleProfileFileActivity::class.java)
                .putExtra(EXTRA_EXPORT, true)
                .putExtra(EXTRA_PROFILE_ID, profileId),
        )

        private fun launch(context: Context, intent: Intent) {
            ExclusiveLauncherSession.begin(context)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    val started = runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                    if (!started) {
                        ExclusiveLauncherSession.complete(context)
                        Toast.makeText(context, AppLocale.string(context, R.string.export_picker_failed), Toast.LENGTH_LONG).show()
                    }
                },
                120L,
            )
        }
    }
}

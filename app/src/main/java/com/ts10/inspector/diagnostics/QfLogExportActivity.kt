package com.ts10.inspector.diagnostics

import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.R
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.vehicle.can.Esp32CanClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class QfLogExportActivity : ComponentActivity() {
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        if (destination == null) {
            ExclusiveLauncherSession.complete(this)
            finish()
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            val result = runCatching {
                val report = withContext(Dispatchers.IO) {
                    DiagnosticReport.build(
                        applicationContext,
                        LauncherRuntime.diagnostics.value,
                    )
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination, "wt")?.use { stream ->
                        stream.writer(StandardCharsets.UTF_8).buffered().use { writer ->
                            writer.write(report)
                            Esp32CanClient.appendCaptureTo(writer)
                        }
                    } ?: error("Android could not open the selected file")
                }
            }
            Toast.makeText(
                this@QfLogExportActivity,
                if (result.isSuccess) AppLocale.string(this@QfLogExportActivity, R.string.export_saved) else AppLocale.string(this@QfLogExportActivity, R.string.export_failed, result.exceptionOrNull()?.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
            ExclusiveLauncherSession.complete(this@QfLogExportActivity)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) createDocument.launch(DiagnosticReport.suggestedFileName())
    }

    companion object {
        fun launch(context: Context) {
            ExclusiveLauncherSession.begin(context)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    val started = runCatching {
                        context.startActivity(
                            Intent(context, QfLogExportActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
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

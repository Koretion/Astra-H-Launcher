package com.ts10.inspector.diagnostics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import com.ts10.inspector.R
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.ui.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class FrameworkExportProgress(
    val running: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
    val files: Int = 0,
    val totalFiles: Int = 0,
    val copiedBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val percent: Int get() = if (totalBytes <= 0) 0 else (copiedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
    val copiedMb: Long get() = copiedBytes / BYTES_PER_MB
    val totalMb: Long get() = totalBytes / BYTES_PER_MB
}

private const val BYTES_PER_MB = 1024L * 1024L

/**
 * Copia en un ZIP los archivos legibles sin root del framework y las apps del sistema para
 * analizar la ROM QF (QFApi, política de ventanas) en el PC. Solo lectura: no modifica el sistema.
 */
class FrameworkExportActivity : ComponentActivity() {
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        ExclusiveLauncherSession.complete(this)
        if (destination != null) startExport(applicationContext, destination)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) createDocument.launch(suggestedFileName())
    }

    companion object {
        // La copia dura minutos; no debe depender de la vida de esta actividad transparente.
        private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutableProgress = MutableStateFlow(FrameworkExportProgress())
        internal val progress: StateFlow<FrameworkExportProgress> = mutableProgress.asStateFlow()
        private const val CHANNEL_ID = "framework_export"
        private const val NOTIFICATION_ID = 0x7E10

        private val SOURCE_DIRECTORIES = listOf(
            "/system/framework",
            "/system/priv-app",
            "/system/app",
            "/system/etc/permissions",
            "/system/etc/sysconfig",
            "/product/framework",
            "/product/priv-app",
            "/product/app",
            "/vendor/framework",
            "/vendor/app",
            "/system_ext/framework",
            "/system_ext/priv-app",
        )
        private val SOURCE_FILES = listOf(
            "/system/build.prop",
            "/vendor/build.prop",
            "/product/build.prop",
        )

        fun launch(context: Context) {
            if (mutableProgress.value.running) {
                Toast.makeText(context, AppLocale.string(context, R.string.framework_export_running), Toast.LENGTH_LONG).show()
                return
            }
            ExclusiveLauncherSession.begin(context)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    val started = runCatching {
                        context.startActivity(
                            Intent(context, FrameworkExportActivity::class.java)
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

        private fun suggestedFileName(): String =
            "TS10_Framework_${Build.DISPLAY.replace(Regex("[^A-Za-z0-9._-]"), "_")}_" +
                "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))}.zip"

        private fun startExport(context: Context, destination: Uri) {
            if (mutableProgress.value.running) return
            mutableProgress.value = FrameworkExportProgress(running = true)
            toast(context, AppLocale.string(context, R.string.framework_export_started))
            exportScope.launch {
                val result = runCatching { writeZip(context, destination) }
                val final = mutableProgress.value.copy(
                    running = false,
                    completed = result.isSuccess,
                    failed = result.isFailure,
                )
                mutableProgress.value = final
                result.onSuccess {
                    TelemetryLog.event("FRAMEWORK_EXPORT", "completed files=${final.files} bytes=${final.copiedBytes}")
                    val message = AppLocale.string(context, R.string.framework_export_done, final.files, final.copiedMb)
                    notifyProgress(context, final, message)
                    toast(context, message)
                }.onFailure {
                    TelemetryLog.event("FRAMEWORK_EXPORT", "failed ${it.javaClass.simpleName}:${it.message.orEmpty()}")
                    val message = AppLocale.string(context, R.string.export_failed, it.message.orEmpty())
                    notifyProgress(context, final, message)
                    toast(context, message)
                }
            }
        }

        private fun toast(context: Context, message: String) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }

        private fun notifyProgress(context: Context, state: FrameworkExportProgress, text: String) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        AppLocale.string(context, R.string.framework_export_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
                manager.notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(AppLocale.string(context, R.string.framework_export_title))
                        .setContentText(text)
                        .setOnlyAlertOnce(true)
                        .setOngoing(state.running)
                        .setAutoCancel(!state.running)
                        .apply { if (state.running) setProgress(100, state.percent, state.totalBytes <= 0) }
                        .build(),
                )
            }
        }

        private fun writeZip(context: Context, destination: Uri) {
            val skipped = mutableListOf<String>()
            // Primero se recorre todo para conocer el tamaño total y poder mostrar el porcentaje.
            val sources = buildList {
                SOURCE_DIRECTORIES.map(::File).forEach { directory ->
                    if (!directory.isDirectory) {
                        skipped += "${directory.path} (missing)"
                        return@forEach
                    }
                    directory.walkTopDown()
                        .onFail { file, error -> skipped += "${file.path} (${error.javaClass.simpleName})" }
                        .filter { it.isFile }
                        .forEach(::add)
                }
                SOURCE_FILES.map(::File).filter(File::exists).forEach(::add)
            }
            val totalBytes = sources.sumOf { if (it.canRead()) it.length() else 0L }
            mutableProgress.value = mutableProgress.value.copy(totalFiles = sources.size, totalBytes = totalBytes)
            notifyProgress(
                context,
                mutableProgress.value,
                AppLocale.string(context, R.string.framework_export_progress, 0, 0L, totalBytes / BYTES_PER_MB),
            )

            val copied = mutableListOf<String>()
            var copiedBytes = 0L
            var lastPublishedPercent = -1
            fun publish() {
                val state = mutableProgress.value.copy(files = copied.size, copiedBytes = copiedBytes)
                mutableProgress.value = state
                if (state.percent != lastPublishedPercent) {
                    lastPublishedPercent = state.percent
                    notifyProgress(
                        context,
                        state,
                        AppLocale.string(context, R.string.framework_export_progress, state.percent, state.copiedMb, state.totalMb),
                    )
                }
            }
            val buffer = ByteArray(256 * 1024)
            val stream = context.contentResolver.openOutputStream(destination, "w")
                ?: error("Android could not open the selected file")
            ZipOutputStream(stream.buffered(1024 * 1024)).use { zip ->
                // APK, JAR, ODEX y VDEX ya van comprimidos: guardar sin recomprimir es mucho más rápido.
                zip.setLevel(Deflater.NO_COMPRESSION)
                sources.forEach { file ->
                    if (!file.canRead()) {
                        skipped += "${file.path} (unreadable)"
                        return@forEach
                    }
                    runCatching {
                        zip.putNextEntry(ZipEntry(file.path.trimStart('/')).apply { time = file.lastModified() })
                        file.inputStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                zip.write(buffer, 0, read)
                                copiedBytes += read
                                publish()
                            }
                        }
                        zip.closeEntry()
                        copied += "${file.path} ${file.length()}"
                    }.onFailure { skipped += "${file.path} (${it.javaClass.simpleName}: ${it.message.orEmpty()})" }
                }

                zip.putNextEntry(ZipEntry("TS10_framework_index.txt"))
                zip.write(
                    buildString {
                        appendLine("TS10 framework export")
                        appendLine("Generated: ${LocalDateTime.now()}")
                        appendLine("Fingerprint: ${Build.FINGERPRINT}")
                        appendLine("Display: ${Build.DISPLAY}")
                        appendLine("Device / board / hardware: ${Build.DEVICE} / ${Build.BOARD} / ${Build.HARDWARE}")
                        appendLine("SDK: ${Build.VERSION.SDK_INT}")
                        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
                        appendLine()
                        appendLine("Copied files (${copied.size}, $copiedBytes bytes):")
                        copied.forEach(::appendLine)
                        appendLine()
                        appendLine("Skipped (${skipped.size}):")
                        skipped.forEach(::appendLine)
                    }.toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
            }
            publish()
        }
    }
}

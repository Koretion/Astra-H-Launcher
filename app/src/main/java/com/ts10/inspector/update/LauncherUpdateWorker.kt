package com.ts10.inspector.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import com.ts10.inspector.launcher.HybridOverlayService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Comprobación periódica de nuevas versiones; no hace nada si el modo es OFF. */
internal class LauncherUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        LauncherUpdateManager.runCheck(applicationContext, automatic = true)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "launcher_update_check"
        private const val INTERVAL_HOURS = 12L
        private const val INITIAL_DELAY_MINUTES = 3L

        fun schedule(context: Context, mode: UpdateMode) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            if (mode == UpdateMode.OFF) {
                workManager.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LauncherUpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    // El primer arranque del día no compite con la conexión de CAN, Bluetooth y mapas.
                    .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}

/** Marca el HOME relanzado tras instalar una actualización. */
internal const val EXTRA_AFTER_UPDATE = "com.ts10.inspector.extra.AFTER_UPDATE"

/** Acciones de la notificación y resultado de la sesión de PackageInstaller. */
class LauncherUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LauncherUpdateManager.ACTION_INSTALL_STATUS -> LauncherUpdateManager.onInstallStatus(context, intent)
            LauncherUpdateManager.ACTION_DOWNLOAD -> LauncherUpdateManager.download(context)
            LauncherUpdateManager.ACTION_INSTALL -> LauncherUpdateManager.install(context)
            GmlanFirmwareUpdateManager.ACTION_REVIEW -> HybridOverlayService.showSettings(context)
            // Al actualizarse, Android mata el proceso y la app que estaba en el panel queda a
            // pantalla completa sin launcher: se vuelve a mostrar HOME.
            Intent.ACTION_MY_PACKAGE_REPLACED -> runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_AFTER_UPDATE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

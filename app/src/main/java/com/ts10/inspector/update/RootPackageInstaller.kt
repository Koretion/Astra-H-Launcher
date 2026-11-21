package com.ts10.inspector.update

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import com.ts10.inspector.MainActivity
import com.ts10.inspector.diagnostics.AndroidRootChecker
import com.ts10.inspector.diagnostics.RootDetectionStatus
import com.ts10.inspector.diagnostics.TelemetryLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Instalación sin diálogo cuando la unidad tiene root (Magisk).
 *
 * Android mata este proceso al reemplazar el paquete, así que todo el trabajo lo hace un script
 * root desacoplado con `setsid`: instala, vuelve a abrir HOME y compila AOT (`speed`). Tras una
 * actualización ART solo tiene JIT y el 3D/Compose van mucho más lentos hasta que el sistema
 * recompila en mantenimiento, cosa que en una radio de coche casi nunca ocurre; por eso, al
 * terminar la compilación, el launcher se reinicia una vez para cargar el código compilado.
 */
internal object RootPackageInstaller {
    private const val GRANT_TIMEOUT_SECONDS = 20L
    private const val TMP_APK = "/data/local/tmp/astra-launcher-update.apk"

    fun likelyAvailable(): Boolean = AndroidRootChecker.state.value.status == RootDetectionStatus.DETECTED

    /** Devuelve true si el script quedó lanzado; false para recurrir a PackageInstaller. */
    fun install(context: Context, apk: File): Boolean {
        if (!likelyAvailable() || !hasRootGrant()) return false
        val pkg = context.packageName
        val home = ComponentName(context, MainActivity::class.java).flattenToShortString()
        val receiver = ComponentName(context, LauncherUpdateReceiver::class.java).flattenToShortString()
        val start = "am start -n $home -a android.intent.action.MAIN -c android.intent.category.HOME " +
            "-f 0x10000000 --ez $EXTRA_AFTER_UPDATE true"
        val script = File(context.cacheDir, "root-install.sh")
        script.writeText(
            """
            |#!/system/bin/sh
            |cp '${apk.absolutePath}' $TMP_APK && chmod 644 $TMP_APK || exit 1
            |OUT=${'$'}(pm install -r $TMP_APK 2>&1)
            |rm -f $TMP_APK
            |case "${'$'}OUT" in
            |  *Success*) ;;
            |  *) am broadcast -n $receiver -a ${LauncherUpdateManager.ACTION_INSTALL_STATUS} \
            |       --ei ${PackageInstaller.EXTRA_STATUS} ${PackageInstaller.STATUS_FAILURE} \
            |       --es ${PackageInstaller.EXTRA_STATUS_MESSAGE} "${'$'}(echo ${'$'}OUT | tr -d '\n' | cut -c1-200)"
            |     exit 1 ;;
            |esac
            |$start
            |sleep 3
            |case "${'$'}(cmd package compile -m speed -f $pkg 2>&1)" in
            |  *Success*) am force-stop $pkg; sleep 1; $start ;;
            |esac
            |rm -f '${script.absolutePath}'
            |
            """.trimMargin(),
        )
        return runCatching {
            val process = ProcessBuilder(
                "su", "-c", "setsid sh '${script.absolutePath}' </dev/null >/dev/null 2>&1 &",
            ).redirectErrorStream(true).start()
            process.waitFor(GRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        }.onFailure { TelemetryLog.event("UPDATE", "root install launch failed: ${it.message}") }
            .getOrDefault(false)
            .also { TelemetryLog.event("UPDATE", "root install launched=$it") }
    }

    /** Magisk muestra su diálogo de permiso la primera vez; si se deniega se usa el instalador normal. */
    private fun hasRootGrant(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!process.waitFor(GRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return false
        }
        process.inputStream.bufferedReader().readText().contains("uid=0")
    }.getOrDefault(false)
}

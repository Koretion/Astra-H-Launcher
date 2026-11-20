package com.ts10.inspector.update

import com.ts10.inspector.ui.uiText
import com.ts10.inspector.ui.fail
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import com.ts10.inspector.ui.AppLocale
import android.content.Context
import android.net.Uri
import com.ts10.inspector.BuildConfig
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.vehicle.can.firmware.Esp32FirmwareUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal data class GmlanFirmwareReleaseState(
    val mode: UpdateMode = UpdateMode.NOTIFY,
    val status: UiText = UiText.of(R.string.upd_not_checked),
    val busy: Boolean = false,
    val progressPercent: Int = 0,
    val availableVersion: String? = null,
    val downloadedVersion: String? = null,
    val lastCheckMillis: Long = 0L,
)

/**
 * Firmware del GMLAN-USB desde GitHub Releases (repositorio propio del firmware).
 *
 * Solo se consulta con el GMLAN-USB conectado y su versión leída. La imagen descargada pasa por
 * su `.sha256` y por las validaciones de [Esp32FirmwareUpdater] (cabecera ESP32-C3, tamaño y
 * versión incrustada, que debe coincidir con la etiqueta). El modo INSTALL solo aplica OTA a un
 * firmware que ya la admite y con el coche parado; la primera instalación por ROM siempre requiere
 * la confirmación del usuario.
 */
internal object GmlanFirmwareUpdateManager {
    private const val PREFERENCES = "gmlan_firmware_updates"
    private const val KEY_MODE = "mode"
    private const val KEY_NOTIFIED_VERSION = "notified_version"
    private const val KEY_LAST_CHECK = "last_check_millis"
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val PARKED_BEFORE_INSTALL_MS = 30_000L
    private const val NOTIFICATION_ID = 421
    const val ACTION_REVIEW = "com.ts10.inspector.update.FIRMWARE_REVIEW"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(GmlanFirmwareReleaseState())
    val state: StateFlow<GmlanFirmwareReleaseState> = mutableState.asStateFlow()

    private var appContext: Context? = null
    private var latest: GithubRelease? = null
    private var installJob: Job? = null

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val preferences = preferences(app)
        val mode = runCatching {
            UpdateMode.valueOf(preferences.getString(KEY_MODE, null) ?: UpdateMode.NOTIFY.name)
        }.getOrDefault(UpdateMode.NOTIFY)
        mutableState.update { it.copy(mode = mode, lastCheckMillis = preferences.getLong(KEY_LAST_CHECK, 0L)) }
        scope.launch {
            // Cada conexión con versión identificada permite una comprobación si ya tocaba.
            Esp32FirmwareUpdater.state
                .map { it.usbConnected && it.installedVersion != null }
                .distinctUntilChanged()
                .collect { ready ->
                    val due = System.currentTimeMillis() - mutableState.value.lastCheckMillis >= CHECK_INTERVAL_MS
                    if (ready && due) runCheck(app, automatic = true)
                }
        }
    }

    fun setMode(context: Context, mode: UpdateMode) {
        start(context)
        preferences(context).edit().putString(KEY_MODE, mode.name).apply()
        mutableState.update { it.copy(mode = mode) }
        if (mode != UpdateMode.INSTALL) installJob?.cancel()
    }

    fun checkNow(context: Context) {
        start(context)
        scope.launch { runCheck(context.applicationContext, automatic = false) }
    }

    fun download(context: Context) {
        start(context)
        scope.launch { operation.withLock { downloadLocked(context.applicationContext) } }
    }

    private suspend fun runCheck(context: Context, automatic: Boolean) {
        val mode = mutableState.value.mode
        if (automatic && mode == UpdateMode.OFF) return
        operation.withLock {
            setStatus(UiText.of(R.string.upd_checking), busy = true)
            val release = runCatching { GithubReleases.latest(BuildConfig.FIRMWARE_UPDATE_REPOSITORY) }
                .getOrElse { error ->
                    setStatus(error.uiText(), busy = false)
                    return
                }
            val now = System.currentTimeMillis()
            preferences(context).edit().putLong(KEY_LAST_CHECK, now).apply()
            val installed = Esp32FirmwareUpdater.state.value.installedVersion
            // Sin versión leída (firmware antiguo sin OTA) cualquier release se ofrece.
            if (installed != null && compareVersions(release.version, installed) <= 0) {
                latest = null
                mutableState.update { it.copy(status = UiText.of(R.string.upd_up_to_date), busy = false, availableVersion = null, lastCheckMillis = now) }
                return
            }
            latest = release
            mutableState.update {
                it.copy(status = UiText.of(R.string.gfw_available, release.version), busy = false, availableVersion = release.version, lastCheckMillis = now)
            }
            TelemetryLog.event("UPDATE", "gmlan ${release.version} available installed=$installed mode=$mode")
            if (!automatic) return
            when (mode) {
                UpdateMode.OFF -> Unit
                UpdateMode.NOTIFY -> notifyOnce(context, release.version, AppLocale.string(context, R.string.firmware_available_title, release.version), AppLocale.string(context, R.string.firmware_available_text))
                UpdateMode.DOWNLOAD, UpdateMode.INSTALL -> {
                    if (!downloadLocked(context)) return
                    val ota = Esp32FirmwareUpdater.state.value.otaSupported == true
                    if (mode == UpdateMode.INSTALL && ota) {
                        installWhenParked(context, release.version)
                    } else {
                        notifyOnce(context, release.version, AppLocale.string(context, R.string.firmware_ready_title, release.version), AppLocale.string(context, R.string.firmware_ready_text))
                    }
                }
            }
        }
    }

    /** Descarga, comprueba el SHA-256 y entrega la imagen a [Esp32FirmwareUpdater]. */
    private suspend fun downloadLocked(context: Context): Boolean {
        val release = latest ?: run {
            setStatus(UiText.of(R.string.gfw_check_first), busy = false)
            return false
        }
        val image = release.asset { it.endsWith(".bin", ignoreCase = true) }
        val checksum = image?.let(release::checksumFor)
        if (image == null || checksum == null) {
            setStatus(UiText.of(R.string.gfw_release_missing_bin), busy = false)
            return false
        }
        val directory = File(context.filesDir, "firmware").apply { mkdirs() }
        val target = File(directory, "gmlan-usb-${release.version}.bin")
        return runCatching {
            setStatus(UiText.of(R.string.upd_downloading, release.version), busy = true)
            val expected = parseSha256File(GithubReleases.readText(checksum)) ?: fail(R.string.upd_sha_unreadable)
            val actual = GithubReleases.download(image, target, MAX_IMAGE_BYTES) { percent ->
                mutableState.update { it.copy(progressPercent = percent) }
            }
            if (actual != expected) fail(R.string.upd_sha_mismatch)
            directory.listFiles()?.filter { it != target }?.forEach(File::delete)
            withContext(Dispatchers.Main) { Esp32FirmwareUpdater.selectFirmware(context, Uri.fromFile(target)) }
            // El actualizador valida la imagen en su propio hilo.
            val validated = withTimeoutOrNull(30_000L) {
                Esp32FirmwareUpdater.state.first { !it.busy && it.candidateName != null }
            }
            if (validated?.candidateVersion != release.version) {
                fail(R.string.gfw_version_mismatch, validated?.candidateVersion ?: UiText.of(R.string.gfw_unreadable))
            }
        }.fold(
            onSuccess = {
                mutableState.update {
                    it.copy(status = UiText.of(R.string.gfw_ready, release.version), busy = false, downloadedVersion = release.version)
                }
                TelemetryLog.event("UPDATE", "gmlan ${release.version} downloaded and validated")
                true
            },
            onFailure = { error ->
                target.delete()
                TelemetryLog.event("UPDATE", "gmlan download failed: ${error.message}")
                setStatus(UiText.of(R.string.upd_download_rejected, error.uiText()), busy = false)
                false
            },
        )
    }

    private fun installWhenParked(context: Context, version: String) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            while (true) {
                LauncherRuntime.state.first { it.speedKmh == 0 }
                delay(PARKED_BEFORE_INSTALL_MS)
                if (LauncherRuntime.state.value.speedKmh == 0) break
            }
            val updater = Esp32FirmwareUpdater.state.value
            if (mutableState.value.mode != UpdateMode.INSTALL || !updater.usbConnected ||
                updater.otaSupported != true || updater.candidateVersion != version || updater.busy
            ) {
                return@launch
            }
            TelemetryLog.event("UPDATE", "gmlan $version automatic OTA")
            setStatus(UiText.of(R.string.gfw_installing_ota, version), busy = false)
            withContext(Dispatchers.Main) {
                Esp32FirmwareUpdater.requestInstallationConfirmation()
                Esp32FirmwareUpdater.confirmInstallation(context)
            }
        }
    }

    private fun notifyOnce(context: Context, version: String, title: String, text: String) {
        val preferences = preferences(context)
        val key = "$version/${mutableState.value.downloadedVersion != null}"
        if (preferences.getString(KEY_NOTIFIED_VERSION, null) == key) return
        preferences.edit().putString(KEY_NOTIFIED_VERSION, key).apply()
        showUpdateNotification(context, NOTIFICATION_ID, title, text, ACTION_REVIEW)
    }

    private fun setStatus(status: UiText, busy: Boolean) =
        mutableState.update { it.copy(status = status, busy = busy, progressPercent = if (busy) it.progressPercent else 0) }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

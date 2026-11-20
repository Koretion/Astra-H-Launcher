package com.ts10.inspector.update

import com.ts10.inspector.ui.uiText
import com.ts10.inspector.ui.fail
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import com.ts10.inspector.ui.AppLocale
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ts10.inspector.AppVersion
import com.ts10.inspector.BuildConfig
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.panel.openExclusiveSystemScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal data class LauncherUpdateState(
    val mode: UpdateMode = UpdateMode.NOTIFY,
    val status: UiText = UiText.of(R.string.upd_not_checked),
    val busy: Boolean = false,
    val progressPercent: Int = 0,
    val availableVersion: String? = null,
    val notes: String = "",
    val downloadedVersion: String? = null,
    val lastCheckMillis: Long = 0L,
)

/**
 * Actualizaciones del launcher desde GitHub Releases.
 *
 * Android 10 no permite que una aplicación normal se instale sin confirmación: el modo INSTALL
 * descarga, verifica y abre el diálogo de Android con el coche parado. Cada APK se acepta solo si
 * coincide con su `.sha256`, es este mismo paquete, tiene una versión superior y está firmado con
 * el mismo certificado que la instalación actual.
 */
internal object LauncherUpdateManager {
    private const val PREFERENCES = "launcher_updates"
    private const val KEY_MODE = "mode"
    private const val KEY_NOTIFIED_VERSION = "notified_version"
    private const val KEY_LAST_CHECK = "last_check_millis"
    private const val NOTIFICATION_ID = 420
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val PARKED_BEFORE_INSTALL_MS = 30_000L
    const val ACTION_DOWNLOAD = "com.ts10.inspector.update.DOWNLOAD"
    const val ACTION_INSTALL = "com.ts10.inspector.update.INSTALL"
    const val ACTION_INSTALL_STATUS = "com.ts10.inspector.update.INSTALL_STATUS"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(LauncherUpdateState())
    val state: StateFlow<LauncherUpdateState> = mutableState.asStateFlow()

    private var appContext: Context? = null
    private var latest: GithubRelease? = null
    private var parkedInstallJob: Job? = null

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
            operation.withLock {
                // Se conserva una descarga pendiente solo si sigue siendo válida para esta instalación.
                updatesDirectory(app).listFiles()?.forEach { file ->
                    val valid = isNewerDownload(file) && runCatching { verifyApk(app, file) }.isSuccess
                    if (!valid) {
                        file.delete()
                    } else if (mutableState.value.downloadedVersion == null) {
                        val version = file.name.removePrefix("launcher-").removeSuffix(".apk")
                        mutableState.update { it.copy(downloadedVersion = version, availableVersion = version, status = UiText.of(R.string.upd_ready_version, version)) }
                    }
                }
            }
        }
        LauncherUpdateWorker.schedule(app, mode)
    }

    fun setMode(context: Context, mode: UpdateMode) {
        start(context)
        preferences(context).edit().putString(KEY_MODE, mode.name).apply()
        mutableState.update { it.copy(mode = mode) }
        LauncherUpdateWorker.schedule(context.applicationContext, mode)
        if (mode == UpdateMode.OFF) parkedInstallJob?.cancel()
    }

    fun checkNow(context: Context) {
        start(context)
        scope.launch { runCheck(context.applicationContext, automatic = false) }
    }

    fun download(context: Context) {
        start(context)
        scope.launch {
            operation.withLock { downloadLocked(context.applicationContext) }
            if (mutableState.value.downloadedVersion != null) notifyReady(context.applicationContext)
        }
    }

    /** Comprobación periódica o manual; en los modos automáticos continúa con la descarga. */
    suspend fun runCheck(context: Context, automatic: Boolean) {
        start(context)
        val mode = mutableState.value.mode
        if (automatic && mode == UpdateMode.OFF) return
        operation.withLock {
            setStatus(UiText.of(R.string.upd_checking), busy = true)
            val release = runCatching { GithubReleases.latest(BuildConfig.UPDATE_REPOSITORY) }
                .getOrElse { error ->
                    setStatus(error.uiText(), busy = false)
                    return
                }
            val now = System.currentTimeMillis()
            preferences(context).edit().putLong(KEY_LAST_CHECK, now).apply()
            if (compareVersions(release.version, AppVersion.name) <= 0) {
                latest = null
                mutableState.update {
                    it.copy(status = UiText.of(R.string.upd_up_to_date), busy = false, availableVersion = null, notes = "", lastCheckMillis = now)
                }
                return
            }
            latest = release
            mutableState.update {
                it.copy(
                    status = UiText.of(R.string.upd_version_available, release.version),
                    busy = false,
                    availableVersion = release.version,
                    notes = release.notes.take(1_500),
                    lastCheckMillis = now,
                )
            }
            TelemetryLog.event("UPDATE", "launcher ${release.version} available mode=$mode automatic=$automatic")
            if (!automatic) return
            when (mode) {
                UpdateMode.OFF -> Unit
                UpdateMode.NOTIFY -> notifyAvailable(context, release.version)
                UpdateMode.DOWNLOAD, UpdateMode.INSTALL -> {
                    downloadLocked(context)
                    if (mutableState.value.downloadedVersion == null) return
                    if (mode == UpdateMode.DOWNLOAD) notifyReady(context) else installWhenParked(context)
                }
            }
        }
    }

    /** Acción del usuario: instala lo ya descargado, o descarga primero. */
    fun install(context: Context) {
        start(context)
        val app = context.applicationContext
        scope.launch {
            operation.withLock {
                if (mutableState.value.downloadedVersion == null) downloadLocked(app)
            }
            val file = downloadedFile(app) ?: return@launch
            withContext(Dispatchers.Main) { commitInstall(app, file) }
        }
    }

    fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                setStatus(UiText.of(R.string.upd_confirm_on_screen), busy = false)
                openExclusiveSystemScreen(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> setStatus(UiText.of(R.string.upd_installed), busy = false)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                TelemetryLog.event("UPDATE", "install status=$status message=$message")
                setStatus(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UiText.of(R.string.upd_install_cancelled) else UiText.of(R.string.upd_install_failed, message ?: status.toString()),
                    busy = false,
                )
            }
        }
    }

    private suspend fun downloadLocked(context: Context) {
        val release = latest ?: run {
            setStatus(UiText.of(R.string.upd_check_first), busy = false)
            return
        }
        if (downloadedFile(context)?.name == apkFileName(release.version)) return
        val apk = release.asset { it.endsWith(".apk", ignoreCase = true) }
        val checksum = apk?.let(release::checksumFor)
        if (apk == null || checksum == null) {
            setStatus(UiText.of(R.string.upd_release_missing_apk), busy = false)
            return
        }
        val target = File(updatesDirectory(context), apkFileName(release.version))
        runCatching {
            setStatus(UiText.of(R.string.upd_downloading, release.version), busy = true)
            val expected = parseSha256File(GithubReleases.readText(checksum))
                ?: fail(R.string.upd_sha_unreadable)
            val actual = GithubReleases.download(apk, target, MAX_APK_BYTES) { percent ->
                mutableState.update { it.copy(progressPercent = percent) }
            }
            if (actual != expected) fail(R.string.upd_sha_mismatch)
            verifyApk(context, target)
        }.onSuccess {
            updatesDirectory(context).listFiles()?.filter { it != target }?.forEach(File::delete)
            mutableState.update {
                it.copy(status = UiText.of(R.string.upd_ready_version, release.version), busy = false, downloadedVersion = release.version)
            }
            TelemetryLog.event("UPDATE", "launcher ${release.version} downloaded and verified")
        }.onFailure { error ->
            target.delete()
            TelemetryLog.event("UPDATE", "download failed: ${error.message}")
            setStatus(UiText.of(R.string.upd_download_rejected, error.uiText()), busy = false)
        }
    }

    private fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: fail(R.string.upd_not_apk)
        if (archive.packageName != context.packageName) fail(R.string.upd_other_app)
        if (archive.longVersionCode <= AppVersion.code) fail(R.string.upd_not_newer)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveSigners = signerDigests(archive)
        // Si Android no expone la firma del archivo, la instalación la comprobará igualmente.
        if (archiveSigners != null) {
            if (archiveSigners.intersect(signerDigests(installed).orEmpty()).isEmpty()) fail(R.string.upd_other_certificate)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String>? {
        val signatures = info.signingInfo?.let { signing ->
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } ?: info.signatures
        return signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet()?.takeIf { it.isNotEmpty() }
    }

    private fun installWhenParked(context: Context) {
        if (parkedInstallJob?.isActive == true) return
        parkedInstallJob = scope.launch {
            // Nunca se interrumpe la conducción con el diálogo de instalación.
            while (true) {
                LauncherRuntime.state.first { it.speedKmh == 0 }
                delay(PARKED_BEFORE_INSTALL_MS)
                if (LauncherRuntime.state.value.speedKmh == 0) break
            }
            if (mutableState.value.mode != UpdateMode.INSTALL) return@launch
            val file = downloadedFile(context) ?: return@launch
            withContext(Dispatchers.Main) { commitInstall(context, file) }
        }
    }

    private fun commitInstall(context: Context, file: File) {
        if (LauncherRuntime.state.value.speedKmh > 0) {
            setStatus(UiText.of(R.string.upd_stop_to_install), busy = false)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            setStatus(UiText.of(R.string.upd_allow_installs), busy = false)
            openExclusiveSystemScreen(
                context,
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
            return
        }
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("launcher.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, LauncherUpdateReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
                    flags,
                )
                session.commit(callback.intentSender)
            }
            setStatus(UiText.of(R.string.upd_preparing_install), busy = true)
        }.onFailure { error ->
            setStatus(UiText.of(R.string.upd_install_start_failed, error.uiText()), busy = false)
        }
    }

    private fun notifyAvailable(context: Context, version: String) {
        val preferences = preferences(context)
        if (preferences.getString(KEY_NOTIFIED_VERSION, null) == version) return
        preferences.edit().putString(KEY_NOTIFIED_VERSION, version).apply()
        showUpdateNotification(context, NOTIFICATION_ID, AppLocale.string(context, R.string.update_available_title, version), AppLocale.string(context, R.string.update_available_text), ACTION_INSTALL)
    }

    private fun notifyReady(context: Context) {
        val version = mutableState.value.downloadedVersion ?: return
        showUpdateNotification(context, NOTIFICATION_ID, AppLocale.string(context, R.string.update_ready_title, version), AppLocale.string(context, R.string.update_ready_text), ACTION_INSTALL)
    }

    private fun setStatus(status: UiText, busy: Boolean) =
        mutableState.update { it.copy(status = status, busy = busy, progressPercent = if (busy) it.progressPercent else 0) }

    private fun downloadedFile(context: Context): File? {
        val version = mutableState.value.downloadedVersion ?: return null
        return File(updatesDirectory(context), apkFileName(version)).takeIf(File::isFile)
    }

    private fun isNewerDownload(file: File): Boolean {
        val version = file.name.removePrefix("launcher-").removeSuffix(".apk")
        return file.name.endsWith(".apk") && compareVersions(version, AppVersion.name) > 0
    }

    private fun apkFileName(version: String) = "launcher-$version.apk"

    private fun updatesDirectory(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

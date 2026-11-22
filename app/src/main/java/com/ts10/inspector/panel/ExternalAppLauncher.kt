package com.ts10.inspector.panel

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.ts10.inspector.MainActivity
import com.ts10.inspector.camera.UsbCameraFullScreenActivity
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.launcher.HybridOverlayService
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.settings.DashboardCameraMode
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.DashboardSettings
import java.util.concurrent.atomic.AtomicLong

internal const val EXTRA_CAR_STARTUP_RECOVERY =
    "com.ts10.inspector.extra.CAR_STARTUP_RECOVERY"

/**
 * Launches an external task over the content panel when the device supports freeform windows.
 * Android falls back to a regular full-screen launch when the firmware ignores launch bounds.
 *
 * Validated on the TS10. Keep this flow aligned with docs/TS10_STABLE_HYBRID_PANEL.md.
 */
internal fun launchApplicationInContentArea(
    context: Context,
    packageName: String,
    activityClassName: String? = null,
    forceBoundsRepair: Boolean = false,
    supersedePendingLaunches: Boolean = true,
    allowWazeTaskReset: Boolean = true,
): Boolean {
    if (supersedePendingLaunches) LauncherStartupCoordinator.userTookControl()
    TelemetryLog.event(
        "EXTERNAL_APP",
        "panel launch requested package=$packageName class=${activityClassName ?: "default"} " +
            "forceRepair=$forceBoundsRepair",
    )
    if (supersedePendingLaunches) {
        DeferredLauncherAction.cancel()
        PanelBoundsStabilizer.cancel()
        PanelApplicationSession.cancelPendingRestore()
        PanelApplicationSession.clearInterrupted(context)
    }
    val existingTaskSnapshot = qfTopTaskSnapshot(context, packageName)
    val existingTaskId = existingTaskSnapshot?.taskId
        ?: runCatching { qfTaskId(context, packageName) }.getOrNull()
    val replaceFullscreenWazeTask = shouldReplaceFullscreenWazeTask(
        packageName = packageName,
        windowingMode = existingTaskSnapshot?.windowingMode,
    )
    val resetLegacyWazeLaunch = packageName == WAZE_PACKAGE &&
        !PanelApplicationSession.hasLaunchMigration(context, WAZE_RECONCILER_MIGRATION)
    if (forceBoundsRepair || resetLegacyWazeLaunch) {
        clearQfLaunchParams(packageName)
    }
    if (
        replaceFullscreenWazeTask &&
        allowWazeTaskReset &&
        WazeTaskResetActivity.start(context, existingTaskId, activityClassName, forceBoundsRepair = true)
    ) {
        PanelApplicationSession.remember(context, packageName, activityClassName)
        TelemetryLog.event(
            "EXTERNAL_APP",
            "removing fullscreen Waze task=${existingTaskId ?: "unknown"} before freeform launch",
        )
        return true
    }
    val intent = activityClassName?.let { className ->
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, className))
    } ?: context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
        TelemetryLog.event("EXTERNAL_APP", "panel launch failed: no intent package=$packageName")
        return false
    }
    val contentBounds = runCatching { contentBounds(context) }
        .getOrElse {
            TelemetryLog.event(
                "EXTERNAL_APP",
                "bounds unavailable package=$packageName error=${it.javaClass.simpleName}:${it.message.orEmpty()}",
            )
            return launchApplicationFullScreen(
                context,
                packageName,
                activityClassName = activityClassName,
            )
        }
    val finalBounds = if (packageName == WAZE_PACKAGE) {
        wazeLaunchBounds(contentBounds)
    } else {
        contentBounds
    }
    Log.i(
        TAG_EXTERNAL_APP,
        "Launching $packageName in panel: bounds=$finalBounds existingTask=$existingTaskId",
    )
    LauncherRuntime.recordPanelWindowEvent(
        "$packageName inicio=$finalBounds tareaExistente=${existingTaskId ?: "ninguna"} " +
            "reconciliacion=${packageName in VIEWPORT_SENSITIVE_PACKAGES || forceBoundsRepair}",
    )
    val options = ActivityOptions.makeBasic().apply {
        requestFreeformWindow()
        setLaunchBounds(finalBounds)
    }

    // Match the factory launcher's task flags. In particular, never add MULTIPLE_TASK here:
    // Waze's exported FreeMapAppActivity hands off to a singleTask MainActivity, and creating a
    // second task makes the post-splash activity inherit whichever stale geometry wins the race.
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    if (replaceFullscreenWazeTask) {
        // QF restores Waze as a fullscreen singleTask before HOME starts. Relaunching that task
        // with ActivityOptions does not resize it; CLEAR_TASK is the programmatic equivalent of
        // the working manual close-and-reopen sequence and does not touch Waze application data.
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        LauncherRuntime.recordPanelWindowEvent(
            "$packageName tareaFullscreen=${existingTaskId ?: "desconocida"} reemplazo=clearTask",
        )
        TelemetryLog.event(
            "EXTERNAL_APP",
            "replacing fullscreen Waze task=${existingTaskId ?: "unknown"} before freeform launch",
        )
    }

    fun finishPanelLaunch(route: String): Boolean {
        if (resetLegacyWazeLaunch) {
            PanelApplicationSession.markLaunchMigration(context, WAZE_RECONCILER_MIGRATION)
        }
        PanelApplicationSession.remember(context, packageName, activityClassName)
        PanelBoundsStabilizer.schedule(
            context = context.applicationContext,
            packageName = packageName,
            activityClassName = activityClassName,
            finalBounds = finalBounds,
            forceViewportRefresh = forceBoundsRepair || packageName in VIEWPORT_SENSITIVE_PACKAGES,
        )
        LauncherRuntime.recordPanelWindowEvent("$packageName rutaInicial=$route")
        TelemetryLog.event(
            "EXTERNAL_APP",
            "panel launch success package=$packageName route=$route bounds=$finalBounds " +
                "existingTask=${existingTaskId ?: "none"}",
        )
        return true
    }

    // This is the decisive difference from the previous implementation: if the task exists,
    // reactivate that exact task with bounds, just as the QF launcher does. Starting the exported
    // launcher activity again can otherwise create a splash task which later jumps into Waze's
    // old singleTask MainActivity and restores its obsolete viewport.
    if (
        existingTaskId != null &&
        !replaceFullscreenWazeTask &&
        startExistingTaskFromRecents(
            taskId = existingTaskId,
            packageName = packageName,
            bounds = finalBounds,
        )
    ) {
        return finishPanelLaunch("QFApi:$existingTaskId")
    }

    val freeformStarted = runCatching {
        val bundle = options.toBundle().apply {
            // Android 10 exposes this only as a hidden API, but its Bundle contract is stable.
            putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
        }
        context.startActivity(intent, bundle)
        true
    }.onFailure {
        TelemetryLog.event(
            "EXTERNAL_APP",
            "freeform launch error package=$packageName ${it.javaClass.simpleName}:${it.message.orEmpty()}",
        )
    }.getOrDefault(false)
    if (freeformStarted) {
        return finishPanelLaunch(
            if (replaceFullscreenWazeTask) {
                "ActivityOptions+windowingMode+clearTask"
            } else {
                "ActivityOptions+windowingMode"
            },
        )
    }

    val boundedStarted = runCatching {
        context.startActivity(intent, options.toBundle())
        true
    }.onFailure {
        TelemetryLog.event(
            "EXTERNAL_APP",
            "bounded launch error package=$packageName ${it.javaClass.simpleName}:${it.message.orEmpty()}",
        )
    }.getOrDefault(false)
    if (boundedStarted) {
        return finishPanelLaunch("ActivityOptions")
    }

    return launchApplicationFullScreen(context, packageName, activityClassName = activityClassName).also { launched ->
        TelemetryLog.event(
            "EXTERNAL_APP",
            "panel fallback fullscreen package=$packageName launched=$launched",
        )
        if (launched) PanelApplicationSession.remember(context, packageName, activityClassName)
    }
}

internal fun launchConfiguredApplication(context: Context, packageName: String): Boolean {
    LauncherStartupCoordinator.userTookControl()
    val settings = DashboardPreferences.current(context)
    // The configured dashcam can also have been added to the right-panel list. It must never
    // inherit that list's bounded launch route: camera capture is always a full-screen action.
    if (
        settings.cameraMode == DashboardCameraMode.APPLICATION &&
        packageName == settings.cameraPackage
    ) {
        return launchConfiguredCameraFullScreen(context)
    }
    LauncherRuntime.preserveActiveMediaPlayback(context, packageName)
    return if (shouldLaunchInContentArea(settings, packageName)) {
        launchApplicationInContentArea(context, packageName)
    } else {
        // A delayed bounds correction from the current panel application must never run after
        // the full-screen task has opened, or it would bring the right panel back above it.
        DeferredLauncherAction.cancel()
        PanelBoundsStabilizer.cancel()
        PanelApplicationSession.cancelPendingRestore()
        HybridOverlayService.showTopBarOnly(context)
        DeferredLauncherAction.schedule(OVERLAY_HIDE_DELAY_MS) {
            if (!launchApplicationFullScreen(context, packageName)) {
                PanelApplicationSession.clearInterrupted(context)
                HybridOverlayService.showDashboard(context)
                PanelApplicationSession.restoreForHome(context)
            }
        }
        true
    }
}

internal fun launchConfiguredCameraFullScreen(context: Context): Boolean {
    LauncherStartupCoordinator.userTookControl()
    // Camera is a terminal full-screen action. In particular, cancel Waze's delayed
    // post-splash resize or it can bring Waze back above the recorder a few seconds later.
    DeferredLauncherAction.cancel()
    PanelBoundsStabilizer.cancel()
    PanelApplicationSession.cancelPendingRestore()
    PanelApplicationSession.clearInterrupted(context)

    val settings = DashboardPreferences.current(context)
    val packageName: String
    val activityClassName: String?
    if (settings.cameraMode == DashboardCameraMode.USB) {
        packageName = context.packageName
        activityClassName = UsbCameraFullScreenActivity::class.java.name
    } else {
        packageName = settings.cameraPackage ?: return false
        activityClassName = null
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) return false
    }

    HybridOverlayService.hideForExclusiveScreen(context)
    // The exclusive-screen command marks the current panel task for restoration. That is appropriate for
    // ordinary full-screen tools, but would relaunch a dashcam selected as a panel app inside
    // the bounded content area immediately after this full-screen launch.
    PanelApplicationSession.clearInterrupted(context)
    DeferredLauncherAction.schedule(OVERLAY_HIDE_DELAY_MS) {
        val launched = launchApplicationFullScreen(
            context = context,
            packageName = packageName,
            activityClassName = activityClassName,
            forceFullscreenWindow = true,
        )
        if (!launched) {
            HybridOverlayService.showDashboard(context)
            PanelApplicationSession.restoreForHome(context)
        }
    }
    return true
}

internal fun shouldLaunchInContentArea(settings: DashboardSettings, packageName: String): Boolean =
    packageName == settings.navigationPackage ||
        packageName in settings.panelPackages ||
        packageName == settings.mediaPackage ||
        packageName in settings.mediaPackages

/** Launches a fresh full-screen task underneath the fixed launcher overlay. */
internal fun launchApplicationFullScreen(
    context: Context,
    packageName: String,
    activityClassName: String? = null,
    forceFullscreenWindow: Boolean = false,
): Boolean {
    val component = ComponentName.unflattenFromString(packageName)
    val intent = if (activityClassName != null) {
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, activityClassName))
    } else if (component != null) {
        Intent(Intent.ACTION_MAIN).setComponent(component)
    } else {
        context.packageManager.getLaunchIntentForPackage(packageName)
    } ?: return false
    intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
    )
    if (forceFullscreenWindow) {
        // A reused freeform task keeps its old windowing mode on the TS10 even when fullscreen
        // ActivityOptions are supplied. A separate task is therefore the reliable fallback.
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
    return runCatching {
        if (forceFullscreenWindow) {
            if (activityClassName == null && startExistingTaskFullScreen(context, packageName)) {
                return@runCatching true
            }
            val options = ActivityOptions.makeBasic().apply { requestFullscreenWindow() }
            val bundle = options.toBundle().apply {
                putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FULLSCREEN)
            }
            context.startActivity(intent, bundle)
        } else {
            context.startActivity(intent)
        }
        true
    }.getOrDefault(false)
}

/**
 * A tap on launcher chrome may arrive through the overlay while another task owns focus.
 * Reorder the existing HOME task before handling the tap so controls never depend on the
 * foreground state of an external application.
 */
internal fun bringLauncherToForeground(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(EXTRA_SKIP_PANEL_RESTORE, true),
    )
    true
}.getOrDefault(false)

internal fun recoverLauncherAfterCarStartup(context: Context): Boolean = runCatching {
    if (!isDefaultLauncher(context)) return@runCatching false
    HybridOverlayService.showDashboard(context.applicationContext)
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(EXTRA_CAR_STARTUP_RECOVERY, true),
    )
    true
}.getOrDefault(false)

internal fun launchConfiguredVoiceAssistant(context: Context): Boolean {
    val configuredPackage = DashboardPreferences.current(context).assistantPackage
    val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
        configuredPackage?.let(::setPackage)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val voiceStarted = runCatching {
        if (voiceIntent.resolveActivity(context.packageManager) == null) return@runCatching false
        context.startActivity(voiceIntent)
        true
    }.getOrDefault(false)
    if (voiceStarted) return true
    return configuredPackage?.let { launchApplicationFullScreen(context, it) } ?: false
}

internal fun isDefaultLauncher(context: Context): Boolean {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == context.packageName
}

internal fun openDefaultLauncherSelector(context: Context): Boolean {
    val roleIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        roleManager?.takeIf {
            it.isRoleAvailable(RoleManager.ROLE_HOME) && !it.isRoleHeld(RoleManager.ROLE_HOME)
        }?.createRequestRoleIntent(RoleManager.ROLE_HOME)
    } else {
        null
    }
    // The exported QF selector is the validated route on TS10; Android routes are fallbacks.
    val intents = listOfNotNull(
        Intent(Intent.ACTION_VIEW).setComponent(
            ComponentName("com.qf.carsettings", "com.qf.carsettings.activity.FactoryLauncherSelectActivity"),
        ),
        roleIntent,
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.settings", "com.android.settings.Settings\$HomeSettingsActivity"),
        ),
    )
    return intents.any { intent ->
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            // Keep the dashboard alive if this ROM rejects the requested settings screen.
            HybridOverlayService.hideForExclusiveScreen(context)
            true
        }.getOrDefault(false)
    }
}

internal fun openAndroidSystemSettings(context: Context): Boolean =
    openExclusiveSystemScreen(context, Intent(Settings.ACTION_SETTINGS))

/** Abre una pantalla del sistema retirando antes los overlays, que si no quedarían encima. */
internal fun openExclusiveSystemScreen(context: Context, intent: Intent): Boolean {
    LauncherStartupCoordinator.userTookControl()
    DeferredLauncherAction.cancel()
    PanelBoundsStabilizer.cancel()
    PanelApplicationSession.cancelPendingRestore()
    HybridOverlayService.hideForExclusiveScreen(context)
    DeferredLauncherAction.schedule(OVERLAY_HIDE_DELAY_MS) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!opened) {
            PanelApplicationSession.clearInterrupted(context)
            HybridOverlayService.showDashboard(context)
            PanelApplicationSession.restoreForHome(context)
        }
    }
    return true
}

/**
 * Invalidates delayed boot/ACC actions as soon as the driver explicitly opens something.
 * A generation token avoids an old HOME/Waze callback racing a newer user choice.
 */
internal object LauncherStartupCoordinator {
    private val generation = AtomicLong(0L)

    fun token(): Long = generation.get()

    fun userTookControl() {
        generation.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = generation.get() == token
}

/** Keeps system-owned full-screen flows above HOME until their result is delivered. */
internal object ExclusiveLauncherSession {
    @Volatile
    var active: Boolean = false
        private set

    fun begin(context: Context) {
        LauncherStartupCoordinator.userTookControl()
        active = true
        DeferredLauncherAction.cancel()
        PanelBoundsStabilizer.cancel()
        PanelApplicationSession.cancelPendingRestore()
        HybridOverlayService.hideForExclusiveScreen(context)
        TelemetryLog.event("EXTERNAL_APP", "exclusive system activity started")
    }

    fun complete(context: Context) {
        if (!active) return
        active = false
        HybridOverlayService.showDashboard(context.applicationContext)
        val restored = PanelApplicationSession.restoreIfPending(context.applicationContext)
        TelemetryLog.event(
            "EXTERNAL_APP",
            "exclusive system activity completed panelRestored=$restored",
        )
    }
}

/**
 * Serializes delayed activity launches. Slow TS10 firmware can otherwise execute an older
 * callback after the user has already selected a different destination.
 */
private object DeferredLauncherAction {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var generation = 0L

    fun schedule(delayMillis: Long, action: () -> Unit) {
        cancel()
        val expectedGeneration = generation
        val runnable = Runnable {
            pending = null
            if (generation == expectedGeneration) action()
        }
        pending = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    fun cancel() {
        generation += 1
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}

private const val OVERLAY_HIDE_DELAY_MS = 120L
internal const val WAZE_PACKAGE = "com.waze"
internal const val WAZE_ENTRY_ACTIVITY = "com.waze.FreeMapAppActivity"
internal const val YOUTUBE_PACKAGE = "com.google.android.youtube"
internal const val TAG_EXTERNAL_APP = "TS10ExternalApp"
internal const val EXTRA_SKIP_PANEL_RESTORE = "com.ts10.inspector.extra.SKIP_PANEL_RESTORE"

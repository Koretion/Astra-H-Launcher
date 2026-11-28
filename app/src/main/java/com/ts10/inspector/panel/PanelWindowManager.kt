package com.ts10.inspector.panel

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ts10.inspector.MainActivity
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.DashboardSettings
import com.ts10.inspector.settings.WazePanelGeometry
import com.ts10.inspector.ui.DesignGrid
import kotlin.math.min
import kotlin.math.roundToInt

internal fun contentBounds(context: Context): Rect {
    val windowManager = context.getSystemService(WindowManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // The launcher itself may already occupy a compact/freeform task. Its current metrics
        // are therefore not the physical display dimensions needed by the right-hand panel.
        val metrics = windowManager.maximumWindowMetrics
        val screen = metrics.bounds
        val scale = min(
            screen.width().toFloat() / DesignGrid.Width,
            screen.height().toFloat() / DesignGrid.Height,
        )
        return panelLaunchBounds(screen, scale)
    }

    @Suppress("DEPRECATION")
    val display = (context as? Activity)?.windowManager?.defaultDisplay ?: windowManager.defaultDisplay
    val displayMetrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(displayMetrics)
    val scale = min(
        displayMetrics.widthPixels.toFloat() / DesignGrid.Width,
        displayMetrics.heightPixels.toFloat() / DesignGrid.Height,
    )
    return panelLaunchBounds(
        Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels),
        scale,
    )
}

/**
 * QF's Android 10 freeform policy keeps a caption/status row above every application task.
 * Supplying the complete display height therefore translates a 720 px task to y=72..792 on
 * the TS10 and clips its bottom controls. Reserve that row in the requested height; QF then
 * settles the task at y=72..720 while the launcher indicators occupy the row above it.
 */
internal fun panelLaunchBounds(screen: Rect, scale: Float): Rect {
    val coordinates = panelLaunchCoordinates(
        screenLeft = screen.left,
        screenTop = screen.top,
        screenRight = screen.right,
        screenBottom = screen.bottom,
        scale = scale,
    )
    return Rect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
}

internal fun panelLaunchCoordinates(
    screenLeft: Int,
    screenTop: Int,
    screenRight: Int,
    screenBottom: Int,
    scale: Float,
): List<Int> {
    // Measured from the QF task manager on the 1280x720 unit: a freeform task requested at
    // y=0 is settled at y=72. Scale that firmware inset with the physical display height.
    val freeformTopInset = (
        (screenBottom - screenTop).toFloat() *
            QF_FREEFORM_TOP_INSET_BASE / QF_BASE_DISPLAY_HEIGHT
        ).roundToInt()
    return listOf(
        screenLeft + (DesignGrid.VehiclePanelWidth * scale).roundToInt(),
        screenTop,
        screenRight,
        (screenBottom - freeformTopInset).coerceAtLeast(screenTop + 1),
    )
}

/** Keeps Waze's proven 605 px aspect and lets QF settle it against the display bottom. */
internal fun wazeLaunchCoordinates(panelBounds: List<Int>): List<Int> {
    if (panelBounds.size != 4) return panelBounds
    val panelHeight = panelBounds[3] - panelBounds[1]
    val hiddenNavigationInset = (
        panelHeight.toFloat() * WAZE_HIDDEN_NAVIGATION_INSET_BASE /
            (QF_BASE_DISPLAY_HEIGHT - QF_FREEFORM_TOP_INSET_BASE)
        ).roundToInt()
    return listOf(
        panelBounds[0],
        panelBounds[1] + hiddenNavigationInset,
        panelBounds[2],
        panelBounds[3],
    )
}

/** Rectángulo de Waze según la geometría de prueba elegida en Ajustes › Avanzado. */
internal fun wazeGeometryCoordinates(panelBounds: List<Int>, geometry: WazePanelGeometry): List<Int> {
    if (panelBounds.size != 4) return panelBounds
    val extra = ((panelBounds[2] - panelBounds[0]) * WAZE_WIDER_FRACTION).roundToInt()
    val base = when (geometry) {
        WazePanelGeometry.CURRENT, WazePanelGeometry.WIDER -> wazeLaunchCoordinates(panelBounds)
        WazePanelGeometry.FULL_HEIGHT, WazePanelGeometry.WIDER_FULL_HEIGHT -> panelBounds
    }
    val wider = geometry == WazePanelGeometry.WIDER || geometry == WazePanelGeometry.WIDER_FULL_HEIGHT
    return if (wider) listOf(base[0] - extra, base[1], base[2], base[3]) else base
}

private const val WAZE_WIDER_FRACTION = 0.05f

internal fun wazeLaunchBounds(panelBounds: Rect): Rect =
    wazeLaunchCoordinates(
        listOf(panelBounds.left, panelBounds.top, panelBounds.right, panelBounds.bottom),
    ).toRect()

/** Keeps the logical right-panel task across temporary full-screen system activities. */
internal object PanelApplicationSession {
    private val restoreHandler = Handler(Looper.getMainLooper())
    private var pendingRestore: Runnable? = null
    private var lastAutomaticRestorePackage: String? = null
    private var lastAutomaticRestoreElapsedMs = 0L

    /**
     * Aplica al momento la geometría de prueba de Waze: si Waze es la app del panel y su tarea
     * sigue abierta, se reabre esa misma tarea con el rectángulo nuevo (no se reinicia ni pierde
     * la ruta). Devuelve false si Waze no está en el panel; entonces se aplicará al abrirlo.
     */
    fun applyWazeGeometryNow(context: Context): Boolean {
        if (preferences(context).getString(KEY_LAST_PACKAGE, null) != WAZE_PACKAGE) return false
        if (runCatching { qfTaskId(context, WAZE_PACKAGE) }.getOrNull() == null) return false
        TelemetryLog.event("EXTERNAL_APP", "waze geometry applied live")
        return launchApplicationInContentArea(
            context,
            WAZE_PACKAGE,
            activityClassName = preferences(context).getString(KEY_LAST_ACTIVITY, null),
            forceBoundsRepair = true,
            allowWazeTaskReset = false,
        )
    }

    fun remember(context: Context, packageName: String, activityClassName: String? = null) {
        preferences(context).edit()
            .putString(KEY_LAST_PACKAGE, packageName)
            .apply {
                if (activityClassName == null) remove(KEY_LAST_ACTIVITY)
                else putString(KEY_LAST_ACTIVITY, activityClassName)
            }
            .apply()
    }

    fun hasLaunchMigration(context: Context, migration: String): Boolean =
        migration in preferences(context).getStringSet(KEY_LAUNCH_MIGRATIONS, emptySet()).orEmpty()

    fun markLaunchMigration(context: Context, migration: String) {
        val preferences = preferences(context)
        val migrations = preferences.getStringSet(KEY_LAUNCH_MIGRATIONS, emptySet()).orEmpty().toMutableSet()
        if (migrations.add(migration)) {
            preferences.edit().putStringSet(KEY_LAUNCH_MIGRATIONS, migrations).apply()
        }
    }

    fun markInterrupted(context: Context) {
        if (preferences(context).getString(KEY_LAST_PACKAGE, null) != null) {
            preferences(context).edit().putBoolean(KEY_RESTORE_PENDING, true).apply()
        }
    }

    fun clearInterrupted(context: Context) {
        preferences(context).edit().putBoolean(KEY_RESTORE_PENDING, false).apply()
    }

    fun isRestorePending(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RESTORE_PENDING, false)

    fun cancelPendingRestore() {
        pendingRestore?.let(restoreHandler::removeCallbacks)
        pendingRestore = null
        restoreScheduled = false
    }

    fun restoreIfPending(context: Context): Boolean {
        if (!isRestorePending(context)) return false
        return restoreRememberedApplication(context)
    }

    /** Restores the exact logical right-panel task on HOME/BACK, with navigation as fallback. */
    fun restoreForHome(context: Context): Boolean {
        val preferences = preferences(context)
        val settings = DashboardPreferences.current(context)
        val rememberedPackage = preferences.getString(KEY_LAST_PACKAGE, null)
        val packageName = rememberedPackage
            ?.takeIf { isRestorablePanelPackage(settings, it) }
            ?: settings.navigationPackage
        val activityClassName = preferences.getString(KEY_LAST_ACTIVITY, null)
            .takeIf { packageName == rememberedPackage }
        return restorePackage(context, packageName, activityClassName)
    }

    /**
     * Repairs the visible HOME placeholder without reviving the last media application.
     *
     * QF can move the bounded application to the background during an application switch while
     * leaving this launcher's right-hand placeholder exposed. A configured panel task at rank 0
     * is still covering that placeholder and must be left alone. If every configured task is in
     * the background (or gone), navigation is the deterministic default requested for the panel.
     */
    fun restoreNavigationIfPanelEmpty(context: Context): Boolean {
        if (restoreScheduled) return true
        panelAutomaticRestoreBlockReason(context)?.let { reason ->
            TelemetryLog.event("EXTERNAL_APP", "panel restore blocked: $reason")
            return false
        }
        val settings = DashboardPreferences.current(context)
        val panelPackages = buildList {
            add(settings.navigationPackage)
            addAll(settings.panelPackages)
            settings.mediaPackage?.let(::add)
            addAll(settings.mediaPackages)
        }.distinct()
        val taskRanks = panelPackages.map { packageName ->
            qfTopTaskSnapshot(context, packageName)?.rank
        }
        if (!shouldRestoreDefaultNavigation(taskRanks)) return false
        val preferences = preferences(context)
        val rememberedPackage = preferences.getString(KEY_LAST_PACKAGE, null)
            ?.takeIf { isRestorablePanelPackage(settings, it) }
        val targetPackage = selectPanelRestoreTarget(rememberedPackage, settings.navigationPackage)
        val targetActivity = preferences.getString(KEY_LAST_ACTIVITY, null)
            .takeIf { targetPackage == rememberedPackage }
        TelemetryLog.event(
            "EXTERNAL_APP",
            "right panel empty; restoring owner=$targetPackage " +
                "fallbackNavigation=${rememberedPackage == null}",
        )
        return restorePackage(context, targetPackage, targetActivity)
    }

    /** Null means this ROM did not expose its task list, so an automatic launch is not safe. */
    fun isConfiguredPanelEmpty(context: Context): Boolean? {
        if (panelAutomaticRestoreBlockReason(context) != null) return false
        val settings = DashboardPreferences.current(context)
        val panelPackages = buildList {
            add(settings.navigationPackage)
            addAll(settings.panelPackages)
            settings.mediaPackage?.let(::add)
            addAll(settings.mediaPackages)
        }.distinct()
        val runningPackages = qfRunningTaskPackages(context) ?: return null
        return panelPackages.none(runningPackages::contains)
    }

    private fun restoreRememberedApplication(context: Context): Boolean {
        if (restoreScheduled) return true
        val preferences = preferences(context)
        val packageName = preferences.getString(KEY_LAST_PACKAGE, null) ?: return false
        val activityClassName = preferences.getString(KEY_LAST_ACTIVITY, null)
        return restorePackage(context, packageName, activityClassName)
    }

    private fun restorePackage(
        context: Context,
        packageName: String,
        activityClassName: String?,
    ): Boolean {
        if (restoreScheduled) return true
        val now = SystemClock.elapsedRealtime()
        if (
            packageName == lastAutomaticRestorePackage &&
            now - lastAutomaticRestoreElapsedMs < PANEL_AUTOMATIC_RESTORE_COOLDOWN_MS
        ) {
            TelemetryLog.event(
                "EXTERNAL_APP",
                "automatic restore suppressed during cooldown package=$packageName",
            )
            return true
        }
        val settings = DashboardPreferences.current(context)
        if (!isRestorablePanelPackage(settings, packageName)) {
            return false
        }
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) return false

        restoreScheduled = true
        val restore = Runnable {
            pendingRestore = null
            try {
                val restored = launchApplicationInContentArea(
                    context.applicationContext,
                    packageName,
                    activityClassName = activityClassName,
                    // On ACC/boot the firmware can restore Waze's old full-screen launch
                    // parameters after HOME has started. An automatic restore must clear that
                    // stale geometry just like the manual close-and-reopen sequence does.
                    forceBoundsRepair = packageName == WAZE_PACKAGE,
                    supersedePendingLaunches = false,
                )
                if (restored) {
                    lastAutomaticRestorePackage = packageName
                    lastAutomaticRestoreElapsedMs = SystemClock.elapsedRealtime()
                    clearInterrupted(context)
                }
            } finally {
                restoreScheduled = false
            }
        }
        pendingRestore = restore
        restoreHandler.postDelayed(restore, PANEL_RESTORE_DELAY_MS)
        return true
    }

    private fun isRestorablePanelPackage(settings: DashboardSettings, packageName: String): Boolean {
        if (packageName == settings.navigationPackage) return true
        if (packageName in settings.panelPackages) return true
        if (packageName == settings.mediaPackage) return true
        if (packageName in settings.mediaPackages) return true
        return false
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private const val FILE_NAME = "panel_application_session"
    private const val KEY_LAST_PACKAGE = "last_package"
    private const val KEY_LAST_ACTIVITY = "last_activity"
    private const val KEY_RESTORE_PENDING = "restore_pending"
    private const val KEY_LAUNCH_MIGRATIONS = "launch_migrations"
    private const val PANEL_RESTORE_DELAY_MS = 280L
    private const val PANEL_AUTOMATIC_RESTORE_COOLDOWN_MS = 20_000L
    private var restoreScheduled = false
}

/** A rank-zero configured task is already the visible right-panel application. */
internal fun shouldRestoreDefaultNavigation(configuredTaskRanks: List<Int?>): Boolean =
    configuredTaskRanks.none { it == 0 }

/**
 * Supervises the right-panel task for its complete foreground session.
 *
 * A launch-only timer is insufficient: applications can replace their activity after a splash,
 * change WindowConfiguration, enter an immersive video mode, or ask Android to alter their task
 * long after startup. The supervisor therefore uses fast polling while the task is changing and
 * a low-frequency heartbeat once stable. Exact bounds are cheap to reassert; the visible
 * short/full geometry pulse is reserved for real activity or configuration transitions.
 */
internal object PanelBoundsStabilizer {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<Runnable>()
    private var generation = 0L

    fun schedule(
        context: Context,
        packageName: String,
        activityClassName: String?,
        finalBounds: Rect,
        forceViewportRefresh: Boolean,
    ) {
        cancel()
        val expectedGeneration = generation
        val stableBounds = Rect(finalBounds)
        var commandBounds = Rect(stableBounds)
        val startedAt = SystemClock.elapsedRealtime()
        val startedAtWallClock = System.currentTimeMillis()
        var previousIdentity: String? = null
        var previousConfigurationSignature: String? = null
        var lastIdentityChangeAt = startedAt
        var suppressConfigurationUntil = 0L
        var refreshInFlight = false
        var fallbackWave = 0
        var backgroundObservedAt: Long? = null
        var steadyIdentityLogged: String? = null
        var active = true
        val repairBackoff = PanelRepairBackoff()

        fun post(delayMillis: Long, action: () -> Unit) {
            lateinit var runnable: Runnable
            runnable = Runnable {
                pending.remove(runnable)
                if (generation != expectedGeneration || !active) return@Runnable
                action()
            }
            pending += runnable
            handler.postDelayed(runnable, delayMillis)
        }

        fun finish(message: String) {
            LauncherRuntime.recordPanelWindowEvent(message)
            active = false
            pending.forEach(handler::removeCallbacks)
            pending.clear()
        }

        fun recordAdjustment(
            reason: String,
            taskId: Int?,
            bounds: Rect,
            route: String?,
        ) {
            LauncherRuntime.recordPanelWindowEvent(
                "$packageName motivo=$reason tarea=${taskId ?: "desconocida"} " +
                    "ajuste=$bounds ruta=${route ?: "fallo"}",
            )
        }

        fun applyExactOrFallback(taskId: Int?, bounds: Rect): String? {
            if (
                taskId != null &&
                startExistingTaskFromRecents(
                    taskId = taskId,
                    packageName = packageName,
                    bounds = bounds,
                )
            ) {
                return "QFApi:$taskId"
            }
            return applyPanelBounds(
                context = context,
                packageName = packageName,
                activityClassName = activityClassName,
                bounds = bounds,
            )
        }

        fun adaptCommandBounds(snapshot: PanelTaskSnapshot?) {
            if (packageName != WAZE_PACKAGE || snapshot?.bounds == null) return
            val adapted = wazeAdaptiveCommandCoordinates(
                expected = stableBounds.toCoordinates(),
                current = commandBounds.toCoordinates(),
                observed = snapshot.bounds.toCoordinates(),
            )
            commandBounds = adapted.toRect()
        }

        fun refreshViewport(snapshot: PanelTaskSnapshot?, reason: String) {
            if (refreshInFlight) return
            refreshInFlight = true
            adaptCommandBounds(snapshot)
            val targetTaskId = snapshot?.taskId
            val primingBounds = panelPrimingBounds(commandBounds)
            val kickRoute = applyExactOrFallback(targetTaskId, primingBounds)
            suppressConfigurationUntil =
                SystemClock.elapsedRealtime() + PANEL_OWN_RESIZE_SUPPRESS_MS
            recordAdjustment(reason, targetTaskId, primingBounds, kickRoute)

            post(PANEL_VIEWPORT_PULSE_MS) {
                // If Waze moved from its entry task into a different MainActivity task while the
                // pulse was open, never bring the obsolete task back to the foreground. The next
                // poll will reconcile the new task instead.
                val current = qfTopTaskSnapshot(context, packageName)
                if (
                    targetTaskId != null &&
                    current != null &&
                    current.taskId != targetTaskId
                ) {
                    refreshInFlight = false
                    previousIdentity = null
                    LauncherRuntime.recordPanelWindowEvent(
                        "$packageName tareaCambio=$targetTaskId->${current.taskId}; " +
                            "discarding the final bounds of the previous task",
                    )
                    return@post
                }

                val finalTaskId = current?.taskId ?: targetTaskId
                adaptCommandBounds(current)
                val finalRoute = applyExactOrFallback(finalTaskId, commandBounds)
                refreshInFlight = false
                suppressConfigurationUntil =
                    SystemClock.elapsedRealtime() + PANEL_OWN_RESIZE_SUPPRESS_MS
                recordAdjustment("$reason:final", finalTaskId, commandBounds, finalRoute)
            }
        }

        fun scheduleSettledViewportVerifications(
            expectedIdentity: String,
            activityName: String?,
        ) {
            if (activityName == WAZE_ENTRY_ACTIVITY) return
            PANEL_POST_ACTIVITY_VERIFY_MS.forEachIndexed { index, delayMillis ->
                post(delayMillis) {
                    val current = qfTopTaskSnapshot(context, packageName)
                    val currentIdentity = current?.identity ?: latestForegroundActivity(
                        context = context,
                        packageName = packageName,
                        sinceWallClock = startedAtWallClock - PANEL_USAGE_LOOKBACK_MS,
                    )?.let { "usage:$it" }
                    if (
                        currentIdentity != expectedIdentity ||
                        current?.rank?.let { it > 0 } == true
                    ) {
                        return@post
                    }
                    refreshViewport(
                        snapshot = current,
                        reason = "asentamiento:${index + 1}:${activityName ?: "actividad"}",
                    )
                }
            }
        }

        fun reassertBounds(snapshot: PanelTaskSnapshot?, reason: String) {
            if (reason.startsWith("deriva:") && !repairBackoff.allow(
                    "${snapshot?.identity}:${snapshot?.windowingMode}:${snapshot?.bounds}",
                    SystemClock.elapsedRealtime(),
                )) return
            val taskId = snapshot?.taskId
            // Redimensionar no saca una tarea de la pila fullscreen; hay que recrearla.
            if (
                shouldReplaceFullscreenWazeTask(packageName, snapshot?.windowingMode) &&
                WazeTaskResetActivity.start(context, taskId, activityClassName, forceBoundsRepair = true)
            ) {
                finish("$packageName reconciliacion=recreandoTarea motivo=$reason")
                return
            }
            adaptCommandBounds(snapshot)
            val route = applyExactOrFallback(taskId, commandBounds)
            recordAdjustment(reason, taskId, commandBounds, route)
        }

        lateinit var poll: () -> Unit
        poll = poll@{
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - startedAt

            val snapshot = qfTopTaskSnapshot(context, packageName)
            if (snapshot != null) {
                // A different foreground task means the user has left this panel application by
                // a route the launcher did not initiate. Do not steal focus by repairing it.
                if (snapshot.rank > 0) {
                    val backgroundSince = backgroundObservedAt ?: now.also {
                        backgroundObservedAt = it
                    }
                    if (now - backgroundSince >= PANEL_BACKGROUND_CANCEL_MS) {
                        finish(
                            "$packageName reconciliacion=cancelada tareaEnSegundoPlano",
                        )
                        return@poll
                    }
                    post(PANEL_RECONCILE_BACKGROUND_POLL_MS) { poll() }
                    return@poll
                }
                backgroundObservedAt = null

                val firstObservation = previousIdentity == null
                val identityChanged = snapshot.identity != previousIdentity
                val configurationChanged =
                    previousConfigurationSignature != null &&
                        snapshot.configurationSignature != null &&
                        snapshot.configurationSignature != previousConfigurationSignature
                val correctionSettled =
                    !refreshInFlight && now >= suppressConfigurationUntil
                val action = choosePanelWindowAction(
                    firstObservation = firstObservation,
                    identityChanged = identityChanged,
                    configurationChanged = configurationChanged,
                    wrongGeometry = snapshot.hasWrongGeometry(stableBounds, packageName),
                    correctionSettled = correctionSettled,
                    heartbeatDue = false,
                    eagerFirstObservation = forceViewportRefresh,
                )

                if (identityChanged) {
                    lastIdentityChangeAt = now
                    steadyIdentityLogged = null
                }
                if (identityChanged && refreshInFlight) {
                    // Do not consume a transition while the preceding pulse is still open.
                    // Observing it again after the final bounds are applied guarantees that the
                    // new activity receives its own resize notification.
                    previousIdentity = null
                } else {
                    previousIdentity = snapshot.identity
                }
                previousConfigurationSignature = snapshot.configurationSignature

                when (action) {
                    PanelWindowAction.REFRESH_VIEWPORT -> {
                        refreshViewport(
                            snapshot,
                            when {
                                identityChanged ->
                                    "transicion:${snapshot.topActivity ?: snapshot.baseActivity ?: "actividad"}"
                                else ->
                                    "configuracion:appBounds=${snapshot.appBounds}"
                            },
                        )
                        if (forceViewportRefresh && identityChanged) {
                            scheduleSettledViewportVerifications(
                                expectedIdentity = snapshot.identity,
                                activityName = snapshot.topActivity ?: snapshot.baseActivity,
                            )
                        }
                    }

                    PanelWindowAction.REASSERT_BOUNDS -> {
                        reassertBounds(
                            snapshot,
                            when {
                                snapshot.hasWrongGeometry(stableBounds, packageName) ->
                                    "deriva:modo=${snapshot.windowingMode},bounds=${snapshot.bounds}"
                                identityChanged -> "transicionInicial"
                                else -> "latido"
                            },
                        )
                    }

                    PanelWindowAction.NONE -> {
                        if (!snapshot.hasWrongGeometry(stableBounds, packageName)) repairBackoff.reset()
                    }
                }

                val quietFor = now - lastIdentityChangeAt
                if (
                    steadyIdentityLogged != snapshot.identity &&
                    !refreshInFlight &&
                    quietFor >= PANEL_SUPERVISOR_QUIET_MS &&
                    !snapshot.hasWrongGeometry(stableBounds, packageName)
                ) {
                    steadyIdentityLogged = snapshot.identity
                    LauncherRuntime.recordPanelWindowEvent(
                        "$packageName supervisor=activo tarea=${snapshot.taskId} " +
                            "actividad=${snapshot.topActivity} bounds=${snapshot.bounds} " +
                            "appBounds=${snapshot.appBounds}",
                    )
                }
            } else {
                // QFApi task snapshots are the primary signal on the TS10. Usage events provide
                // an independent, bounded startup fallback when this ROM restricts task data.
                val usageActivity = latestForegroundActivity(
                    context = context,
                    packageName = packageName,
                    sinceWallClock = startedAtWallClock - PANEL_USAGE_LOOKBACK_MS,
                )
                val usageIdentity = usageActivity?.let { "usage:$it" }
                if (usageIdentity != null && usageIdentity != previousIdentity) {
                    val firstObservation = previousIdentity == null
                    previousIdentity = usageIdentity
                    lastIdentityChangeAt = now
                    if (forceViewportRefresh || !firstObservation) {
                        refreshViewport(snapshot = null, reason = "actividad:$usageActivity")
                        if (forceViewportRefresh) {
                            scheduleSettledViewportVerifications(
                                expectedIdentity = usageIdentity,
                                activityName = usageActivity,
                            )
                        }
                    } else {
                        reassertBounds(snapshot = null, reason = "actividad:$usageActivity")
                    }
                } else if (
                    fallbackWave < PANEL_FALLBACK_REASSERT_MS.size &&
                    elapsed >= PANEL_FALLBACK_REASSERT_MS[fallbackWave] &&
                    !refreshInFlight
                ) {
                    val wave = fallbackWave + 1
                    fallbackWave += 1
                    if (forceViewportRefresh && fallbackWave < MAX_FALLBACK_VIEWPORT_PULSES) {
                        refreshViewport(snapshot = null, reason = "respaldo:$wave")
                    } else {
                        reassertBounds(snapshot = null, reason = "respaldo:$wave")
                    }
                }
                if (elapsed >= PANEL_FALLBACK_MONITOR_MAX_MS && !refreshInFlight) {
                    finish(
                        "$packageName supervisor=fin sinDatosDeTarea actividad=$previousIdentity",
                    )
                    return@poll
                }
            }

            val quietFor = now - lastIdentityChangeAt
            val nextPollDelay = if (
                refreshInFlight ||
                elapsed < PANEL_SUPERVISOR_STARTUP_MS ||
                quietFor < PANEL_SUPERVISOR_QUIET_MS
            ) {
                PANEL_RECONCILE_POLL_MS
            } else {
                PANEL_SUPERVISOR_STEADY_POLL_MS
            }
            post(nextPollDelay) { poll() }
        }

        post(PANEL_RECONCILE_INITIAL_DELAY_MS) { poll() }
    }

    private fun applyPanelBounds(
        context: Context,
        packageName: String,
        activityClassName: String?,
        bounds: Rect,
    ): String? {
        if (startExistingTaskFromRecents(context, packageName, bounds)) {
            return "QFApi"
        }
        if (
            relaunchExistingTaskWithBounds(
                context,
                packageName,
                activityClassName,
                bounds,
            )
        ) {
            return "ActivityOptions"
        }
        return null
    }

    fun cancel() {
        generation += 1
        pending.forEach(handler::removeCallbacks)
        pending.clear()
    }
}

/** Creates a real configuration delta so viewport-sensitive activities cannot ignore the resize. */
private fun panelPrimingBounds(finalBounds: Rect): Rect {
    val resizeStep = wazeSystemBarHeight(finalBounds)
    return Rect(finalBounds).apply {
        bottom = (bottom - resizeStep).coerceAtLeast(top + 1)
    }
}

private fun wazeSystemBarHeight(bounds: Rect): Int = (
    bounds.height().toFloat() *
        DesignGrid.SystemBarHeight /
        DesignGrid.Height
    ).roundToInt().coerceAtLeast(1)

internal enum class PanelWindowAction {
    NONE,
    REASSERT_BOUNDS,
    REFRESH_VIEWPORT,
}

/** QF may accept a launch but ignore its bounds; stop touching an unchanged task after three tries. */
internal class PanelRepairBackoff {
    private var geometry: String? = null
    private var attempts = 0

    fun allow(observedGeometry: String, @Suppress("UNUSED_PARAMETER") nowMs: Long): Boolean {
        if (observedGeometry != geometry) {
            reset()
            geometry = observedGeometry
        }
        if (attempts >= 3) return false
        attempts++
        return true
    }

    fun reset() {
        geometry = null
        attempts = 0
    }
}

internal fun shouldReplaceFullscreenWazeTask(
    packageName: String,
    windowingMode: Int?,
): Boolean = packageName == WAZE_PACKAGE && windowingMode == WINDOWING_MODE_FULLSCREEN

/**
 * Keeps the policy independently testable. Stable tasks are deliberately left alone; a real
 * short/full resize is used only when an activity or its effective configuration changed.
 */
internal fun choosePanelWindowAction(
    firstObservation: Boolean,
    identityChanged: Boolean,
    configurationChanged: Boolean,
    wrongGeometry: Boolean,
    correctionSettled: Boolean,
    heartbeatDue: Boolean,
    eagerFirstObservation: Boolean,
): PanelWindowAction = when {
    // A stale task restored by the TS10 can already be freeform yet expose a clipped viewport.
    // Reasserting the same bounds does not make Waze recalculate its content (the 0.3.2 logs
    // show the same bottom=717 settlement every three seconds). The eager startup path needs
    // one real configuration delta before applying the final bounds.
    correctionSettled && firstObservation && wrongGeometry && eagerFirstObservation ->
        PanelWindowAction.REFRESH_VIEWPORT
    correctionSettled && wrongGeometry -> PanelWindowAction.REASSERT_BOUNDS
    correctionSettled && identityChanged && (!firstObservation || eagerFirstObservation) ->
        PanelWindowAction.REFRESH_VIEWPORT
    correctionSettled && identityChanged -> PanelWindowAction.REASSERT_BOUNDS
    correctionSettled && configurationChanged -> PanelWindowAction.REFRESH_VIEWPORT
    correctionSettled && heartbeatDue -> PanelWindowAction.REASSERT_BOUNDS
    else -> PanelWindowAction.NONE
}

/**
 * The QF policy may translate a requested freeform rectangle vertically to make room for its
 * system row. That translation is correct as long as the task keeps the requested width and
 * height and the horizontal panel edges. A size change or horizontal drift remains an error.
 */
internal fun panelTaskBoundsMatch(expected: Rect, observed: Rect, packageName: String): Boolean =
    if (packageName == WAZE_PACKAGE) {
        panelWazeTaskBoundsMatch(
            expected = listOf(expected.left, expected.top, expected.right, expected.bottom),
            observed = listOf(observed.left, observed.top, observed.right, observed.bottom),
        )
    } else panelTaskBoundsMatch(
        expected = listOf(expected.left, expected.top, expected.right, expected.bottom),
        observed = listOf(observed.left, observed.top, observed.right, observed.bottom),
    )

internal fun panelTaskBoundsMatch(expected: List<Int>, observed: List<Int>): Boolean =
    if (expected.size != 4 || observed.size != 4) {
        false
    } else {
        val expectedHeight = expected[3] - expected[1]
        val observedVerticalOffset = observed[1] - expected[1]
        val qfVerticalOffset = (
            expectedHeight.toFloat() * QF_FREEFORM_TOP_INSET_BASE /
                (QF_BASE_DISPLAY_HEIGHT - QF_FREEFORM_TOP_INSET_BASE)
            ).roundToInt()
        expected[0] == observed[0] &&
            expected[2] == observed[2] &&
            expected[2] - expected[0] == observed[2] - observed[0] &&
            expectedHeight == observed[3] - observed[1] &&
            (observedVerticalOffset == 0 || observedVerticalOffset == qfVerticalOffset)
    }

/**
 * Accepts either the full-height QF settlement or a pure translation that preserves the requested
 * panel size. The latter is the stable 473,72-1280,677 geometry observed on the current firmware;
 * rejecting it caused an endless task-reactivation loop even though no content was clipped.
 */
internal fun panelWazeTaskBoundsMatch(expected: List<Int>, observed: List<Int>): Boolean {
    if (expected.size != 4 || observed.size != 4) return false
    val displayScale = expected[2].toFloat() / QF_BASE_DISPLAY_WIDTH
    val qfVerticalOffset = (QF_FREEFORM_TOP_INSET_BASE * displayScale).roundToInt()
    val physicalDisplayBottom = (QF_BASE_DISPLAY_HEIGHT * displayScale).roundToInt()
    val fillsVisiblePanel =
        kotlin.math.abs(observed[3] - physicalDisplayBottom) <= PANEL_WAZE_BOTTOM_TOLERANCE_PX
    val preservesRequestedSize =
        expected[2] - expected[0] == observed[2] - observed[0] &&
            expected[3] - expected[1] == observed[3] - observed[1] &&
            observed[1] >= 0 && observed[3] <= physicalDisplayBottom
    return expected[0] == observed[0] &&
        expected[2] == observed[2] &&
        (preservesRequestedSize ||
            (observed[1] - expected[1] == qfVerticalOffset && fillsVisiblePanel))
}

/** Falls back to the desired compensated command until QF reports the physical bottom. */
internal fun wazeAdaptiveCommandCoordinates(
    expected: List<Int>,
    current: List<Int>,
    observed: List<Int>,
): List<Int> {
    if (expected.size != 4 || current.size != 4 || observed.size != 4) return current
    if (expected[0] != observed[0] || expected[2] != observed[2]) return current

    return if (panelWazeTaskBoundsMatch(expected, observed)) current else expected
}

private fun Rect.toCoordinates(): List<Int> = listOf(left, top, right, bottom)

/** Never place a panel task over an external app intentionally occupying the whole display. */
internal fun panelAutomaticRestoreBlockReason(context: Context): String? = runCatching {
    val tasks = qfRunningTaskSnapshots(context)
        ?: return@runCatching "foreground task inspection unavailable"
    val task = tasks.firstOrNull() ?: return@runCatching null
    val packageName = task.packageName ?: return@runCatching null
    if (shouldBlockPanelRestore(packageName, context.packageName, task.windowingMode)) {
        "foreground fullscreen package=$packageName task=${task.taskId}"
    } else null
}.getOrNull()

internal fun selectPanelRestoreTarget(rememberedPackage: String?, navigationPackage: String): String =
    rememberedPackage ?: navigationPackage

internal fun shouldBlockPanelRestore(
    foregroundPackage: String?,
    launcherPackage: String,
    windowingMode: Int?,
): Boolean = foregroundPackage != null &&
    foregroundPackage != launcherPackage &&
    foregroundPackage != "com.android.systemui" &&
    windowingMode == WINDOWING_MODE_FULLSCREEN

internal const val WAZE_RECONCILER_MIGRATION = "panel_session_supervisor_v10"
private const val QF_BASE_DISPLAY_HEIGHT = 720f
private const val QF_BASE_DISPLAY_WIDTH = 1280f
private const val QF_FREEFORM_TOP_INSET_BASE = 72f
private const val WAZE_HIDDEN_NAVIGATION_INSET_BASE = 43f
private const val PANEL_RECONCILE_INITIAL_DELAY_MS = 180L
private const val PANEL_RECONCILE_POLL_MS = 400L
private const val PANEL_SUPERVISOR_STEADY_POLL_MS = 3_000L
private const val PANEL_RECONCILE_BACKGROUND_POLL_MS = 2_500L
private const val PANEL_VIEWPORT_PULSE_MS = 260L
private const val PANEL_OWN_RESIZE_SUPPRESS_MS = 1_100L
private val PANEL_POST_ACTIVITY_VERIFY_MS = longArrayOf(1_800L, 5_200L)
private const val PANEL_SUPERVISOR_STARTUP_MS = 12_000L
private const val PANEL_SUPERVISOR_QUIET_MS = 4_000L
private const val PANEL_WAZE_BOTTOM_TOLERANCE_PX = 4
private const val PANEL_FALLBACK_MONITOR_MAX_MS = 60_000L
private const val PANEL_BACKGROUND_CANCEL_MS = 30_000L
private const val PANEL_USAGE_LOOKBACK_MS = 1_500L
private val PANEL_FALLBACK_REASSERT_MS = longArrayOf(900L, 6_000L, 15_000L, 30_000L, 45_000L)
private const val MAX_FALLBACK_VIEWPORT_PULSES = 2
internal val VIEWPORT_SENSITIVE_PACKAGES = setOf(WAZE_PACKAGE, YOUTUBE_PACKAGE)

package com.ts10.inspector.panel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.launcher.LauncherRuntime

/**
 * Sin root no se puede cambiar el modo de ventana de una tarea ajena: QF rechaza
 * startActivityFromRecents y CLEAR_TASK reutiliza la misma tarea dentro de la pila fullscreen.
 * Esta actividad comparte la afinidad de Waze, entra en su tarea vaciándola con CLEAR_TASK y la
 * elimina con finishAndRemoveTask; Waze se abre después en una tarea nueva en modo freeform.
 */
class WazeTaskResetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val expectedTaskId = intent.getIntExtra(EXTRA_EXPECTED_TASK, -1)
        val activityClassName = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)
        val forceBoundsRepair = intent.getBooleanExtra(EXTRA_FORCE_REPAIR, true)
        val joinedWazeTask = expectedTaskId < 0 || taskId == expectedTaskId
        LauncherRuntime.recordPanelWindowEvent(
            "$WAZE_PACKAGE tareaFullscreen=${expectedTaskId.takeIf { it >= 0 } ?: "desconocida"} " +
                "puente=$taskId unida=$joinedWazeTask eliminacion=finishAndRemoveTask",
        )
        TelemetryLog.event(
            "EXTERNAL_APP",
            "Waze fullscreen task reset expected=$expectedTaskId bridge=$taskId joined=$joinedWazeTask",
        )
        val appContext = applicationContext
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
        Handler(Looper.getMainLooper()).postDelayed({
            launchApplicationInContentArea(
                context = appContext,
                packageName = WAZE_PACKAGE,
                activityClassName = activityClassName,
                forceBoundsRepair = forceBoundsRepair,
                supersedePendingLaunches = false,
                allowWazeTaskReset = false,
            )
        }, RELAUNCH_DELAY_MS)
    }

    companion object {
        private const val EXTRA_EXPECTED_TASK = "com.ts10.inspector.extra.WAZE_EXPECTED_TASK"
        private const val EXTRA_ACTIVITY_CLASS = "com.ts10.inspector.extra.WAZE_ACTIVITY_CLASS"
        private const val EXTRA_FORCE_REPAIR = "com.ts10.inspector.extra.WAZE_FORCE_REPAIR"
        private const val RELAUNCH_DELAY_MS = 350L
        // Evita un bucle si la ROM vuelve a forzar fullscreen sobre la tarea nueva.
        private const val RESET_COOLDOWN_MS = 60_000L
        private var lastResetAt = 0L

        internal fun start(
            context: Context,
            expectedTaskId: Int?,
            activityClassName: String?,
            forceBoundsRepair: Boolean,
        ): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (lastResetAt != 0L && now - lastResetAt < RESET_COOLDOWN_MS) return false
            return runCatching {
                context.startActivity(
                    Intent(context, WazeTaskResetActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                        )
                        .putExtra(EXTRA_EXPECTED_TASK, expectedTaskId ?: -1)
                        .putExtra(EXTRA_ACTIVITY_CLASS, activityClassName)
                        .putExtra(EXTRA_FORCE_REPAIR, forceBoundsRepair),
                )
                lastResetAt = now
                true
            }.onFailure {
                TelemetryLog.event(
                    "EXTERNAL_APP",
                    "Waze fullscreen task reset failed ${it.javaClass.simpleName}:${it.message.orEmpty()}",
                )
            }.getOrDefault(false)
        }
    }
}

/** Resize modes are hidden fields; 0 means the ROM may force the task out of freeform. */
internal fun wazeWindowingDiagnostics(context: Context): List<String> = runCatching {
    val lines = mutableListOf<String>()
    val resolver = context.contentResolver
    listOf("force_resizable_activities", "enable_freeform_support").forEach { key ->
        lines += "$key=${android.provider.Settings.Global.getString(resolver, key) ?: "--"}"
    }
    val packageInfo = context.packageManager.getPackageInfo(
        WAZE_PACKAGE,
        android.content.pm.PackageManager.GET_ACTIVITIES,
    )
    lines += "version=${packageInfo.versionName ?: "--"}"
    val resizeModeField = runCatching {
        android.content.pm.ActivityInfo::class.java.getField("resizeMode")
    }.getOrNull()
    packageInfo.activities.orEmpty()
        .filter { it.name.endsWith(".MainActivity") || it.name.endsWith(".FreeMapAppActivity") }
        .forEach { activity ->
            val resizeMode = resizeModeField?.let { runCatching { it.getInt(activity) }.getOrNull() }
            lines += "${activity.name.substringAfterLast('.')} resizeMode=${resizeMode ?: "--"} " +
                "launchMode=${activity.launchMode} orientation=${activity.screenOrientation}"
        }
    lines
}.getOrElse { listOf("unavailable: ${it.javaClass.simpleName}") }

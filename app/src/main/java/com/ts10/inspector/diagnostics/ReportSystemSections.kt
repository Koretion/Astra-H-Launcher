package com.ts10.inspector.diagnostics

import android.content.Context
import com.ts10.inspector.panel.panelAutomaticRestoreBlockReason
import com.ts10.inspector.panel.panelProcessDiagnostics
import com.ts10.inspector.panel.panelTaskDiagnostics
import com.ts10.inspector.panel.wazeWindowingDiagnostics
import com.ts10.inspector.phone.AndroidBluetoothPhoneClient
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.vehicle.qf.QfDiagnosticsState
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine
import com.ts10.inspector.vehicle3d.Vehicle3dPerformanceLog

internal fun StringBuilder.appendRightPanelTaskOwnershipSection(context: Context) {
    section("RIGHT PANEL TASK OWNERSHIP")
    field("Automatic restore blocked", panelAutomaticRestoreBlockReason(context) ?: "no")
    val taskDiagnostics = panelTaskDiagnostics(context)
    if (taskDiagnostics.isEmpty()) appendLine("--") else taskDiagnostics.forEach(::appendLine)
    appendLine("Configured application processes:")
    val processDiagnostics = panelProcessDiagnostics(context)
    if (processDiagnostics.isEmpty()) appendLine("--") else processDiagnostics.forEach(::appendLine)
    appendLine("Waze windowing support:")
    wazeWindowingDiagnostics(context).forEach(::appendLine)
    appendQfWindowPolicySection(context)
}

// ROM QF005: services.jar pasa a fullscreen las tareas freeform abiertas por un paquete distinto de
// persist.sys.qf.launcher y fuerza sys.qf.freeform.bound como límites de toda ventana freeform.
private val QF_WINDOW_PROPERTIES = listOf(
    "persist.sys.qf.launcher",
    "sys.qf.freeform.bound",
    "sys.freeform.fullscreen",
    "sys.qf.freeform.hide",
    "sys.qf.top_pkg_name",
    "sys.qf.last_exec_pkg_name",
    "sys.qf.current.activity",
    "persist.sys.new_set_launcher",
)

private val QF_WINDOW_CONFIG_FILES = listOf(
    "/system/config/AppNotSupportSplit.config",
    "/system/config/SplitApp.ini",
    "/system/config/RestoreAppsWhenWakeup.ini",
)

private fun StringBuilder.appendQfWindowPolicySection(context: Context) {
    section("QF WINDOW POLICY")
    val values = QF_WINDOW_PROPERTIES.associateWith(::readSystemProperty)
    values.forEach { (key, value) -> field(key, value ?: "--") }
    val registered = values["persist.sys.qf.launcher"].orEmpty()
    field(
        "QF registered launcher matches this app",
        when {
            registered.isEmpty() -> "unknown"
            context.packageName.startsWith(registered) -> "yes"
            else -> "no (${context.packageName}); panel relaunches are reset to fullscreen by the ROM"
        },
    )
    QF_WINDOW_CONFIG_FILES.forEach { path ->
        appendLine("$path:")
        val content = runCatching { java.io.File(path).readText().trim() }
            .getOrElse { "unreadable: ${it.javaClass.simpleName}" }
        appendLine(content.ifEmpty { "(empty)" }.lines().take(MAX_CONFIG_LINES).joinToString("\n"))
    }
}

private fun readSystemProperty(key: String): String? {
    val viaReflection = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()
    if (viaReflection != null) return viaReflection.ifEmpty { "(empty)" }
    return runCatching {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText().trim() }
            .also { process.waitFor() }
            .ifEmpty { "(empty)" }
    }.getOrNull()
}

private const val MAX_CONFIG_LINES = 80

internal fun StringBuilder.appendBluetoothPhoneAudioSection() {
    section("BLUETOOTH PHONE / AUDIO")
    AndroidBluetoothPhoneClient.diagnostics().forEach(::appendLine)
}

internal fun StringBuilder.appendCarouselMusicVolumeSection() {
    section("CAROUSEL / MUSIC VOLUME")
    VolumeInteractionDiagnostics.snapshot().forEach(::appendLine)
}

internal fun StringBuilder.appendOffscreenOpengl3dSection(context: Context, state: QfDiagnosticsState) {
    val dashboardSettings = DashboardPreferences.current(context)
    section("OFFSCREEN OPENGL 3D")
    val offscreen3d = Vehicle3dOffscreenEngine.state.value
    field("Selected mode", dashboardSettings.vehicleSceneMode)
    field("Status", offscreen3d.status)
    field("Frame", offscreen3d.frame)
    field("Render time", offscreen3d.renderMs?.let { "$it ms" } ?: "--")
    val stats = offscreen3d.stats
    fun f(value: Float) = "%.1f".format(java.util.Locale.US, value)
    field("Target interval", "50 ms (20 fps), fixed cadence")
    field("Last minute frames", stats.frames)
    field("Last minute fps", f(stats.fps))
    field("Frame interval avg", "${f(stats.intervalAvgMs)} ms")
    field("Render avg / p95 / max", "${f(stats.renderAvgMs)} / ${f(stats.renderP95Ms)} / ${f(stats.renderMaxMs)} ms")
    field("Frame output", Vehicle3dOffscreenEngine.outputPath())
    field("Draw / readback / convert avg", "${f(stats.drawAvgMs)} / ${f(stats.readbackAvgMs)} / ${f(stats.convertAvgMs)} ms")
    field("Error", offscreen3d.error ?: "--")
    appendLine("Mode: read-only")
    appendLine("Capture: ${if (state.captureActive) "active" else "stopped"}")
    appendLine()
}

internal fun StringBuilder.appendVehicle3dPerformanceSection(context: Context) {
    val vehicle3dPerformance = Vehicle3dPerformanceLog.snapshot(context)
    section("VEHICLE 3D PERFORMANCE (OLDEST TO NEWEST)")
    append(vehicle3dPerformance)
    if (!vehicle3dPerformance.endsWith('\n')) appendLine()
}

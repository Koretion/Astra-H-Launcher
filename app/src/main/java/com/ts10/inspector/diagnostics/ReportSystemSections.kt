package com.ts10.inspector.diagnostics

import android.content.Context
import com.ts10.inspector.panel.panelAutomaticRestoreBlockReason
import com.ts10.inspector.panel.panelProcessDiagnostics
import com.ts10.inspector.panel.panelTaskDiagnostics
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
}

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

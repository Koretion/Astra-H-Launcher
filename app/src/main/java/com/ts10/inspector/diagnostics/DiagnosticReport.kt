package com.ts10.inspector.diagnostics

import com.ts10.inspector.ui.UiText
import android.content.Context
import com.ts10.inspector.vehicle.obd.Obd2State
import com.ts10.inspector.vehicle.qf.QfDiagnosticsState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-text diagnostic report exported by [QfLogExportActivity]; each domain adds its own sections. */
internal object DiagnosticReport {
    private val fileTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val reportTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun suggestedFileName(): String =
        "TS10_QF_Log_${LocalDateTime.now().format(fileTimeFormat)}.txt"

    fun build(context: Context, state: QfDiagnosticsState): String = buildString {
        appendLine("TS10 QF / CAN diagnostic log")
        appendLine("Generated: ${LocalDateTime.now().format(reportTimeFormat)}")
        appendRightPanelTaskOwnershipSection(context)
        appendBluetoothPhoneAudioSection()
        appendCarouselMusicVolumeSection()
        appendOffscreenOpengl3dSection(context, state)
        appendConnectionSection(context, state)
        appendEsp32CanRawUsbSection(context)
        appendCanFaultDiscoverySection(context)
        appendLauncherGpsAcquisitionSection(context)
        appendOpenstreetmapRoadContextSection(context)
        appendVehicleTrackingGoogleSheetsSection(context)
        appendGrabadoraHdAdasSection()
        appendGrabadoraHdCameraTargetsSection()
        appendLocalVisionPhaseV0Section(context)
        appendVisionFrameHubSection()
        appendUsbUvcVisionSourceSection(context)
        appendLocalObjectDetectorPhaseV1Section()
        appendLocalLaneDetectorPhaseV3Section()
        appendConfirmedRoadSignsV3Section()
        appendVisionDeviceSnapshotSection(context)
        appendPersistentVisionTelemetrySection(context)
        appendVehicle3dPerformanceSection(context)
        appendObd2Elm327Section(context)
        appendDirectCanElm327ParitySection()
        appendTpmsDdsSection(context)
        appendDisplayedSpeedSection(context)
        appendFuelRangeSection(context)
        appendPacketTypesSection(state)
        appendObservedBroadcastsSection(state)
        appendByteChangesSection(state)
        appendDistinctRawPacketSamplesSection(state)
        appendQuickAnalysisSection(state)
        appendClimateSection(state)
        appendBodyAndLightsSection(state)
        appendDoorsSection(state)
        appendSteeringRadarCameraSection(state)
        appendOutsideTemperatureSection(state)
        appendLastRawPacketSection(state)
        appendEventLogSection(state)
    }
}

internal fun StringBuilder.section(title: String) {
    appendLine()
    appendLine("=== $title ===")
}

internal fun StringBuilder.field(label: String, value: Any) {
    appendLine("$label: $value")
}

/** Impide volcar un UiText sin resolver: hay que usar `english(context)`. */
@Deprecated("Resolve the text with english(context)", level = DeprecationLevel.ERROR)
@Suppress("UNUSED_PARAMETER")
internal fun StringBuilder.field(label: String, value: UiText): Unit = error("Unresolved UiText")

internal fun quickAnalysis(state: QfDiagnosticsState): List<String> = buildList {
    if (state.packetCount == 0L) {
        add("No QF/CAN packets were received during this capture.")
        return@buildList
    }
    if (state.turnPacketCount == 0L &&
        state.leftBlinker == "Inactivo" &&
        state.rightBlinker == "Inactivo" &&
        state.hazard == "Inactivo"
    ) {
        add("No turn/hazard callback packets were received; blinkers may not be exported by this CAN profile.")
    }
    if (state.steeringPacketCount == 0L) {
        add("No steering angle packets were received.")
    }
    if (state.doorPacketCount == 0L) {
        add("No dedicated door packets were received.")
    }
    if (state.carPacketCount > 0L &&
        state.dippedBeam == "Inactivo" &&
        state.highBeam == "Inactivo" &&
        state.positionLights == "Inactivo"
    ) {
        add("Body packets were received, but no headlight bits changed in the exported fields.")
    }
    if (isEmpty()) add("No missing-signal warning detected in the exported QF fields.")
}

internal fun obdSensorValue(state: Obd2State, pid: Int): String = when (pid) {
    0x05 -> state.coolantCelsius?.let { "$it C" }
    0x0C -> state.rpm?.let { "$it rpm" }
    0x0D -> state.speedKmh?.let { "$it km/h" }
    0x10 -> state.massAirFlowGps?.let { "%.2f g/s".format(Locale.US, it) }
    0x2F -> state.fuelPercent?.let { "$it %" }
    0x5C -> state.oilCelsius?.let { "$it C" }
    0x5E -> state.fuelRateLitersPerHour?.let { "%.2f L/h".format(Locale.US, it) }
    else -> null
} ?: "no current value"

internal val KNOWN_OBD_SENSORS = linkedMapOf(
    0x05 to "Engine coolant temperature",
    0x0C to "Engine RPM",
    0x0D to "Vehicle speed",
    0x10 to "Mass air flow",
    0x2F to "Fuel tank level",
    0x5C to "Engine oil temperature",
    0x5E to "Engine fuel rate",
)

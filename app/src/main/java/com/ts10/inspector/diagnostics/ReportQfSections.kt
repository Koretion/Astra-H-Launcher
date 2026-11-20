package com.ts10.inspector.diagnostics

import android.content.Context
import com.ts10.inspector.vehicle.qf.QfDataShareBridge
import com.ts10.inspector.vehicle.qf.QfDiagnosticsState

internal fun StringBuilder.appendConnectionSection(context: Context, state: QfDiagnosticsState) {
    val root = AndroidRootChecker.state.value
    val qfSteeringBridge = QfDataShareBridge.state.value
    section("CONNECTION")
    field("Binder", state.binderStatus.english(context))
    field("Climate callback", state.acCallbackStatus)
    field("Door callback", state.doorCallbackStatus)
    field("Steering callback", state.steeringCallbackStatus)
    field("Radar callback", state.radarCallbackStatus)
    field("Body callback", state.carCallbackStatus)
    field("Camera callback", state.cameraCallbackStatus)
    field("Turn callback", state.turnCallbackStatus)
    field("ACC", state.accStatus)
    field("System night mode", state.systemNightMode)
    field("System night mode changes", state.systemNightModeCount)
    field("Car backlight", state.carBacklightValue)
    field("Car backlight messages", state.carBacklightCount)
    field("Last source", state.lastSource)
    field("Total packets", state.packetCount)
    field("Android root", root.summary)
    field("Root evidence", root.evidence)
    field("QF steering broadcast", qfSteeringBridge.status.english(context))
    field("QF ACTION_SWA attempts", qfSteeringBridge.attemptedPackets)
    field("QF ACTION_SWA sends", qfSteeringBridge.sentPackets)
    field("QF ACTION_SWA blocked by ROM", qfSteeringBridge.blockedByRom)
    field("QF ACTION_SWA level", qfSteeringBridge.lastAngleLevel ?: "--")
    field("QF ACTION_SWA angle", qfSteeringBridge.lastQfAngleDegrees ?: "--")
}

internal fun StringBuilder.appendPacketTypesSection(state: QfDiagnosticsState) {
    section("PACKET TYPES")
    if (state.packetTypes.isEmpty()) appendLine("No packets received")
    state.packetTypes.toSortedMap().forEach { (type, count) -> field(type, count) }
}

internal fun StringBuilder.appendObservedBroadcastsSection(state: QfDiagnosticsState) {
    section("OBSERVED BROADCASTS")
    if (state.observedBroadcasts.isEmpty()) appendLine("No diagnostic broadcasts observed")
    state.observedBroadcasts.toSortedMap().forEach { (action, count) -> field(action, count) }
}

internal fun StringBuilder.appendByteChangesSection(state: QfDiagnosticsState) {
    section("BYTE CHANGES")
    field("Last diff", state.lastPacketDiff)
    if (state.packetByteChanges.isEmpty()) appendLine("No byte changes detected")
    state.packetByteChanges.toSortedMap().forEach { (type, diff) -> field(type, diff) }
}

internal fun StringBuilder.appendDistinctRawPacketSamplesSection(state: QfDiagnosticsState) {
    section("DISTINCT RAW PACKET SAMPLES")
    if (state.rawPacketSamples.isEmpty()) {
        appendLine("No raw packet samples retained")
    }
    state.rawPacketSamples.toSortedMap().forEach { (type, samples) ->
        appendLine(
            "$type: ${samples.size} distinct retained / " +
                "${state.packetTypes[type] ?: 0L} packets received",
        )
        samples.forEach { sample ->
            appendLine(
                "  ${sample.timestamp} ${sample.source} [${sample.sizeBytes} bytes] " +
                    "diff={${sample.diff}} raw={${sample.hex}}",
            )
        }
    }
}

internal fun StringBuilder.appendQuickAnalysisSection(state: QfDiagnosticsState) {
    section("QUICK ANALYSIS")
    quickAnalysis(state).forEach(::appendLine)
}

internal fun StringBuilder.appendClimateSection(state: QfDiagnosticsState) {
    section("CLIMATE")
    field("Packets", state.climatePacketCount)
    field("Power", state.climatePower)
    field("A/C compressor", state.climateCompressor)
    field("Automatic", state.climateAuto)
    field("Recirculation", state.climateRecirculation)
    field("Front defrost", state.climateFrontDefrost)
    field("Rear defrost", state.climateRearDefrost)
    field("Fan", state.climateFan)
    field("Air direction", state.climateAirDirection)
    field("Temperature", state.climateTemperature)
}

internal fun StringBuilder.appendBodyAndLightsSection(state: QfDiagnosticsState) {
    section("BODY AND LIGHTS")
    field("Packets", state.carPacketCount)
    field("CAN speed", state.canSpeed)
    field("Engine RPM", state.engineRpm)
    field("Fuel", state.fuelCapacity)
    field("Left blinker", state.leftBlinker)
    field("Right blinker", state.rightBlinker)
    field("Hazard", state.hazard)
    field("Dipped beam", state.dippedBeam)
    field("High beam", state.highBeam)
    field("Position lights", state.positionLights)
    field("Handbrake", state.handbrake)
    field("Brake warning", state.brakeWarning)
    field("Turn packets", state.turnPacketCount)
}

internal fun StringBuilder.appendDoorsSection(state: QfDiagnosticsState) {
    section("DOORS")
    field("Packets", state.doorPacketCount)
    field("Front left", state.frontLeftDoor)
    field("Front right", state.frontRightDoor)
    field("Rear left", state.rearLeftDoor)
    field("Rear right", state.rearRightDoor)
    field("Trunk", state.trunk)
    field("Bonnet", state.bonnet)
}

internal fun StringBuilder.appendSteeringRadarCameraSection(state: QfDiagnosticsState) {
    section("STEERING / RADAR / CAMERA")
    field("Steering packets", state.steeringPacketCount)
    field("Steering angle", state.steeringAngle)
    field("Radar packets", state.radarPacketCount)
    field("Radar raw levels", state.radarLevels)
    field("Camera packets", state.cameraPacketCount)
    field("Camera state", state.cameraState)
    field("Reverse state", state.reverseStatus)
    field("BACKCAR events", state.reverseEventCount)
}

internal fun StringBuilder.appendOutsideTemperatureSection(state: QfDiagnosticsState) {
    section("OUTSIDE TEMPERATURE")
    field("Messages", state.outsideTemperatureCount)
    field("Value", state.outsideTemperature)
}

internal fun StringBuilder.appendLastRawPacketSection(state: QfDiagnosticsState) {
    section("LAST RAW PACKET")
    appendLine(state.lastPacketHex)
}

internal fun StringBuilder.appendEventLogSection(state: QfDiagnosticsState) {
    section("EVENT LOG (newest first)")
    if (state.log.isEmpty()) appendLine("No log entries")
    state.log.forEach { appendLine("${it.timestamp}  ${it.message}") }
}

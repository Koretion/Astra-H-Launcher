package com.ts10.inspector.vehicle.qf

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import androidx.compose.runtime.Immutable

@Immutable
data class QfDiagnosticsState(
    val captureActive: Boolean = false,
    val binderStatus: UiText = UiText.of(R.string.qf_not_started),
    val acCallbackStatus: String = "Pending",
    val doorCallbackStatus: String = "Pending",
    val steeringCallbackStatus: String = "Pending",
    val radarCallbackStatus: String = "Pending",
    val carCallbackStatus: String = "Pending",
    val cameraCallbackStatus: String = "Pending",
    val turnCallbackStatus: String = "Pending",
    val accStatus: String = "Unknown",
    val systemNightMode: String = "No data",
    val systemNightModeCount: Long = 0,
    val carBacklightValue: String = "No data",
    val carBacklightCount: Long = 0,
    val packetCount: Long = 0,
    val climatePacketCount: Long = 0,
    val carPacketCount: Long = 0,
    val outsideTemperatureCount: Long = 0,
    val lastSource: String = "--",
    val lastPacketHex: String = "--",
    val lastPacketDiff: String = "--",
    val packetByteChanges: Map<String, String> = emptyMap(),
    val rawPacketSamples: Map<String, List<QfRawPacketSample>> = emptyMap(),
    val climateDataReceived: Boolean = false,
    val climatePower: String = "No data",
    val climateCompressor: String = "No data",
    val climateAuto: String = "No data",
    val climateRecirculation: String = "No data",
    val climateFrontDefrost: String = "No data",
    val climateRearDefrost: String = "No data",
    val climateFan: String = "No data",
    val climateAirDirection: String = "No data",
    val climateTemperature: String = "No data",
    val carDataReceived: Boolean = false,
    val canSpeed: String = "No data",
    val engineRpm: String = "No data",
    val fuelCapacity: String = "No data",
    val leftBlinker: String = "No data",
    val rightBlinker: String = "No data",
    val hazard: String = "No data",
    val dippedBeam: String = "No data",
    val highBeam: String = "No data",
    val positionLights: String = "No data",
    val handbrake: String = "No data",
    val brakeWarning: String = "No data",
    val doorPacketCount: Long = 0,
    val frontLeftDoor: String = "No data",
    val frontRightDoor: String = "No data",
    val rearLeftDoor: String = "No data",
    val rearRightDoor: String = "No data",
    val trunk: String = "No data",
    val bonnet: String = "No data",
    val steeringPacketCount: Long = 0,
    val steeringAngle: String = "No data",
    val radarPacketCount: Long = 0,
    val radarLevels: String = "No data",
    val cameraPacketCount: Long = 0,
    val cameraState: String = "No data",
    val reverseEventCount: Long = 0,
    val reverseStatus: String = "No data",
    val turnPacketCount: Long = 0,
    val packetTypes: Map<String, Long> = emptyMap(),
    val observedBroadcasts: Map<String, Long> = emptyMap(),
    val outsideTemperature: String = "No data",
    val log: List<QfLogEntry> = emptyList(),
)

@Immutable
data class QfLogEntry(
    val timestamp: String,
    val message: String,
)

@Immutable
data class QfRawPacketSample(
    val timestamp: String,
    val source: String,
    val sizeBytes: Int,
    val hex: String,
    val diff: String,
)

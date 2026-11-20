package com.ts10.inspector.vehicle.can

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import com.ts10.inspector.vehicle.tires.WheelSpeeds

internal data class Esp32CanState(
    val status: UiText = UiText.of(R.string.can_not_started),
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val connected: Boolean = false,
    val subscribed: Boolean = false,
    val frames: Long = 0,
    val framesPerSecond: Int = 0,
    val distinctIds: Int = 0,
    val chunkLosses: Long = 0,
    val frameLosses: Long = 0,
    val malformedPackets: Long = 0,
    val remoteReceived: Long = 0,
    val remoteQueueDrops: Long = 0,
    val remoteNoClientDrops: Long = 0,
    val remoteFilteredFrames: Long = 0,
    val remoteTwaiErrors: Long = 0,
    val remoteHsStarted: Boolean? = null,
    val remoteMsStarted: Boolean? = null,
    val remoteSwStarted: Boolean? = null,
    val remoteHsReceived: Long = 0,
    val remoteMsReceived: Long = 0,
    val remoteSwReceived: Long = 0,
    val remoteHsErrors: Long = 0,
    val remoteMsErrors: Long = 0,
    val remoteSwErrors: Long = 0,
    val remoteSwBusErrorAlerts: Long = 0,
    val remoteSwRxQueueFullAlerts: Long = 0,
    val remoteSwErrorPassiveAlerts: Long = 0,
    val remoteSwBusOffAlerts: Long = 0,
    val remoteSwAboveErrorWarningAlerts: Long = 0,
    val remoteSwBelowErrorWarningAlerts: Long = 0,
    val remoteSwControllerState: Long? = null,
    val remoteSwMessagesPending: Long = 0,
    val remoteSwTxErrorCounter: Long = 0,
    val remoteSwRxErrorCounter: Long = 0,
    val remoteSwRxMissedCount: Long = 0,
    val remoteSwRxOverrunCount: Long = 0,
    val remoteSwBusErrorCount: Long = 0,
    val remoteSwRxEdges: Long = 0,
    val remoteSwRxLevelHigh: Boolean? = null,
    val bitrateKbps: Int = 0,
    val streamMode: String = "--",
    val captureActive: Boolean = false,
    val loggedFrames: Long = 0,
    val localLogDrops: Long = 0,
    val capturePath: String? = null,
    val initialStateStatus: String = "No startup snapshot",
    val initialStateResolved: Boolean = false,
    val faultCandidates: List<CanFaultCandidate> = emptyList(),
    val lastFrame: String = "--",
    val hsSteeringAngleDegrees: Float? = null,
    val hsLeftIndicator: Boolean = false,
    val hsRightIndicator: Boolean = false,
    val hsExteriorLightsCandidate: Boolean = false,
    val hsHighBeam: Boolean = false,
    val hsHandbrake: Boolean? = null,
    val hsWheelSpeeds: WheelSpeeds? = null,
    val hsVehicleSpeedKmh: Float? = null,
    val hsEngineRpm: Int? = null,
    val hsCoolantCelsius: Int? = null,
    val hsOdometerKm: Double? = null,
    val hsFuelRawByte: Int? = null,
    val swDriverDoorOpen: Boolean? = null,
    val swPassengerDoorOpen: Boolean? = null,
    val swTrunkOpen: Boolean? = null,
    val swCentralLocked: Boolean? = null,
    val swCentralLockAction: SwCentralLockAction? = null,
    val swLeftIndicator: Boolean = false,
    val swRightIndicator: Boolean = false,
    val swHazard: Boolean = false,
    val swLampFault: SwLampFault? = null,
    val swHandbrake: Boolean? = null,
    val swServiceBrakePressed: Boolean? = null,
    val swClutchPressed: Boolean? = null,
    val swPedalSwitchRaw: Int? = null,
    val swHighBeamRequest: Boolean = false,
    val swWiperMode: SwWiperMode? = null,
    val swWindowAction: SwWindowAction? = null,
    val swCentralControlAction: SwCentralControlAction? = null,
    val swOdometerKm: Double? = null,
    val swFuelRawByte: Int? = null,
    val swOutdoorTemperatureCelsius: Float? = null,
    val swBatteryVoltage: Float? = null,
    val swIgnitionKeyIndex: Int? = null,
    val swFrontFogOn: Boolean? = null,
    val swRearFogOn: Boolean? = null,
    val hsWheelSpeedUpdatedElapsedMs: Long = 0L,
    val hsVehicleSpeedUpdatedElapsedMs: Long = 0L,
    val hsBodyDynamicsUpdatedElapsedMs: Long = 0L,
    val hsHandbrakeUpdatedElapsedMs: Long = 0L,
    val hsEngineUpdatedElapsedMs: Long = 0L,
    val hsCoolantUpdatedElapsedMs: Long = 0L,
    val swBodyUpdatedElapsedMs: Long = 0L,
    val swTurnUpdatedElapsedMs: Long = 0L,
    val swHandbrakeUpdatedElapsedMs: Long = 0L,
    val msExteriorLightsOn: Boolean? = null,
    val msLightingUpdatedElapsedMs: Long = 0L,
    val msVehicleSpeedKmh: Float? = null,
    val msEngineRpm: Int? = null,
    val msReverse: Boolean? = null,
    val msMotionState: Int? = null,
    val msClimate: MsCanClimateSignals? = null,
    val msMotionUpdatedElapsedMs: Long = 0L,
    val msClimateUpdatedElapsedMs: Long = 0L,
)

internal fun Esp32CanState.busDiagnostic(bus: Esp32CanBus): String {
    val (started, received, errors) = when (bus) {
        Esp32CanBus.HS_CAN -> Triple(remoteHsStarted, remoteHsReceived, remoteHsErrors)
        Esp32CanBus.MS_CAN -> Triple(remoteMsStarted, remoteMsReceived, remoteMsErrors)
        Esp32CanBus.SW_CAN -> Triple(remoteSwStarted, remoteSwReceived, remoteSwErrors)
        Esp32CanBus.UNKNOWN -> Triple(null, remoteReceived, remoteTwaiErrors)
    }
    val state = when (started) {
        true -> "active"
        false -> "START FAILED"
        null -> "no data"
    }
    return "$state / $received / $errors"
}

internal fun twaiStateLabel(state: Long): String = when (state) {
    0L -> "STOPPED"
    1L -> "RUNNING"
    2L -> "BUS_OFF"
    3L -> "RECOVERING"
    else -> "UNKNOWN($state)"
}

package com.ts10.inspector.vehicle

import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.can.Esp32CanState
import com.ts10.inspector.vehicle.can.SwWindowAction
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.obd.Obd2State
import com.ts10.inspector.vehicle.obd.ObdConnectionState
import com.ts10.inspector.vehicle.obd.effectiveFuelRateLitersPerHour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal enum class VehicleSignalSource(val label: String) {
    HS_CAN("GMLAN-USB HS-CAN"),
    MS_CAN("GMLAN-USB MS-CAN"),
    SW_CAN("GMLAN-USB SW-CAN"),
    OBD2("OBD2"),
}

internal data class VehicleSignal<out T>(val value: T, val source: VehicleSignalSource)

/**
 * Vehicle values resolved from whichever optional data sources are fitted (ESP32 CAN, ELM327).
 *
 * Screens read this instead of a concrete client, so a car with only one of the sources still
 * gets every value that source can provide. OBD2 values are used only while the adapter is
 * connected, because the client keeps its last readings across reconnection attempts.
 * Displayed speed, doors and lights stay in LauncherState, which also merges the QF CAN box.
 */
internal data class VehicleSignals(
    val engineRpm: VehicleSignal<Int>? = null,
    /** Speed from vehicle buses only; the speedometer also falls back to QF and GPS. */
    val busSpeedKmh: VehicleSignal<Float>? = null,
    val coolantCelsius: VehicleSignal<Int>? = null,
    val batteryVoltage: VehicleSignal<Float>? = null,
    val fuelRateLitersPerHour: VehicleSignal<Float>? = null,
    val steeringAngleDegrees: VehicleSignal<Float>? = null,
    val clutchPressed: Boolean? = null,
    val reverse: Boolean? = null,
    val serviceBrakePressed: Boolean? = null,
    val frontFogOn: Boolean? = null,
    val rearFogOn: Boolean? = null,
    val windowAction: SwWindowAction? = null,
)

internal fun fuseVehicleSignals(can: Esp32CanState, obd: Obd2State): VehicleSignals {
    val obdLive = obd.connection == ObdConnectionState.CONNECTED
    return VehicleSignals(
        engineRpm = firstSignal(
            can.hsEngineRpm to VehicleSignalSource.HS_CAN,
            can.msEngineRpm to VehicleSignalSource.MS_CAN,
            obd.rpm.takeIf { obdLive } to VehicleSignalSource.OBD2,
        ),
        busSpeedKmh = firstSignal(
            can.hsVehicleSpeedKmh to VehicleSignalSource.HS_CAN,
            can.msVehicleSpeedKmh to VehicleSignalSource.MS_CAN,
            obd.speedKmh?.toFloat().takeIf { obdLive } to VehicleSignalSource.OBD2,
        ),
        coolantCelsius = firstSignal(
            can.hsCoolantCelsius to VehicleSignalSource.HS_CAN,
            obd.coolantCelsius.takeIf { obdLive } to VehicleSignalSource.OBD2,
        ),
        batteryVoltage = firstSignal(can.swBatteryVoltage to VehicleSignalSource.SW_CAN),
        fuelRateLitersPerHour = firstSignal(
            effectiveFuelRateLitersPerHour(obd).takeIf { obdLive } to VehicleSignalSource.OBD2,
        ),
        steeringAngleDegrees = firstSignal(can.hsSteeringAngleDegrees to VehicleSignalSource.HS_CAN),
        clutchPressed = can.swClutchPressed,
        reverse = can.msReverse,
        serviceBrakePressed = can.swServiceBrakePressed,
        frontFogOn = can.swFrontFogOn,
        rearFogOn = can.swRearFogOn,
        windowAction = can.swWindowAction,
    )
}

private fun <T : Any> firstSignal(vararg candidates: Pair<T?, VehicleSignalSource>): VehicleSignal<T>? =
    candidates.firstNotNullOfOrNull { (value, source) -> value?.let { VehicleSignal(it, source) } }

internal object VehicleSignalHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<VehicleSignals> = combine(
        Esp32CanClient.state,
        Obd2Client.state,
        ::fuseVehicleSignals,
    ).stateIn(
        scope,
        SharingStarted.Eagerly,
        fuseVehicleSignals(Esp32CanClient.state.value, Obd2Client.state.value),
    )
}

package com.ts10.inspector.vehicle.can

import com.ts10.inspector.R
import androidx.annotation.StringRes
import com.ts10.inspector.vehicle.can.profile.CanDecodedSignals
import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfile
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfiles

// `label` va a los registros en inglés; `text` es la etiqueta traducida de la interfaz.
internal enum class SwWiperMode(val label: String, @StringRes val text: Int) {
    OFF("Off", R.string.wiper_off),
    SPEED_1("Speed 1", R.string.wiper_speed_1),
    SPEED_2("Speed 2", R.string.wiper_speed_2),
    SPEED_3("Speed 3", R.string.wiper_speed_3),
    WASHER("Washer", R.string.wiper_washer),
    UNKNOWN("Unknown", R.string.wiper_unknown),
}

internal enum class SwWindowAction(val label: String, @StringRes val text: Int) {
    OPENING("Opening", R.string.window_opening),
    CLOSING("Closing", R.string.window_closing),
}

internal enum class SwCentralLockAction(val label: String, @StringRes val text: Int) {
    LOCKING("Lock", R.string.lock_locking),
    UNLOCKING("Unlock", R.string.lock_unlocking),
}

internal enum class SwCentralControlAction(val label: String, @StringRes val text: Int) {
    SPORT("SPORT button", R.string.control_sport),
    ESP("ESP button", R.string.control_esp),
}

internal enum class SwLampFault(val label: String, @StringRes val text: Int) {
    RIGHT_INDICATOR("Right indicator: open circuit or failed bulb", R.string.lamp_fault_right_indicator),
}

internal data class SwCanVehicleSignals(
    val driverDoorOpen: Boolean? = null,
    val passengerDoorOpen: Boolean? = null,
    val trunkOpen: Boolean? = null,
    val centralLocked: Boolean? = null,
    val centralLockAction: SwCentralLockAction? = null,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val hazard: Boolean = false,
    val lampFault: SwLampFault? = null,
    val handbrake: Boolean? = null,
    val serviceBrakePressed: Boolean? = null,
    val clutchPressed: Boolean? = null,
    val pedalSwitchRaw: Int? = null,
    val highBeamRequest: Boolean = false,
    val wiperMode: SwWiperMode? = null,
    val windowAction: SwWindowAction? = null,
    val centralControlAction: SwCentralControlAction? = null,
    val odometerKm: Double? = null,
    val fuelRawByte: Int? = null,
    val outdoorTemperatureCelsius: Float? = null,
    val batteryVoltage: Float? = null,
    val ignitionKeyIndex: Int? = null,
    val frontFogOn: Boolean? = null,
    val rearFogOn: Boolean? = null,
)

/**
 * Passive SW/LS-CAN decoder. Frame IDs, bits and payloads come from the vehicle CAN profile
 * (`vehicle_profiles/<car>.json`); the evidence for the Astra H values is in docs/CAN_SIGNALS.md.
 */
internal class SwCanVehicleDecoder(profile: VehicleCanProfile = VehicleCanProfiles.active) {
    /** Al cambiar de perfil se olvida lo decodificado con el anterior. */
    var profile: VehicleCanProfile = profile
        set(value) {
            field = value
            reset()
        }

    var signals = SwCanVehicleSignals()
        private set

    fun reset() {
        signals = SwCanVehicleSignals()
    }

    fun accept(frame: Esp32CanFrame): SwCanVehicleSignals {
        if (frame.bus != Esp32CanBus.SW_CAN) return signals
        val decoded = profile.decode(frame)
        if (decoded.isEmpty) return signals
        val current = signals
        var next = current.copy(
            driverDoorOpen = decoded.flag(CanSignals.DRIVER_DOOR_OPEN, current.driverDoorOpen),
            passengerDoorOpen = decoded.flag(CanSignals.PASSENGER_DOOR_OPEN, current.passengerDoorOpen),
            trunkOpen = decoded.flag(CanSignals.TRUNK_OPEN, current.trunkOpen),
            centralLocked = decoded.flag(CanSignals.CENTRAL_LOCKED, current.centralLocked),
            centralLockAction = decoded.enumOr(CanSignals.CENTRAL_LOCK_ACTION, current.centralLockAction),
            handbrake = decoded.flag(CanSignals.HANDBRAKE, current.handbrake),
            serviceBrakePressed = decoded.flag(CanSignals.SERVICE_BRAKE, current.serviceBrakePressed),
            clutchPressed = decoded.flag(CanSignals.CLUTCH, current.clutchPressed),
            pedalSwitchRaw = decoded.int(CanSignals.PEDAL_SWITCH_RAW, current.pedalSwitchRaw),
            highBeamRequest = decoded.flagOr(CanSignals.HIGH_BEAM_REQUEST, current.highBeamRequest),
            wiperMode = decoded.enumOr(CanSignals.WIPER_MODE, current.wiperMode),
            windowAction = decoded.enumOr(CanSignals.WINDOW_ACTION, current.windowAction),
            centralControlAction = decoded.enumOr(CanSignals.CENTRAL_CONTROL_ACTION, current.centralControlAction),
            odometerKm = decoded.double(CanSignals.ODOMETER_KM, current.odometerKm),
            fuelRawByte = decoded.int(CanSignals.FUEL_SENDER_RAW, current.fuelRawByte),
            outdoorTemperatureCelsius = decoded.float(CanSignals.OUTDOOR_TEMPERATURE_C, current.outdoorTemperatureCelsius),
            batteryVoltage = decoded.float(CanSignals.BATTERY_VOLTAGE, current.batteryVoltage),
            ignitionKeyIndex = decoded.int(CanSignals.IGNITION_KEY_INDEX, current.ignitionKeyIndex),
            frontFogOn = decoded.flag(CanSignals.FRONT_FOG, current.frontFogOn),
            rearFogOn = decoded.flag(CanSignals.REAR_FOG, current.rearFogOn),
        )
        next = when (decoded.label(CanSignals.TURN_LAMPS)) {
            "LEFT" -> next.copy(leftIndicator = true, rightIndicator = false, hazard = false)
            "RIGHT" -> next.copy(leftIndicator = false, rightIndicator = true, hazard = false, lampFault = null)
            // The BCM repeats the fault payload every ~320 ms instead of ~681 ms (hyperflash).
            "RIGHT_LAMP_FAULT" -> next.copy(
                leftIndicator = false,
                rightIndicator = true,
                hazard = false,
                lampFault = SwLampFault.RIGHT_INDICATOR,
            )
            "HAZARD" -> next.copy(leftIndicator = true, rightIndicator = true, hazard = true)
            "OFF" -> next.copy(leftIndicator = false, rightIndicator = false, hazard = false)
            else -> next
        }
        signals = next
        return signals
    }

    private inline fun <reified T : Enum<T>> CanDecodedSignals.enumOr(name: String, current: T?): T? =
        label(name)?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: current
}

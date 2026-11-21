package com.ts10.inspector.vehicle.can

import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfile
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfiles
import com.ts10.inspector.vehicle.tires.WheelSpeeds

/** HS-CAN signals; the Astra H values were validated on captures from 2026-09 to 2026-11. */
internal data class HsCanVehicleSignals(
    val steeringAngleDegrees: Float? = null,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val exteriorLightsCandidate: Boolean = false,
    val highBeam: Boolean = false,
    val handbrake: Boolean? = null,
    val wheelSpeeds: WheelSpeeds? = null,
    val vehicleSpeedKmh: Float? = null,
    val engineRpm: Int? = null,
    val coolantCelsius: Int? = null,
    val odometerKm: Double? = null,
    val fuelRawByte: Int? = null,
)

/**
 * Passive HS-CAN decoder. Frame IDs, bits and scales come from the vehicle CAN profile
 * (`vehicle_profiles/<car>.json`); the evidence for the Astra H values is in docs/CAN_SIGNALS.md.
 */
internal class HsCanVehicleDecoder(profile: VehicleCanProfile = VehicleCanProfiles.active) {
    /** Al cambiar de perfil se olvida lo decodificado con el anterior. */
    var profile: VehicleCanProfile = profile
        set(value) {
            field = value
            reset()
        }

    var signals = HsCanVehicleSignals()
        private set

    fun reset() {
        signals = HsCanVehicleSignals()
    }

    fun accept(frame: Esp32CanFrame): HsCanVehicleSignals {
        if (frame.bus != Esp32CanBus.HS_CAN) return signals
        val decoded = profile.decode(frame)
        if (decoded.isEmpty) return signals
        val current = signals
        val turn = decoded.label(CanSignals.TURN_STATE)
        val fl = decoded.float(CanSignals.WHEEL_SPEED_FL_KMH, null)
        val fr = decoded.float(CanSignals.WHEEL_SPEED_FR_KMH, null)
        val rl = decoded.float(CanSignals.WHEEL_SPEED_RL_KMH, null)
        val rr = decoded.float(CanSignals.WHEEL_SPEED_RR_KMH, null)
        signals = current.copy(
            steeringAngleDegrees = decoded.float(CanSignals.STEERING_ANGLE_DEG, current.steeringAngleDegrees),
            leftIndicator = turn?.let { it == "LEFT" } ?: current.leftIndicator,
            rightIndicator = turn?.let { it == "RIGHT" } ?: current.rightIndicator,
            exteriorLightsCandidate = decoded.flagOr(CanSignals.EXTERIOR_LIGHTS, current.exteriorLightsCandidate),
            highBeam = decoded.flagOr(CanSignals.HIGH_BEAM, current.highBeam),
            handbrake = decoded.flag(CanSignals.HANDBRAKE, current.handbrake),
            wheelSpeeds = if (fl != null && fr != null && rl != null && rr != null) {
                WheelSpeeds(fl, fr, rl, rr)
            } else {
                current.wheelSpeeds
            },
            vehicleSpeedKmh = decoded.float(CanSignals.VEHICLE_SPEED_KMH, current.vehicleSpeedKmh),
            engineRpm = decoded.int(CanSignals.ENGINE_RPM, current.engineRpm),
            coolantCelsius = decoded.int(CanSignals.COOLANT_C, current.coolantCelsius),
            odometerKm = decoded.double(CanSignals.ODOMETER_KM, current.odometerKm),
            fuelRawByte = decoded.int(CanSignals.FUEL_SENDER_RAW, current.fuelRawByte),
        )
        return signals
    }
}

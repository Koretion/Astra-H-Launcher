package com.ts10.inspector.vehicle.can

import com.ts10.inspector.vehicle.tires.WheelSpeeds

/** Signals validated on the Astra H HS-CAN captures from 2026-09-04 through 2026-09-09. */
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
 * Passive decoder for signals reproduced with isolated physical actions.
 *
 * 0x180 bytes 2..3: signed big-endian steering angle, 1/16 degree, left positive. Ignition
 * captures (2026-10/11) show byte 0 0x64 + angle 0x7FFF (not initialised) until the first
 * movement, 0xA4 afterwards; such frames are ignored.
 * 0x381 byte 0: indicator state; bit 0x08 is an exterior-light candidate.
 * 0x381 byte 4 bit 0x80: high beam.
 * 0x381 byte 5: complete 8-bit fuel sender, mirrored in SW-CAN 0x375 byte 1.
 * 0x380 byte 7 bit 0x80: handbrake engaged.
 * 0x280: four unsigned big-endian wheel speeds, raw * 15 / 512 km/h. Validated order:
 * bytes 0..1 front-left, 2..3 front-right, 4..5 rear-left, 6..7 rear-right.
 * 0x410 bytes 1..2: vehicle speed, unsigned big-endian, 1/128 km/h.
 * 0x110 bytes 1..2: engine speed, unsigned big-endian, 1/4 rpm.
 * 0x510 byte 1: engine coolant temperature with the standard -40 C offset.
 * 0x4A0 bytes 2..4: total odometer, unsigned big-endian, 1/64 kilometre.
 */
internal class HsCanVehicleDecoder {
    var signals = HsCanVehicleSignals()
        private set

    fun reset() {
        signals = HsCanVehicleSignals()
    }

    fun accept(frame: Esp32CanFrame): HsCanVehicleSignals {
        if (frame.bus != Esp32CanBus.HS_CAN || frame.extended || frame.rtr) return signals
        when (frame.identifier) {
            STEERING_ID -> if (frame.data.size >= 4) {
                val raw = ((frame.data[2].toInt() and 0xff) shl 8) or
                    (frame.data[3].toInt() and 0xff)
                // After ignition the sensor reports "not initialised" (byte 0 0x64, angle 0x7FFF)
                // until the wheel is turned, then 0xA4 with the real angle. Either marker rejects it.
                val notInitialised = frame.data[0].toInt() and STEERING_STATUS_MASK == STEERING_NOT_INITIALISED
                if (!notInitialised && raw != STEERING_INVALID_RAW) {
                    signals = signals.copy(steeringAngleDegrees = raw.toShort() / 16f)
                }
            }

            BODY_DYNAMICS_ID -> if (frame.data.size >= 5) {
                val byte0 = frame.data[0].toInt() and 0xff
                val turnState = byte0 and TURN_STATE_MASK
                signals = signals.copy(
                    leftIndicator = turnState == TURN_LEFT_EDGE || turnState == TURN_LEFT_HELD,
                    rightIndicator = turnState == TURN_RIGHT_EDGE || turnState == TURN_RIGHT_HELD,
                    exteriorLightsCandidate = byte0 and EXTERIOR_LIGHTS_CANDIDATE != 0,
                    highBeam = frame.data[4].toInt() and HIGH_BEAM_MASK != 0,
                    fuelRawByte = frame.data.getOrNull(5)?.toInt()?.and(0xff),
                )
            }

            CHASSIS_STATUS_ID -> if (frame.data.size >= 8) {
                signals = signals.copy(
                    handbrake = frame.data[7].toInt() and HANDBRAKE_MASK != 0,
                )
            }

            WHEEL_SPEED_ID -> if (frame.data.size >= 8) {
                fun wheel(offset: Int): Float = unsigned16(frame.data, offset) * WHEEL_SPEED_SCALE
                signals = signals.copy(
                    // Corner order validated against steering angle in 18,846 moving turn samples.
                    wheelSpeeds = WheelSpeeds(wheel(0), wheel(2), wheel(4), wheel(6)),
                )
            }

            VEHICLE_SPEED_ID -> if (frame.data.size >= 3) {
                signals = signals.copy(vehicleSpeedKmh = unsigned16(frame.data, 1) / 128f)
            }

            ENGINE_ID -> if (frame.data.size >= 3) {
                signals = signals.copy(engineRpm = unsigned16(frame.data, 1) / 4)
            }

            COOLANT_ID -> if (frame.data.size >= 2) {
                signals = signals.copy(coolantCelsius = (frame.data[1].toInt() and 0xff) - 40)
            }

            ODOMETER_ID -> if (frame.data.size >= 5) {
                val raw = ((frame.data[2].toLong() and 0xff) shl 16) or
                    ((frame.data[3].toLong() and 0xff) shl 8) or
                    (frame.data[4].toLong() and 0xff)
                signals = signals.copy(odometerKm = raw / ODOMETER_STEPS_PER_KM)
            }
        }
        return signals
    }

    private fun unsigned16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private companion object {
        const val STEERING_ID = 0x180L
        const val STEERING_STATUS_MASK = 0xC0
        const val STEERING_NOT_INITIALISED = 0x40
        const val STEERING_INVALID_RAW = 0x7FFF
        const val BODY_DYNAMICS_ID = 0x381L
        const val CHASSIS_STATUS_ID = 0x380L
        const val WHEEL_SPEED_ID = 0x280L
        const val VEHICLE_SPEED_ID = 0x410L
        const val ENGINE_ID = 0x110L
        const val COOLANT_ID = 0x510L
        const val ODOMETER_ID = 0x4A0L
        const val ODOMETER_STEPS_PER_KM = 64.0
        const val WHEEL_SPEED_SCALE = 15f / 512f
        const val TURN_STATE_MASK = 0xE0
        const val TURN_LEFT_EDGE = 0x20
        const val TURN_LEFT_HELD = 0x60
        const val TURN_RIGHT_EDGE = 0x40
        const val TURN_RIGHT_HELD = 0x80
        const val EXTERIOR_LIGHTS_CANDIDATE = 0x08
        const val HIGH_BEAM_MASK = 0x80
        const val HANDBRAKE_MASK = 0x80
    }
}

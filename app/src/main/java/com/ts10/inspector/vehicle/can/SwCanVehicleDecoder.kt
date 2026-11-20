package com.ts10.inspector.vehicle.can

import com.ts10.inspector.R
import androidx.annotation.StringRes

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

/** Passive decoder for the SW/LS-CAN signals reproduced on the vehicle on 2026-09-19. */
internal class SwCanVehicleDecoder {
    var signals = SwCanVehicleSignals()
        private set

    fun reset() {
        signals = SwCanVehicleSignals()
    }

    fun accept(frame: Esp32CanFrame): SwCanVehicleSignals {
        if (frame.bus != Esp32CanBus.SW_CAN || frame.extended || frame.rtr) return signals
        when (frame.identifier) {
            CENTRAL_LOCK_COMMAND_ID -> decodeCentralLockCommand(frame.data)
            COLUMN_SWITCH_ID -> decodeColumnSwitch(frame.data)
            ODOMETER_ID -> decodeOdometer(frame.data)
            BODY_AND_WINDOWS_ID -> decodeBodyAndWindows(frame.data)
            TURN_LAMPS_ID -> decodeTurnLamps(frame.data)
            CENTRAL_CONTROLS_ID -> decodeCentralControls(frame.data)
            SERVICE_BRAKE_ID -> if (frame.data.size >= 3) {
                signals = signals.copy(
                    serviceBrakePressed = frame.data[2].u8() and SERVICE_BRAKE_MASK != 0,
                )
            }
            PEDAL_SWITCH_ID -> if (frame.data.size == 1) {
                // 2026-10-11 drive: bit 0x02 follows the clutch independently of 0x360.
                // Preserve the full byte; 0x04/0x08 still look like redundant brake contacts.
                val raw = frame.data[0].u8()
                signals = signals.copy(
                    clutchPressed = raw and CLUTCH_MASK != 0,
                    pedalSwitchRaw = raw,
                )
            }
            HANDBRAKE_ID -> if (frame.data.size >= 2) {
                signals = signals.copy(
                    handbrake = frame.data[1].u8() and HANDBRAKE_MASK != 0,
                    rearFogOn = frame.data[1].u8() and REAR_FOG_MASK != 0,
                )
            }
            // 2026-11-15 drive: each front-fog press (0x305 byte 2 bit 0x20) toggled this bit.
            LAMP_STATUS_ID -> if (frame.data.isNotEmpty()) {
                signals = signals.copy(frontFogOn = frame.data[0].u8() and FRONT_FOG_MASK != 0)
            }
            FUEL_ID -> if (frame.data.size >= 2) {
                signals = signals.copy(fuelRawByte = frame.data[1].u8())
            }
            OUTDOOR_TEMPERATURE_ID -> if (frame.data.size >= 2) {
                signals = signals.copy(outdoorTemperatureCelsius = frame.data[1].u8() / 2f - 40f)
            }
            BATTERY_VOLTAGE_ID -> if (frame.data.size >= 2) {
                signals = signals.copy(batteryVoltage = frame.data[1].u8() / 8f)
            }
            IGNITION_KEY_ID -> if (frame.data.size == 1) {
                signals = signals.copy(ignitionKeyIndex = frame.data[0].u8().takeIf { it in 1..2 })
            }
            WINDOW_COMMAND_ID -> decodeWindowCommand(frame.data)
        }
        return signals
    }

    private fun decodeColumnSwitch(data: ByteArray) {
        if (data.size < 2) return
        val byte0 = data[0].u8()
        val byte1 = data[1].u8()
        val mode = when (byte1) {
            0x00 -> SwWiperMode.OFF
            0xA0 -> SwWiperMode.SPEED_1
            0xB0 -> SwWiperMode.SPEED_2
            0xD0 -> SwWiperMode.SPEED_3
            0xAA -> SwWiperMode.WASHER
            else -> SwWiperMode.UNKNOWN
        }
        signals = signals.copy(
            highBeamRequest = byte0 and (HIGH_BEAM_PULL_MASK or HIGH_BEAM_PUSH_MASK) != 0,
            wiperMode = mode,
        )
    }

    private fun decodeOdometer(data: ByteArray) {
        if (data.size < 5) return
        val raw = (data[2].u8().toLong() shl 16) or
            (data[3].u8().toLong() shl 8) or data[4].u8().toLong()
        signals = signals.copy(odometerKm = raw / ODOMETER_STEPS_PER_KM)
    }

    private fun decodeBodyAndWindows(data: ByteArray) {
        if (data.size < 3) return
        val openings = data[2].u8()
        signals = signals.copy(
            driverDoorOpen = openings and DRIVER_DOOR_MASK != 0,
            passengerDoorOpen = openings and PASSENGER_DOOR_MASK != 0,
            trunkOpen = openings and TRUNK_MASK != 0,
            centralLocked = data.contentEquals(CENTRAL_LOCKED_STATE),
        )
    }

    private fun decodeCentralLockCommand(data: ByteArray) {
        if (data.size != 4 || data[1].u8() != 0x00 || data[2].u8() != 0xEA || data[3].u8() != 0xA1) {
            return
        }
        val action = when (data[0].u8()) {
            CENTRAL_LOCK_PULSE -> SwCentralLockAction.LOCKING
            CENTRAL_UNLOCK_PULSE -> SwCentralLockAction.UNLOCKING
            else -> return
        }
        signals = signals.copy(centralLockAction = action)
    }

    private fun decodeTurnLamps(data: ByteArray) {
        if (data.size < 3) return
        val payload = Triple(data[0].u8(), data[1].u8(), data[2].u8())
        signals = when (payload) {
            TURN_LEFT -> signals.copy(leftIndicator = true, rightIndicator = false, hazard = false)
            TURN_RIGHT -> signals.copy(
                leftIndicator = false,
                rightIndicator = true,
                hazard = false,
                lampFault = null,
            )
            TURN_RIGHT_LAMP_FAULT -> signals.copy(
                leftIndicator = false,
                rightIndicator = true,
                hazard = false,
                lampFault = SwLampFault.RIGHT_INDICATOR,
            )
            TURN_HAZARD -> signals.copy(leftIndicator = true, rightIndicator = true, hazard = true)
            TURN_OFF -> signals.copy(leftIndicator = false, rightIndicator = false, hazard = false)
            else -> signals
        }
    }

    private fun decodeCentralControls(data: ByteArray) {
        if (data.size < 7) return
        val action = when {
            data.contentEquals(SPORT_BUTTON) -> SwCentralControlAction.SPORT
            data.contentEquals(ESP_BUTTON) -> SwCentralControlAction.ESP
            else -> return
        }
        signals = signals.copy(centralControlAction = action)
    }

    private fun decodeWindowCommand(data: ByteArray) {
        if (data.size < 2) return
        val action = when (data[1].u8()) {
            WINDOW_OPEN_COMMAND -> SwWindowAction.OPENING
            WINDOW_CLOSE_COMMAND -> SwWindowAction.CLOSING
            else -> return
        }
        signals = signals.copy(windowAction = action)
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private companion object {
        const val CENTRAL_LOCK_COMMAND_ID = 0x135L
        const val WINDOW_COMMAND_ID = 0x160L
        const val COLUMN_SWITCH_ID = 0x175L
        const val ODOMETER_ID = 0x190L
        const val BODY_AND_WINDOWS_ID = 0x230L
        const val TURN_LAMPS_ID = 0x260L
        const val CENTRAL_CONTROLS_ID = 0x305L
        const val PEDAL_SWITCH_ID = 0x115L
        const val SERVICE_BRAKE_ID = 0x360L
        const val HANDBRAKE_ID = 0x370L
        const val FUEL_ID = 0x375L
        const val OUTDOOR_TEMPERATURE_ID = 0x445L
        const val BATTERY_VOLTAGE_ID = 0x500L
        const val IGNITION_KEY_ID = 0x425L
        const val LAMP_STATUS_ID = 0x350L

        const val DRIVER_DOOR_MASK = 0x40
        const val TRUNK_MASK = 0x04
        const val PASSENGER_DOOR_MASK = 0x10
        const val SERVICE_BRAKE_MASK = 0x40
        const val CLUTCH_MASK = 0x02
        const val HANDBRAKE_MASK = 0x01
        // 2026-11-15: rear-fog presses (0x305 byte 2 bit 0x10) toggled 0x370 byte 1 bit 0x80.
        const val REAR_FOG_MASK = 0x80
        const val FRONT_FOG_MASK = 0x20
        const val HIGH_BEAM_PULL_MASK = 0x08
        const val HIGH_BEAM_PUSH_MASK = 0x20
        const val WINDOW_OPEN_COMMAND = 0x10
        const val WINDOW_CLOSE_COMMAND = 0x40
        const val ODOMETER_STEPS_PER_KM = 64.0
        const val CENTRAL_LOCK_PULSE = 0xBC
        const val CENTRAL_UNLOCK_PULSE = 0x7C

        val TURN_OFF = Triple(0x00, 0x00, 0x00)
        val TURN_LEFT = Triple(0x25, 0x43, 0x7F)
        val TURN_RIGHT = Triple(0x3A, 0x43, 0x7F)
        // Captured 2026-10-25 with the right-rear lamp connector unplugged. The BCM repeats this
        // payload every ~320 ms instead of the normal ~681 ms, matching the cluster hyperflash.
        val TURN_RIGHT_LAMP_FAULT = Triple(0x3A, 0x21, 0x7C)
        val TURN_HAZARD = Triple(0x1F, 0x43, 0x7F)
        val CENTRAL_LOCKED_STATE = byteArrayOf(0x05, 0x00, 0x00, 0x00, 0x00, 0x09, 0x00)
        // Confirmed by the labelled 2026-09-27 SPORT and ESP button captures.
        val SPORT_BUTTON = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x3A, 0x51, 0x00)
        val ESP_BUTTON = byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x3B, 0x51, 0x00)
    }
}

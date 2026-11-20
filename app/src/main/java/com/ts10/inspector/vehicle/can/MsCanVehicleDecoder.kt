package com.ts10.inspector.vehicle.can

internal data class MsCanClimateSignals(
    val temperature: String,
    val automatic: Boolean,
    val cooling: Boolean,
    val fanLevel: Int?,
    val airDirection: String,
)

internal data class MsCanVehicleSignals(
    val vehicleSpeedKmh: Float? = null,
    val engineRpm: Int? = null,
    val reverse: Boolean? = null,
    val motionState: Int? = null,
    val climate: MsCanClimateSignals? = null,
)

internal enum class MsCanVehicleUpdate { MOTION, CLIMATE }

/** Passive decoder for community candidates confirmed against the 9 September captures. */
internal class MsCanVehicleDecoder {
    var signals = MsCanVehicleSignals()
        private set

    private var climatePayload = ByteArray(0)
    private var climateExpectedLength = 0
    private var climateNextSequence = 0

    fun reset() {
        signals = MsCanVehicleSignals()
        resetClimateAssembly()
    }

    fun accept(frame: Esp32CanFrame): MsCanVehicleUpdate? {
        if (frame.bus != Esp32CanBus.MS_CAN || frame.extended || frame.rtr) return null
        return when (frame.identifier) {
            MOTION_ID -> decodeMotion(frame)
            CLIMATE_ID -> decodeClimateFragment(frame)
            else -> null
        }
    }

    private fun decodeMotion(frame: Esp32CanFrame): MsCanVehicleUpdate? {
        if (frame.dlc < 7 || frame.data.size < 7 || frame.data.u8(0) != 0x46) return null
        val rawRpm = (frame.data.u8(2) shl 8) or frame.data.u8(3)
        val rawSpeed = (frame.data.u8(4) shl 8) or frame.data.u8(5)
        val motionState = frame.data.u8(6)
        signals = signals.copy(
            engineRpm = rawRpm / 4,
            vehicleSpeedKmh = rawSpeed / 128f,
            reverse = motionState and REVERSE_MASK != 0,
            motionState = motionState,
        )
        return MsCanVehicleUpdate.MOTION
    }

    private fun decodeClimateFragment(frame: Esp32CanFrame): MsCanVehicleUpdate? {
        if (frame.dlc < 1 || frame.data.isEmpty()) return null
        val pci = frame.data.u8(0)
        return when (pci ushr 4) {
            0x1 -> {
                if (frame.dlc < 3 || frame.data.size < 3) return null
                climateExpectedLength = ((pci and 0x0f) shl 8) or frame.data.u8(1)
                if (climateExpectedLength !in 1..MAX_ISO_TP_PAYLOAD) {
                    resetClimateAssembly()
                    return null
                }
                climatePayload = frame.data.copyOfRange(2, frame.data.size)
                climateNextSequence = 1
                finishClimateIfComplete()
            }

            0x2 -> {
                if (climateExpectedLength == 0 || (pci and 0x0f) != climateNextSequence) {
                    resetClimateAssembly()
                    return null
                }
                climatePayload += frame.data.copyOfRange(1, frame.data.size)
                climateNextSequence = (climateNextSequence + 1) and 0x0f
                finishClimateIfComplete()
            }

            else -> null
        }
    }

    private fun finishClimateIfComplete(): MsCanVehicleUpdate? {
        if (climateExpectedLength == 0 || climatePayload.size < climateExpectedLength) return null
        val payload = climatePayload.copyOf(climateExpectedLength)
        resetClimateAssembly()
        val climate = decodeClimatePayload(payload) ?: return null
        signals = signals.copy(climate = climate)
        return MsCanVehicleUpdate.CLIMATE
    }

    private fun decodeClimatePayload(payload: ByteArray): MsCanClimateSignals? {
        if (payload.size < 6 || payload.u8(0) != 0xC0 || payload.u8(1) != 0x00 ||
            payload.u8(3) != CLIMATE_MESSAGE_TYPE
        ) return null

        val fields = mutableMapOf<Int, List<Int>>()
        var offset = 4
        while (offset + 2 <= payload.size) {
            val id = payload.u8(offset)
            val characters = payload.u8(offset + 1)
            val byteCount = characters * 2
            if (offset + 2 + byteCount > payload.size) return null
            fields[id] = (0 until characters).map { index ->
                val charOffset = offset + 2 + index * 2
                (payload.u8(charOffset) shl 8) or payload.u8(charOffset + 1)
            }
            offset += 2 + byteCount
        }

        val temperature = fields[FIELD_TEMPERATURE]?.toDisplayText()?.takeIf(String::isNotBlank)
            ?: return null
        val directionCode = fields[FIELD_DIRECTION]?.firstOrNull()
        val ecoText = fields[FIELD_ECO]?.toDisplayText()?.trim().orEmpty()
        val fanCharacters = fields[FIELD_FAN].orEmpty()
        val fanText = fanCharacters.filter { it < 0x80 }.map { it.toChar() }.joinToString("").trim()
        val automatic = directionCode == DIRECTION_AUTO || fanText.equals("A", ignoreCase = true)
        return MsCanClimateSignals(
            temperature = temperature,
            automatic = automatic,
            cooling = !ecoText.equals("ECO", ignoreCase = true),
            fanLevel = fanText.toIntOrNull()?.coerceIn(0, 7),
            airDirection = directionLabel(directionCode),
        )
    }

    private fun resetClimateAssembly() {
        climatePayload = ByteArray(0)
        climateExpectedLength = 0
        climateNextSequence = 0
    }

    private fun List<Int>.toDisplayText(): String = buildString {
        for (code in this@toDisplayText) when (code) {
            0x00B0 -> append('\u00b0')
            in 0x20..0x7e -> append(code.toChar())
        }
    }

    private fun directionLabel(code: Int?): String = when (code) {
        0xE052 -> "windscreen + face + feet"
        0xE053 -> "windscreen"
        0xE054 -> "windscreen + face"
        0xE055 -> "face"
        0xE056 -> "face + feet"
        0xE057 -> "feet"
        0xE058 -> "windscreen + feet"
        DIRECTION_AUTO -> "AUTO"
        else -> "none"
    }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xff

    companion object {
        const val MOTION_ID = 0x4E8L
        const val CLIMATE_ID = 0x6C8L
        private const val REVERSE_MASK = 0x04
        private const val MAX_ISO_TP_PAYLOAD = 256
        private const val CLIMATE_MESSAGE_TYPE = 0x0A
        private const val FIELD_TEMPERATURE = 0x23
        private const val FIELD_DIRECTION = 0x24
        private const val FIELD_ECO = 0x25
        private const val FIELD_FAN = 0x26
        private const val DIRECTION_AUTO = 0xE059
    }
}

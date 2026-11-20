package com.ts10.inspector.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Decodes QF CAN box packets (climate, body, doors, turn lights) into the launcher state. */
internal object QfCanBoxDecoder {
    private var dedicatedTurnDataReceived = false
    private var climateLiveDataReceived = false

    fun extractTemperatureValue(intent: Intent): String? {
        val extras = intent.extras ?: return null
        val rawValue = intent.getStringExtra(EXTRA_QF_STRING)
            ?: extras.keySet().asSequence()
                .mapNotNull { key -> extras.get(key) }
                .firstOrNull { it is String || it is Number }
                ?.toString()
        return rawValue?.trim()?.takeIf(String::isNotBlank)
    }

    fun decodeQfPacket(packet: ByteArray, source: String) {
        if (packet.size < 2) return
        QfDiagnosticsRecorder.recordPacket(packet, source)
        when (packet[0].toInt() and 0xff) {
            QF_PACKET_HEADER -> when (packet[1].toInt() and 0xff) {
                QF_PACKET_CLIMATE -> decodeClimate(packet, 2)
                QF_PACKET_CAR_BODY -> decodeCarBody(packet, 2)
            }

            QF_SDK_PACKET_HEADER -> if (packet.size >= 3) when (packet[1].toInt() and 0xff) {
                QF_PACKET_CLIMATE -> if ((packet[2].toInt() and 0xff) == QF_DOOR_PAYLOAD_SIZE) {
                    decodeDoors(packet, 3)
                } else {
                    decodeClimate(packet, 3)
                }
                QF_PACKET_CLIMATE_ALTERNATE -> decodeClimate(packet, 3)
                QF_PACKET_CAR_BODY -> decodeCarBody(packet, 3)
                QF_PACKET_DOORS -> decodeDoors(packet, 3)
                QF_PACKET_RADAR -> decodeRadar(packet, 3)
                QF_PACKET_STEERING -> decodeSteering(packet, 3)
                QF_PACKET_CAMERA -> decodeCamera(packet, 3)
                QF_PACKET_TURN_LIGHT -> decodeTurnLights(packet, 3)
            }
        }
    }

    private fun decodeClimate(packet: ByteArray, offset: Int) {
        if (packet.size < offset + QF_CLIMATE_MIN_PAYLOAD_SIZE) return
        val climate = decodeOpelClimate(packet, offset) ?: decodeGenericClimate(packet, offset)
        climateLiveDataReceived = true
        LauncherStore.state.update {
            it.copy(
                cabinTemperature = climate.temperature,
                climateMode = climate.mode,
                climatePower = climate.power,
                climateCooling = climate.cooling,
                climateFanLevel = climate.fanLevel,
                climateAuto = climate.auto,
                climateRecirculation = climate.recirculation,
                climateFrontDefrost = climate.frontDefrost,
                climateRearDefrost = climate.rearDefrost,
                climateAirDirection = climate.airDirection,
            )
        }
        cacheClimateState(
            ClimateCacheEntry(
                cabinTemperature = climate.temperature,
                climateMode = climate.mode,
                climatePower = climate.power,
                climateCooling = climate.cooling,
                climateFanLevel = climate.fanLevel,
                climateAuto = climate.auto,
                climateRecirculation = climate.recirculation,
                climateFrontDefrost = climate.frontDefrost,
                climateRearDefrost = climate.rearDefrost,
                climateAirDirection = climate.airDirection,
            ),
        )
        QfDiagnosticsRecorder.updateDiagnostics {
            val updated = it.copy(
                climatePacketCount = it.climatePacketCount + 1,
                climateDataReceived = true,
                climatePower = boolLabel(climate.power),
                climateCompressor = boolLabel(climate.cooling),
                climateAuto = boolLabel(climate.auto),
                climateRecirculation = boolLabel(climate.recirculation),
                climateFrontDefrost = boolLabel(climate.frontDefrost),
                climateRearDefrost = boolLabel(climate.rearDefrost),
                climateFan = "Level ${climate.fanLevel}",
                climateAirDirection = climate.airDirection,
                climateTemperature = climate.temperature,
            )
            val decoded = buildString {
                append("Climate: ${climate.temperature}, ${if (climate.cooling) "A/C" else "VENT"}")
                append(", fan ${climate.fanLevel}")
                append(", ${if (climate.auto) "AUTO" else "manual"}")
                append(", ${climate.airDirection}")
                if (climate.recirculation) append(", recirculation")
                if (climate.frontDefrost) append(", front demist")
                if (climate.rearDefrost) append(", rear demist")
            }
            if (
                it.climateTemperature == climate.temperature &&
                it.climatePower == boolLabel(climate.power) &&
                it.climateCompressor == boolLabel(climate.cooling) &&
                it.climateAuto == boolLabel(climate.auto) &&
                it.climateRecirculation == boolLabel(climate.recirculation) &&
                it.climateFrontDefrost == boolLabel(climate.frontDefrost) &&
                it.climateRearDefrost == boolLabel(climate.rearDefrost) &&
                it.climateFan == "Nivel ${climate.fanLevel}" &&
                it.climateAirDirection == climate.airDirection
            ) {
                updated
            } else {
                addLog(updated, decoded)
            }
        }
    }

    private fun decodeOpelClimate(packet: ByteArray, offset: Int): ClimateSnapshot? {
        val isOpelSdkClimate = (packet[0].toInt() and 0xff) == QF_SDK_PACKET_HEADER &&
            packet.getOrNull(1)?.toInt()?.and(0xff) == QF_PACKET_CLIMATE &&
            packet.getOrNull(2)?.toInt()?.and(0xff) == QF_OPEL_CLIMATE_DATA_TYPE
        if (!isOpelSdkClimate) return null
        val payloadOffset = when {
            packet.getOrNull(offset)?.toInt()?.and(0xff) == QF_OPEL_CLIMATE_DATA_TYPE -> offset + 1
            offset > 0 && packet.getOrNull(offset - 1)?.toInt()?.and(0xff) == QF_OPEL_CLIMATE_DATA_TYPE -> offset
            else -> offset
        }
        if (packet.size < payloadOffset + QF_OPEL_CLIMATE_PAYLOAD_SIZE) return null
        return decodeGenericClimate(packet, payloadOffset)
    }

    private fun decodeGenericClimate(packet: ByteArray, offset: Int): ClimateSnapshot {
        val status = packet[offset].toInt() and 0xff
        val secondaryStatus = packet[offset + 1].toInt() and 0xff
        val fanAndDirection = packet[offset + 2].toInt() and 0xff
        val fanLevel = (fanAndDirection and 0x0f).coerceIn(0, QF_MAX_FAN_LEVEL)
        val power = status and 0x80 != 0
        val cooling = status and 0x40 != 0
        // Raise/Opel does not expose AUTO in the generic status bits used by other QF
        // profiles. The five captures from 25/07 show that AUTO is represented by an
        // active blower with no manual outlet selected in the high nibble.
        val auto = power && fanLevel > 0 && fanAndDirection and QF_AIR_DIRECTION_MASK == 0
        val recirculation = status and 0x10 != 0
        val frontDefrost = secondaryStatus and 0x80 != 0
        val rearDefrost = secondaryStatus and 0x40 != 0
        val airDirection = qfAirDirectionLabel(fanAndDirection, auto)
        return ClimateSnapshot(
            temperature = decodeQfTemperature(packet[offset + 5]),
            mode = climateMode(power, cooling, auto, fanLevel),
            power = power,
            cooling = cooling,
            fanLevel = fanLevel,
            auto = auto,
            recirculation = recirculation,
            frontDefrost = frontDefrost,
            rearDefrost = rearDefrost,
            airDirection = airDirection,
        )
    }

    private fun qfAirDirectionLabel(direction: Int, auto: Boolean): String = buildList {
        if (direction and 0x80 != 0) add("up")
        if (direction and 0x40 != 0) add("middle")
        if (direction and 0x20 != 0) add("feet")
        if (direction and 0x10 != 0) add("roof")
    }.ifEmpty { listOf(if (auto) "AUTO" else "none") }.joinToString(" + ")

    private fun climateMode(power: Boolean, cooling: Boolean, auto: Boolean, fanLevel: Int): String = when {
        !power -> "OFF"
        auto && cooling -> "AUTO A/C - $fanLevel"
        auto -> "AUTO VENT - $fanLevel"
        cooling -> "A/C - $fanLevel"
        else -> "VENT - $fanLevel"
    }

    fun restoreCachedClimate(context: Context) {
        if (climateLiveDataReceived) return
        val cached = climatePreferences(context).takeIf { it.contains(KEY_CLIMATE_MODE) } ?: return
        val entry = ClimateCacheEntry(
            cabinTemperature = cached.getString(KEY_CLIMATE_TEMPERATURE, "--\u00b0") ?: "--\u00b0",
            climateMode = cached.getString(KEY_CLIMATE_MODE, "NO DATA") ?: "NO DATA",
            climatePower = cached.getBoolean(KEY_CLIMATE_POWER, false),
            climateCooling = cached.getBoolean(KEY_CLIMATE_COOLING, false),
            climateFanLevel = cached.getInt(KEY_CLIMATE_FAN_LEVEL, 0),
            climateAuto = cached.getBoolean(KEY_CLIMATE_AUTO, false),
            climateRecirculation = cached.getBoolean(KEY_CLIMATE_RECIRCULATION, false),
            climateFrontDefrost = cached.getBoolean(KEY_CLIMATE_FRONT_DEFROST, false),
            climateRearDefrost = cached.getBoolean(KEY_CLIMATE_REAR_DEFROST, false),
            climateAirDirection = cached.getString(KEY_CLIMATE_AIR_DIRECTION, "none") ?: "none",
        )
        LauncherStore.state.update {
            it.copy(
                cabinTemperature = entry.cabinTemperature,
                climateMode = entry.climateMode,
                climatePower = entry.climatePower,
                climateCooling = entry.climateCooling,
                climateFanLevel = entry.climateFanLevel,
                climateAuto = entry.climateAuto,
                climateRecirculation = entry.climateRecirculation,
                climateFrontDefrost = entry.climateFrontDefrost,
                climateRearDefrost = entry.climateRearDefrost,
                climateAirDirection = entry.climateAirDirection,
            )
        }
        QfDiagnosticsRecorder.updateDiagnostics {
            addLog(
                it.copy(
                    climatePower = boolLabel(entry.climatePower),
                    climateCompressor = boolLabel(entry.climateCooling),
                    climateAuto = boolLabel(entry.climateAuto),
                    climateRecirculation = boolLabel(entry.climateRecirculation),
                    climateFrontDefrost = boolLabel(entry.climateFrontDefrost),
                    climateRearDefrost = boolLabel(entry.climateRearDefrost),
                    climateFan = "Nivel ${entry.climateFanLevel}",
                    climateAirDirection = entry.climateAirDirection,
                    climateTemperature = entry.cabinTemperature,
                    lastSource = "Cache clima",
                ),
                "Climate restored from local cache",
            )
        }
    }

    private fun cacheClimateState(entry: ClimateCacheEntry) {
        val context = LauncherStore.applicationContext ?: return
        climatePreferences(context).edit()
            .putString(KEY_CLIMATE_TEMPERATURE, entry.cabinTemperature)
            .putString(KEY_CLIMATE_MODE, entry.climateMode)
            .putBoolean(KEY_CLIMATE_POWER, entry.climatePower)
            .putBoolean(KEY_CLIMATE_COOLING, entry.climateCooling)
            .putInt(KEY_CLIMATE_FAN_LEVEL, entry.climateFanLevel)
            .putBoolean(KEY_CLIMATE_AUTO, entry.climateAuto)
            .putBoolean(KEY_CLIMATE_RECIRCULATION, entry.climateRecirculation)
            .putBoolean(KEY_CLIMATE_FRONT_DEFROST, entry.climateFrontDefrost)
            .putBoolean(KEY_CLIMATE_REAR_DEFROST, entry.climateRearDefrost)
            .putString(KEY_CLIMATE_AIR_DIRECTION, entry.climateAirDirection)
            .apply()
    }

    private fun climatePreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(CLIMATE_CACHE_FILE, Context.MODE_PRIVATE)

    private fun decodeCarBody(packet: ByteArray, offset: Int) {
        if (packet.size < offset + QF_CAR_BODY_MIN_PAYLOAD_SIZE) return
        val lights = packet[offset].toInt() and 0xff
        val secondaryLights = packet[offset + 1].toInt() and 0xff
        val chassis = packet[offset + 4].toInt() and 0xff
        val canSpeed = ((packet[offset + 5].toInt() and 0xff) shl 8) or
            (packet[offset + 6].toInt() and 0xff)
        val engineRpm = ((packet[offset + 7].toInt() and 0xff) shl 8) or
            (packet[offset + 8].toInt() and 0xff)
        val fuelLitres = (((packet[offset + 11].toInt() and 0xff) shl 8) or
            (packet[offset + 12].toInt() and 0xff)) / 10f
        FuelRangeEstimator.updateCanFuelLiters(fuelLitres)
        val dippedBeam = lights and 0x40 != 0
        val highBeam = lights and 0x80 != 0
        val positionLights = secondaryLights and 0xc0 != 0
        val now = SystemClock.elapsedRealtime()
        LauncherStore.state.update {
            it.copy(
                leftBlinker = if (dedicatedTurnDataReceived) it.leftBlinker else lights and 0x04 != 0,
                rightBlinker = if (dedicatedTurnDataReceived) it.rightBlinker else lights and 0x08 != 0,
                hazard = if (dedicatedTurnDataReceived) it.hazard else lights and 0x02 != 0,
                handbrake = chassis and 0x10 != 0,
                headlights = dippedBeam || highBeam || positionLights || lights and 0x01 != 0,
                dippedBeam = dippedBeam,
                highBeam = highBeam,
                positionLights = positionLights,
                lowFuel = fuelLitres in 0.1f..LOW_FUEL_LITRES,
                brakeAlert = chassis and 0x10 != 0 || secondaryLights and 0x18 != 0,
            )
        }
        VehicleStateFusion.onQfSpeed(canSpeed, now)
        QfDiagnosticsRecorder.updateDiagnostics {
            it.copy(
                carPacketCount = it.carPacketCount + 1,
                carDataReceived = true,
                canSpeed = "$canSpeed km/h",
                engineRpm = "$engineRpm rpm",
                fuelCapacity = String.format(Locale.getDefault(), "%.1f L", fuelLitres),
                leftBlinker = boolLabel(lights and 0x04 != 0),
                rightBlinker = boolLabel(lights and 0x08 != 0),
                hazard = boolLabel(lights and 0x02 != 0),
                dippedBeam = boolLabel(dippedBeam),
                highBeam = boolLabel(highBeam),
                positionLights = boolLabel(positionLights),
                handbrake = boolLabel(chassis and 0x10 != 0),
                brakeWarning = boolLabel(secondaryLights and 0x18 != 0),
            )
        }
    }

    private fun decodeDoors(packet: ByteArray, offset: Int) {
        if (packet.size <= offset) return
        val doors = packet[offset].toInt() and 0xff
        // Astra H logs validate 0x80/0x40. Remaining masks come from the generic QF
        // model and stay provisional until this CAN Box emits them.
        QfDiagnosticsRecorder.updateDiagnostics {
            it.copy(
                doorPacketCount = it.doorPacketCount + 1,
                frontLeftDoor = boolLabel(doors and 0x80 != 0),
                frontRightDoor = boolLabel(doors and 0x40 != 0),
                rearLeftDoor = boolLabel(doors and 0x20 != 0),
                rearRightDoor = boolLabel(doors and 0x10 != 0),
                trunk = boolLabel(doors and 0x08 != 0),
                bonnet = boolLabel(doors and 0x04 != 0),
            )
        }
        LauncherStore.state.update {
            it.copy(
                frontLeftDoorOpen = doors and 0x80 != 0,
                frontRightDoorOpen = doors and 0x40 != 0,
                rearLeftDoorOpen = doors and 0x20 != 0,
                rearRightDoorOpen = doors and 0x10 != 0,
                trunkOpen = doors and 0x08 != 0,
                bonnetOpen = doors and 0x04 != 0,
            )
        }
    }

    private fun decodeSteering(packet: ByteArray, offset: Int) {
        if (packet.size < offset + 2) return
        val low = packet[offset].toInt() and 0xff
        val high = packet[offset + 1].toInt() and 0xff
        val magnitude = low or ((high and 0x7f) shl 8)
        val angle = if (high and 0x80 != 0) -magnitude else magnitude
        QfDiagnosticsRecorder.updateDiagnostics {
            it.copy(
                steeringPacketCount = it.steeringPacketCount + 1,
                steeringAngle = "$angle grados",
            )
        }
    }

    private fun decodeRadar(packet: ByteArray, offset: Int) {
        if (packet.size <= offset) return
        val available = minOf(packet.size - offset - 1, QF_RADAR_LEVEL_COUNT).coerceAtLeast(0)
        val levels = (0 until available).joinToString(" / ") {
            (packet[offset + it].toInt() and 0xff).toString()
        }
        QfDiagnosticsRecorder.updateDiagnostics {
            it.copy(
                radarPacketCount = it.radarPacketCount + 1,
                radarLevels = levels.ifEmpty { "Paquete vacio" },
            )
        }
    }

    private fun decodeCamera(packet: ByteArray, offset: Int) {
        if (packet.size <= offset) return
        QfDiagnosticsRecorder.updateDiagnostics {
            it.copy(
                cameraPacketCount = it.cameraPacketCount + 1,
                cameraState = boolLabel(packet[offset].toInt() and 0xff != 0),
            )
        }
    }

    private fun decodeTurnLights(packet: ByteArray, offset: Int) {
        if (packet.size <= offset) return
        val value = packet[offset].toInt() and 0xff
        val left = value and 0x80 != 0
        val right = value and 0x40 != 0
        val hazard = value and 0x20 != 0
        dedicatedTurnDataReceived = true
        LauncherStore.state.update {
            it.copy(
                leftBlinker = left,
                rightBlinker = right,
                hazard = hazard,
            )
        }
        QfDiagnosticsRecorder.mutableDiagnostics.update {
            it.copy(
                turnPacketCount = it.turnPacketCount + 1,
                leftBlinker = boolLabel(left),
                rightBlinker = boolLabel(right),
                hazard = boolLabel(hazard),
            )
        }
    }

    private fun decodeQfTemperature(value: Byte): String {
        val unsigned = value.toInt() and 0xff
        return when (unsigned) {
            0 -> "LO"
            0xff -> "HI"
            0xfe -> "--°"
            else -> String.format(Locale.getDefault(), "%.1f°", unsigned / 2f)
        }
    }

    private const val QF_MAX_FAN_LEVEL = 7
    private const val QF_AIR_DIRECTION_MASK = 0xf0
    private const val CLIMATE_CACHE_FILE = "launcher_climate_cache"
    private const val EXTRA_QF_STRING = "extra_string"
    private const val QF_SDK_PACKET_HEADER = 0x98
    private const val QF_OPEL_CLIMATE_DATA_TYPE = 0x19
    private const val QF_OPEL_CLIMATE_PAYLOAD_SIZE = 8
    private const val QF_RADAR_LEVEL_COUNT = 8
    private const val QF_CLIMATE_MIN_PAYLOAD_SIZE = 7
    private const val QF_CAR_BODY_MIN_PAYLOAD_SIZE = 13
    private const val LOW_FUEL_LITRES = 7f
    private const val KEY_CLIMATE_TEMPERATURE = "climate_temperature"
    private const val KEY_CLIMATE_MODE = "climate_mode"
    private const val KEY_CLIMATE_POWER = "climate_power"
    private const val KEY_CLIMATE_COOLING = "climate_cooling"
    private const val KEY_CLIMATE_FAN_LEVEL = "climate_fan_level"
    private const val KEY_CLIMATE_AUTO = "climate_auto"
    private const val KEY_CLIMATE_RECIRCULATION = "climate_recirculation"
    private const val KEY_CLIMATE_FRONT_DEFROST = "climate_front_defrost"
    private const val KEY_CLIMATE_REAR_DEFROST = "climate_rear_defrost"
    private const val KEY_CLIMATE_AIR_DIRECTION = "climate_air_direction"
}

private data class ClimateCacheEntry(
    val cabinTemperature: String,
    val climateMode: String,
    val climatePower: Boolean,
    val climateCooling: Boolean,
    val climateFanLevel: Int,
    val climateAuto: Boolean,
    val climateRecirculation: Boolean,
    val climateFrontDefrost: Boolean,
    val climateRearDefrost: Boolean,
    val climateAirDirection: String,
)

private data class ClimateSnapshot(
    val temperature: String,
    val mode: String,
    val power: Boolean,
    val cooling: Boolean,
    val fanLevel: Int,
    val auto: Boolean,
    val recirculation: Boolean,
    val frontDefrost: Boolean,
    val rearDefrost: Boolean,
    val airDirection: String,
)

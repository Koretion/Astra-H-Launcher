package com.ts10.inspector.vehicle.can.profile

import com.ts10.inspector.vehicle.can.Esp32CanBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perfiles CAN de vehículo. El incluido se carga como recurso Java (no asset) para que la app y
 * los tests JVM lo lean igual, sin `Context`.
 */
internal object VehicleCanProfiles {
    const val DEFAULT_RESOURCE = "vehicle_profiles/opel_astra_h_gtc.json"
    const val SCHEMA = 1

    val builtInText: String by lazy { readResource(DEFAULT_RESOURCE) }
    val builtIn: VehicleCanProfile by lazy { parse(builtInText) }

    /** Perfil en uso. Lo cambia [VehicleProfileManager]; por defecto, el incluido. */
    @Volatile
    private var selected: VehicleCanProfile? = null
    val active: VehicleCanProfile get() = selected ?: builtIn

    /** `null` vuelve al perfil incluido. */
    fun activate(profile: VehicleCanProfile?) {
        selected = profile
    }

    fun loadResource(path: String): VehicleCanProfile = parse(readResource(path))

    private fun readResource(path: String): String {
        val stream = VehicleCanProfiles::class.java.classLoader?.getResourceAsStream(path)
            ?: error("Vehicle CAN profile not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun parse(json: String): VehicleCanProfile {
        val root = JSONObject(json)
        val schema = root.getInt("schema")
        require(schema == SCHEMA) { "Unsupported vehicle CAN profile schema $schema" }
        val id = root.getString("id")
        return VehicleCanProfile(
            id = id,
            name = root.optString("name", id),
            frames = root.optJSONArray("frames").objects().map(::parseFrame),
            codeDecoders = root.optJSONArray("decoders").objects().map { decoder ->
                CanCodeDecoderSpec(
                    name = decoder.getString("name"),
                    bus = parseBus(decoder.getString("bus")),
                    id = number(decoder.get("id")),
                    restore = parseRestore(decoder.optString("restore", "none")),
                )
            },
        )
    }

    private fun parseFrame(frame: JSONObject): CanFrameSpec {
        val bus = parseBus(frame.getString("bus"))
        val id = number(frame.get("id"))
        val where = "${bus.label} 0x${id.toString(16).uppercase()}"
        return runCatching {
            CanFrameSpec(
                bus = bus,
                id = id,
                minLength = frame.optInt("minLength", 0),
                exactLength = if (frame.has("exactLength")) frame.getInt("exactLength") else null,
                require = frame.optJSONArray("require").objects().map(::parseCondition),
                rejectIf = frame.optJSONArray("rejectIf").objects().map(::parseCondition),
                restore = parseRestore(frame.optString("restore", "none")),
                signals = frame.optJSONArray("signals").objects().map(::parseSignal),
            )
        }.getOrElse { throw IllegalArgumentException("$where: ${it.message}", it) }
    }

    private fun parseSignal(signal: JSONObject): CanSignalSpec {
        val name = signal.getString("name")
        val type = when (val raw = signal.getString("type")) {
            "flag" -> CanSignalType.FLAG
            "unsigned" -> CanSignalType.UNSIGNED
            "signed" -> CanSignalType.SIGNED
            "enum" -> CanSignalType.ENUM
            else -> throw IllegalArgumentException("signal $name: unknown type $raw")
        }
        val byte = signal.getInt("byte")
        val length = signal.optInt("length", 1)
        require(byte in 0..63 && length in 1..8) { "signal $name: byte/length out of range" }
        val labels = signal.optJSONObject("values")?.let { values ->
            values.keys().asSequence().associate { key -> number(key) to values.getString(key) }
        }.orEmpty()
        require(type != CanSignalType.ENUM || labels.isNotEmpty()) { "signal $name: enum without values" }
        return CanSignalSpec(
            name = name,
            type = type,
            byte = byte,
            length = length,
            mask = if (signal.has("mask")) number(signal.get("mask")) else null,
            scale = signal.optDouble("scale", 1.0),
            offset = signal.optDouble("offset", 0.0),
            values = if (type == CanSignalType.ENUM) emptySet() else signal.optJSONArray("in").numbers(),
            labels = labels,
            defaultLabel = signal.optString("default").takeIf(String::isNotEmpty),
            invalid = signal.optJSONArray("invalid").numbers(),
            min = if (signal.has("min")) number(signal.get("min")) else null,
            max = if (signal.has("max")) number(signal.get("max")) else null,
            clearWhenInvalid = signal.optBoolean("clearWhenInvalid", false),
        )
    }

    private fun parseCondition(condition: JSONObject) = CanByteCondition(
        byte = condition.getInt("byte"),
        mask = if (condition.has("mask")) number(condition.get("mask")).toInt() else 0xff,
        values = condition.getJSONArray("in").numbers().mapTo(HashSet()) { it.toInt() },
    )

    private fun parseBus(value: String): Esp32CanBus = when (value.uppercase()) {
        "HS" -> Esp32CanBus.HS_CAN
        "MS" -> Esp32CanBus.MS_CAN
        "SW", "LS" -> Esp32CanBus.SW_CAN
        else -> throw IllegalArgumentException("unknown bus $value")
    }

    private fun parseRestore(value: String): CanRestorePolicy = when (value.lowercase()) {
        "none" -> CanRestorePolicy.NONE
        "state" -> CanRestorePolicy.STATE
        "perishable" -> CanRestorePolicy.PERISHABLE
        else -> throw IllegalArgumentException("unknown restore policy $value")
    }

    /** Admite enteros JSON y cadenas decimales o `0x…`. */
    private fun number(value: Any): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().let { text ->
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toLong(16) else text.toLong()
        }
        else -> throw IllegalArgumentException("not a number: $value")
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map(::getJSONObject)

    private fun JSONArray?.numbers(): Set<Long> =
        if (this == null) emptySet() else (0 until length()).mapTo(HashSet()) { number(get(it)) }
}

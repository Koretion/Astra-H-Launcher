package com.ts10.inspector.vehicle.can.profile

import com.ts10.inspector.vehicle.can.Esp32CanBus
import com.ts10.inspector.vehicle.can.Esp32CanFrame

internal enum class CanSignalType { FLAG, UNSIGNED, SIGNED, ENUM }

/** Cómo se trata una trama guardada al restaurar el estado tras reconectar el GMLAN-USB. */
internal enum class CanRestorePolicy { NONE, STATE, PERISHABLE }

/** `(data[byte] and mask) in values`. */
internal data class CanByteCondition(val byte: Int, val mask: Int, val values: Set<Int>) {
    fun matches(data: ByteArray): Boolean =
        byte < data.size && (data[byte].toInt() and 0xff and mask) in values
}

/**
 * Señal simple dentro de una trama: `length` bytes big-endian desde `byte`, con `mask` opcional.
 * - FLAG: verdadero si el valor está en `values` o, sin `values`, si es distinto de 0.
 * - UNSIGNED/SIGNED: `raw * scale + offset`.
 * - ENUM: la etiqueta de `labels`, o `defaultLabel`; sin ninguna, la trama no cambia la señal.
 */
internal data class CanSignalSpec(
    val name: String,
    val type: CanSignalType,
    val byte: Int,
    val length: Int = 1,
    val mask: Long? = null,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val values: Set<Long> = emptySet(),
    val labels: Map<Long, String> = emptyMap(),
    val defaultLabel: String? = null,
    val invalid: Set<Long> = emptySet(),
    val min: Long? = null,
    val max: Long? = null,
    val clearWhenInvalid: Boolean = false,
)

internal data class CanFrameSpec(
    val bus: Esp32CanBus,
    val id: Long,
    val minLength: Int = 0,
    val exactLength: Int? = null,
    val require: List<CanByteCondition> = emptyList(),
    val rejectIf: List<CanByteCondition> = emptyList(),
    val restore: CanRestorePolicy = CanRestorePolicy.NONE,
    val signals: List<CanSignalSpec> = emptyList(),
)

/** Trama que interpreta un decodificador escrito en código, por ejemplo ISO-TP del climatizador. */
internal data class CanCodeDecoderSpec(
    val name: String,
    val bus: Esp32CanBus,
    val id: Long,
    val restore: CanRestorePolicy = CanRestorePolicy.NONE,
)

/**
 * Resultado de una trama. Una clave presente con valor `null` significa que la señal debe
 * borrarse; una clave ausente, que la trama no aporta nada sobre ella.
 */
internal class CanDecodedSignals(private val values: Map<String, Any?>) {
    val isEmpty: Boolean get() = values.isEmpty()

    operator fun contains(name: String): Boolean = name in values

    fun flag(name: String, current: Boolean?): Boolean? = if (name in values) values[name] as Boolean? else current
    fun flagOr(name: String, current: Boolean): Boolean = values[name] as Boolean? ?: current
    fun double(name: String, current: Double?): Double? = if (name in values) values[name] as Double? else current
    fun float(name: String, current: Float?): Float? = if (name in values) (values[name] as Double?)?.toFloat() else current
    fun int(name: String, current: Int?): Int? = if (name in values) (values[name] as Double?)?.toInt() else current
    fun label(name: String): String? = values[name] as String?

    companion object {
        val EMPTY = CanDecodedSignals(emptyMap())
    }
}

internal class VehicleCanProfile(
    val id: String,
    val name: String,
    val frames: List<CanFrameSpec>,
    val codeDecoders: List<CanCodeDecoderSpec> = emptyList(),
) {
    private val framesByKey = frames.associateBy { it.bus to it.id }
    private val decodersByName = codeDecoders.associateBy { it.name }

    init {
        require(framesByKey.size == frames.size) { "Profile $id defines the same bus/ID twice" }
    }

    fun decode(frame: Esp32CanFrame): CanDecodedSignals {
        if (frame.extended || frame.rtr) return CanDecodedSignals.EMPTY
        val spec = framesByKey[frame.bus to frame.identifier] ?: return CanDecodedSignals.EMPTY
        val data = frame.data
        if (data.size < spec.minLength || spec.exactLength != null && data.size != spec.exactLength) {
            return CanDecodedSignals.EMPTY
        }
        if (!spec.require.all { it.matches(data) } || spec.rejectIf.any { it.matches(data) }) {
            return CanDecodedSignals.EMPTY
        }
        val values = LinkedHashMap<String, Any?>()
        spec.signals.forEach { signal -> decodeSignal(signal, data, values) }
        return CanDecodedSignals(values)
    }

    /** Si esta trama es la fuente de `signal` en el perfil (sea cual sea su contenido). */
    fun carries(frame: Esp32CanFrame, signal: String): Boolean =
        framesByKey[frame.bus to frame.identifier]?.signals?.any { it.name == signal } == true

    fun isCodeDecoderFrame(frame: Esp32CanFrame, decoder: String): Boolean =
        decodersByName[decoder]?.let { it.bus == frame.bus && it.id == frame.identifier } == true

    fun restorePolicy(frame: Esp32CanFrame): CanRestorePolicy {
        framesByKey[frame.bus to frame.identifier]?.let { return it.restore }
        return codeDecoders.firstOrNull { it.bus == frame.bus && it.id == frame.identifier }?.restore
            ?: CanRestorePolicy.NONE
    }

    /** Origen legible de unas señales en este perfil, por ejemplo `SW-CAN 0x230`. */
    fun sourceLabel(vararg signals: String): String = frames
        .filter { frame -> frame.signals.any { it.name in signals } }
        .joinToString(" / ") { "${it.bus.label} 0x%03X".format(java.util.Locale.ROOT, it.id) }
        .ifEmpty { "--" }

    val signalNames: Set<String> get() = frames.flatMapTo(LinkedHashSet()) { frame -> frame.signals.map { it.name } }

    private fun decodeSignal(signal: CanSignalSpec, data: ByteArray, out: MutableMap<String, Any?>) {
        if (signal.byte + signal.length > data.size) return
        var raw = 0L
        for (index in signal.byte until signal.byte + signal.length) {
            raw = (raw shl 8) or (data[index].toLong() and 0xff)
        }
        signal.mask?.let { raw = raw and it }
        if (raw in signal.invalid || signal.min != null && raw < signal.min || signal.max != null && raw > signal.max) {
            if (signal.clearWhenInvalid) out[signal.name] = null
            return
        }
        out[signal.name] = when (signal.type) {
            CanSignalType.FLAG -> if (signal.values.isEmpty()) raw != 0L else raw in signal.values
            CanSignalType.UNSIGNED -> raw * signal.scale + signal.offset
            CanSignalType.SIGNED -> {
                val bits = signal.length * 8
                val signed = if (bits < 64 && raw and (1L shl (bits - 1)) != 0L) raw - (1L shl bits) else raw
                signed * signal.scale + signal.offset
            }
            CanSignalType.ENUM -> signal.labels[raw] ?: signal.defaultLabel ?: return
        }
    }
}

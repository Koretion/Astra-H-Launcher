package com.ts10.inspector.vehicle.can

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Esp32CanStateSnapshot(
    val bootId: Long,
    val generation: Long,
    val timestampUs: Long,
    val frames: List<Esp32CanFrame>,
)

/** Separate USB record types: cached states must never look like live button presses. */
internal class Esp32CanStateSnapshotAssembler {
    private var header: ByteArray? = null
    private val frames = mutableListOf<Esp32CanFrame>()

    fun reset() { header = null; frames.clear() }

    fun accept(record: Esp32UsbRecord): Esp32CanStateSnapshot? {
        val payload = record.payload
        if (record.type == 4) {
            if (payload.size != 24 || payload[0].toInt() != 1) { reset(); return null }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt(12)
            if (count !in 0..61 || buffer.getLong(16) < 0) { reset(); return null }
            when (payload[1].toInt()) {
                1 -> { reset(); header = payload.copyOf() }
                2 -> {
                    val start = header
                    val valid = start != null && start.copyOfRange(4, 24).contentEquals(payload.copyOfRange(4, 24)) &&
                        frames.size == count
                    val snapshot = if (valid) Esp32CanStateSnapshot(
                        buffer.getInt(4).toLong() and 0xffffffffL,
                        buffer.getInt(8).toLong() and 0xffffffffL,
                        buffer.getLong(16), frames.toList(),
                    ) else null
                    reset()
                    return snapshot
                }
                else -> reset()
            }
        } else if (record.type == 3) {
            val start = header ?: return null
            val count = ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            if (payload.size != 32 || frames.size >= count ||
                ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0) != frames.size) {
                reset(); return null
            }
            val frame = Esp32CanBleProtocol.decodeFrame(payload.copyOfRange(4, 32))
            val snapshotUs = ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN).getLong(16)
            if (frame == null || frame.timestampUs !in 0..snapshotUs) { reset(); return null }
            frames += frame
        }
        return null
    }
}

internal fun canRestoreInitialState(frame: Esp32CanFrame, snapshotUs: Long, liveTimestampUs: Long?): Boolean {
    if (frame.extended || frame.rtr || frame.timestampUs !in 0..snapshotUs) return false
    if (liveTimestampUs != null && liveTimestampUs >= frame.timestampUs) return false
    val known = when (frame.bus) {
        Esp32CanBus.SW_CAN -> frame.identifier in setOf(0x115L, 0x175L, 0x190L, 0x230L, 0x260L,
            0x360L, 0x370L, 0x375L, 0x425L, 0x445L, 0x500L)
        Esp32CanBus.HS_CAN -> frame.identifier in setOf(0x110L, 0x180L, 0x280L, 0x380L,
            0x381L, 0x410L, 0x4A0L, 0x510L)
        Esp32CanBus.MS_CAN -> frame.identifier in setOf(0x450L, 0x4E8L, 0x6C8L)
        else -> false
    }
    if (!known) return false
    // Event-driven body/key state remains meaningful until changed. Motion is perishable.
    val motion = frame.bus == Esp32CanBus.HS_CAN && frame.identifier in setOf(0x110L, 0x180L, 0x280L, 0x410L) ||
        frame.bus == Esp32CanBus.MS_CAN && frame.identifier == 0x4E8L
    return !motion || snapshotUs - frame.timestampUs <= 3_000_000L
}

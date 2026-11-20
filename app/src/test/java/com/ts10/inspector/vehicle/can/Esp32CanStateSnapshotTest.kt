package com.ts10.inspector.vehicle.can

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Esp32CanStateSnapshotTest {
    private fun marker(phase: Int, count: Int = 1, generation: Int = 1) = Esp32UsbRecord(4,
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1).put(phase.toByte()).putShort(0).putInt(123).putInt(generation).putInt(count).putLong(120_000_000)
        }.array())

    private fun state(index: Int = 0, id: Int = 0x425, data: ByteArray = byteArrayOf(2)) = Esp32UsbRecord(3,
        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(index).put(3).put(0).put(data.size.toByte()).put(3)
            putInt(0).putLong(1_000).putInt(id).put(data)
        }.array())

    @Test fun `old key and final closed door are restored together only after complete snapshot`() {
        val assembler = Esp32CanStateSnapshotAssembler()
        assertNull(assembler.accept(marker(1, 2)))
        assertNull(assembler.accept(state()))
        assertNull(assembler.accept(state(1, 0x230, byteArrayOf(5, 0, 0, 0, 0, 9, 0))))
        val snapshot = assembler.accept(marker(2, 2))!!
        val decoder = SwCanVehicleDecoder()
        snapshot.frames.forEach {
            assertTrue(canRestoreInitialState(it, snapshot.timestampUs, null))
            decoder.accept(it)
        }
        assertEquals(2, decoder.signals.ignitionKeyIndex)
        assertEquals(false, decoder.signals.passengerDoorOpen)
        assertEquals(true, decoder.signals.centralLocked)
        assertNull(decoder.signals.centralLockAction)
    }

    @Test fun `truncated snapshot does not apply partial state and can be retried`() {
        val assembler = Esp32CanStateSnapshotAssembler()
        assembler.accept(marker(1, 2));assembler.accept(state())
        assertNull(assembler.accept(marker(2, 2)))
        assembler.accept(marker(1));assembler.accept(state())
        assertNotNull(assembler.accept(marker(2)))
    }

    @Test fun `rejects missing duplicate and reordered snapshot indices`() {
        for (index in listOf(-1, 1, 100)) {
            val assembler = Esp32CanStateSnapshotAssembler()
            assembler.accept(marker(1));assembler.accept(state(index))
            assertNull(assembler.accept(marker(2)))
        }
        val assembler = Esp32CanStateSnapshotAssembler()
        assembler.accept(marker(1, 2));assembler.accept(state());assembler.accept(state())
        assertNull(assembler.accept(marker(2, 2)))
    }

    @Test fun `end marker cannot commit a different generation`() {
        val assembler = Esp32CanStateSnapshotAssembler()
        assembler.accept(marker(1));assembler.accept(state())
        assertNull(assembler.accept(marker(2, generation = 2)))
    }

    @Test fun `live data wins and old buttons are never replayed`() {
        val key = Esp32CanBleProtocol.decodeFrame(state().payload.copyOfRange(4, 32))!!
        assertFalse(canRestoreInitialState(key, 120_000_000, 2_000))
        assertFalse(canRestoreInitialState(key, 120_000_000, 1_000))
        for (id in listOf(0x135L, 0x160L, 0x305L, 0x777L)) {
            assertFalse(canRestoreInitialState(key.copy(identifier = id), 120_000_000, null))
        }
    }

    @Test fun `old motion cannot become current speed on startup`() {
        val speed = Esp32CanFrame(0, 1_000, 0x410, 0, 3, byteArrayOf(0, 0, 0), Esp32CanBus.HS_CAN)
        assertFalse(canRestoreInitialState(speed, 120_000_000, null))
        assertTrue(canRestoreInitialState(speed, 1_001_000, null))
    }

    @Test fun `CRC parser can interleave live and snapshot records without mixing them`() {
        fun wire(record: Esp32UsbRecord): ByteArray {
            val body = byteArrayOf(record.type.toByte(), record.payload.size.toByte(), 0) + record.payload
            val crc = crc16Ccitt(body)
            return byteArrayOf(0xA5.toByte(), 0x5A) + body + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        }
        val parser = Esp32CanUsbStreamParser()
        val assembler = Esp32CanStateSnapshotAssembler()
        val bytes = wire(marker(1)) + wire(Esp32UsbRecord(1, state().payload.copyOfRange(4, 32))) +
            wire(state()) + wire(marker(2))
        var snapshot: Esp32CanStateSnapshot? = null
        var liveFrames = 0
        bytes.asList().chunked(5).forEach { chunk ->
            parser.accept(chunk.toByteArray()).forEach {
                if (it.type == 1) liveFrames++ else assembler.accept(it)?.let { result -> snapshot = result }
            }
        }
        assertEquals(1, liveFrames)
        assertEquals(1, snapshot!!.frames.size)
    }
}

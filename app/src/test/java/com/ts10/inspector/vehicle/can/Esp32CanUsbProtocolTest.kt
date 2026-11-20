package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Esp32CanUsbProtocolTest {
    @Test fun `reassembles split USB frame and preserves source bus`() {
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(2).put(0).put(2).put(Esp32CanBus.HS_CAN.wireValue.toByte())
            putInt(7).putLong(1234).putInt(0x321).put(0x11).put(0x22)
        }.array()
        val body = byteArrayOf(1, 28, 0) + payload
        val crc = crc16Ccitt(body)
        val packet = byteArrayOf(0xA5.toByte(), 0x5A, 1, 28, 0) + payload +
            byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        val parser = Esp32CanUsbStreamParser()
        assertTrue(parser.accept(packet.copyOfRange(0, 9)).isEmpty())
        val record = parser.accept(packet.copyOfRange(9, packet.size)).single()
        val frame = Esp32CanBleProtocol.decodeFrame(record.payload)!!
        assertEquals(Esp32CanBus.HS_CAN, frame.bus)
        assertEquals(0x321, frame.identifier)
    }

    @Test fun `decodes protocol v3 SW CAN source`() {
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(3).put(0).put(1).put(Esp32CanBus.SW_CAN.wireValue.toByte())
            putInt(8).putLong(5678).putInt(0x123).put(0x55)
        }.array()

        val frame = Esp32CanBleProtocol.decodeFrame(payload)!!

        assertEquals(Esp32CanBus.SW_CAN, frame.bus)
        assertEquals(0x123, frame.identifier)
        assertEquals(0x55, frame.data.single().toInt() and 0xff)
    }
}

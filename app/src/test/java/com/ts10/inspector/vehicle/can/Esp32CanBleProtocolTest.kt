package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Esp32CanBleProtocolTest {
    @Test
    fun reconstructsFragmentedStandardFrame() {
        val record = frameRecord(sequence = 0xfffffffeL, identifier = 0x321, data = byteArrayOf(1, 2, 3, 4))
        val assembler = Esp32CanChunkAssembler()

        val first = assembler.accept(chunk(sequence = 10, offset = 0, record = record))
        val second = assembler.accept(chunk(sequence = 11, offset = 14, record = record))

        assertNull(first.record)
        assertFalse(first.malformed)
        val frame = Esp32CanBleProtocol.decodeFrame(second.record!!)
        assertNotNull(frame)
        assertEquals(0xfffffffeL, frame!!.sequence)
        assertEquals(0x321, frame.identifier)
        assertEquals(4, frame.dlc)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.data)
    }

    @Test
    fun detectsMissingChunkAndRejectsContinuation() {
        val record = frameRecord(sequence = 1, identifier = 0x123, data = byteArrayOf(7, 8))
        val assembler = Esp32CanChunkAssembler()
        assembler.accept(chunk(sequence = 20, offset = 0, record = record))

        val result = assembler.accept(chunk(sequence = 22, offset = 14, record = record))

        assertEquals(1, result.lostChunks)
        assertTrue(result.malformed)
        assertNull(result.record)
    }

    @Test
    fun doesNotCountEspCounterRestartAsMassiveChunkLoss() {
        val record = frameRecord(sequence = 1, identifier = 0x123, data = byteArrayOf(7, 8))
        val assembler = Esp32CanChunkAssembler()
        assembler.accept(chunk(sequence = 9_294, offset = 0, record = record))

        val restarted = assembler.accept(chunk(sequence = 0, offset = 0, record = record))

        assertEquals(0, restarted.lostChunks)
        assertFalse(restarted.malformed)
    }

    @Test
    fun streamResetClearsChunkSequenceContinuity() {
        val record = frameRecord(sequence = 1, identifier = 0x123, data = byteArrayOf(7, 8))
        val assembler = Esp32CanChunkAssembler()
        assembler.accept(chunk(sequence = 100, offset = 0, record = record))

        assembler.resetStream()
        val afterReconnect = assembler.accept(chunk(sequence = 4, offset = 0, record = record))

        assertEquals(0, afterReconnect.lostChunks)
        assertFalse(afterReconnect.malformed)
    }

    @Test
    fun decodesStatsAndRejectsInvalidFrameId() {
        val stats = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).put(1).putShort(95)
            .putInt(1234).putInt(2).putInt(99).putInt(4).array()
        assertEquals(1234, Esp32CanBleProtocol.decodeStats(stats)!!.receivedFrames)
        assertEquals(2, Esp32CanBleProtocol.decodeStats(stats)!!.queueDrops)

        val invalid = frameRecord(sequence = 1, identifier = 0x800, data = byteArrayOf())
        assertNull(Esp32CanBleProtocol.decodeFrame(invalid))
    }

    @Test
    fun decodesAdaptiveBleStatsAndExposesFilteredMode() {
        val stats = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).put(0x43).putShort(500)
            .putInt(20_000).putInt(3).putInt(7_500).putInt(1).array()

        val decoded = Esp32CanBleProtocol.decodeStats(stats)!!

        assertTrue(decoded.connected)
        assertEquals("BLE senales conocidas", decoded.streamMode)
        assertEquals(7_500, decoded.filteredFrames)
        assertEquals(0, decoded.noClientDrops)
    }

    @Test
    fun decodesUsbStatsPerBusAndControllerStartFlags() {
        val stats = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .put(3).put(0x12).putShort(0)
            .putInt(90_000).putInt(0).putInt(0).putInt(2)
            .putInt(1).putInt(0).putInt(1)
            .putInt(0).putInt(17).array()

        val decoded = Esp32CanBleProtocol.decodeUsbStats(stats)!!

        assertTrue(decoded.hsStarted)
        assertFalse(decoded.msStarted)
        assertFalse(decoded.swStarted)
        assertEquals(90_000, decoded.hsReceived)
        assertEquals(0, decoded.msReceived)
        assertEquals(0, decoded.swReceived)
        assertEquals(0, decoded.hsErrors)
        assertEquals(1, decoded.msErrors)
        assertEquals(1, decoded.swErrors)
        assertEquals(17, decoded.noClientDrops)
    }

    @Test
    fun rejectsTruncatedUsbStats() {
        assertNull(Esp32CanBleProtocol.decodeUsbStats(byteArrayOf(3, 7)))
    }

    @Test
    fun decodesExtendedTwaiDiagnostics() {
        val stats = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(3).put(0x17).putShort(0)
            putInt(90_000).putInt(4_000).putInt(0).putInt(0)
            putInt(1).putInt(0).putInt(0).putInt(0).putInt(17)
            putInt(12).putInt(2).putInt(1).putInt(0)
            putInt(1).putInt(1).putInt(1).putInt(0)
            putInt(0).putInt(7).putInt(0).putInt(0)
            putInt(12).putInt(45_678).putInt(1)
        }.array()

        val decoded = Esp32CanBleProtocol.decodeUsbStats(stats)!!

        assertEquals(12, decoded.swBusErrorAlerts)
        assertEquals(2, decoded.swRxQueueFullAlerts)
        assertEquals(1L, decoded.swControllerState)
        assertEquals(7, decoded.swRxErrorCounter)
        assertEquals(12, decoded.swBusErrorCount)
        assertEquals(45_678, decoded.swRxEdges)
        assertEquals(true, decoded.swRxLevelHigh)
    }

    @Test
    fun usbParserDiscardsFirmwareVersionPreamble() {
        val payload = frameRecord(sequence = 7, identifier = 0x321, data = byteArrayOf(1, 2, 3))
        val preamble = "\r\n===== VERSIONES =====\r\nESP-IDF: test\r\n".toByteArray()

        val records = Esp32CanUsbStreamParser().accept(preamble + usbRecord(1, payload))

        assertEquals(1, records.size)
        assertEquals(7, Esp32CanBleProtocol.decodeFrame(records.single().payload)!!.sequence)
    }

    private fun frameRecord(sequence: Long, identifier: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1)
            put(0)
            put(data.size.toByte())
            put(0)
            putInt(sequence.toInt())
            putLong(123_456_789L)
            putInt(identifier)
            put(data)
        }.array()

    private fun chunk(sequence: Int, offset: Int, record: ByteArray): ByteArray {
        val length = minOf(14, record.size - offset)
        return ByteBuffer.allocate(6 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xCA.toByte())
            put(Esp32CanBleProtocol.RECORD_CAN_FRAME.toByte())
            putShort(sequence.toShort())
            put(offset.toByte())
            put(record.size.toByte())
            put(record, offset, length)
        }.array()
    }

    private fun usbRecord(type: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(5 + payload.size + 2)
        packet[0] = 0xA5.toByte()
        packet[1] = 0x5A
        packet[2] = type.toByte()
        packet[3] = payload.size.toByte()
        packet[4] = (payload.size ushr 8).toByte()
        payload.copyInto(packet, destinationOffset = 5)
        val crc = crc16Ccitt(packet, offset = 2, length = 3 + payload.size)
        packet[packet.lastIndex - 1] = crc.toByte()
        packet[packet.lastIndex] = (crc ushr 8).toByte()
        return packet
    }
}

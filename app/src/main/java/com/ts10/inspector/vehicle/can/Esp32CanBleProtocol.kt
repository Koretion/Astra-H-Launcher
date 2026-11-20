package com.ts10.inspector.vehicle.can

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Esp32CanFrame(
    val sequence: Long,
    val timestampUs: Long,
    val identifier: Long,
    val flags: Int,
    val dlc: Int,
    val data: ByteArray,
    val bus: Esp32CanBus = Esp32CanBus.UNKNOWN,
) {
    val extended: Boolean get() = flags and 0x01 != 0
    val rtr: Boolean get() = flags and 0x02 != 0
}

internal data class Esp32CanRemoteStats(
    val connected: Boolean,
    val bitrateKbps: Int,
    val receivedFrames: Long,
    val queueDrops: Long,
    val noClientDrops: Long,
    val filteredFrames: Long = 0,
    val twaiErrors: Long,
    val streamMode: String = "BLE",
)

internal data class Esp32CanUsbStats(
    val hsStarted: Boolean,
    val msStarted: Boolean,
    val swStarted: Boolean,
    val hsReceived: Long,
    val msReceived: Long,
    val swReceived: Long,
    val queueDrops: Long,
    val swErrors: Long,
    val hsErrors: Long,
    val msErrors: Long,
    val filteredFrames: Long,
    val noClientDrops: Long,
    val swBusErrorAlerts: Long = 0,
    val swRxQueueFullAlerts: Long = 0,
    val swErrorPassiveAlerts: Long = 0,
    val swBusOffAlerts: Long = 0,
    val swAboveErrorWarningAlerts: Long = 0,
    val swBelowErrorWarningAlerts: Long = 0,
    val swControllerState: Long? = null,
    val swMessagesPending: Long = 0,
    val swTxErrorCounter: Long = 0,
    val swRxErrorCounter: Long = 0,
    val swRxMissedCount: Long = 0,
    val swRxOverrunCount: Long = 0,
    val swBusErrorCount: Long = 0,
    val swRxEdges: Long = 0,
    val swRxLevelHigh: Boolean? = null,
)

internal enum class Esp32CanBus(val wireValue: Int, val label: String) {
    UNKNOWN(0, "CAN"),
    MS_CAN(1, "MS-CAN"),
    HS_CAN(2, "HS-CAN"),
    SW_CAN(3, "SW-CAN");

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

internal data class Esp32UsbRecord(val type: Int, val payload: ByteArray)

internal fun encodeEsp32UsbRecord(type: Int, payload: ByteArray): ByteArray {
    require(type in 0..0xff)
    require(payload.size in 1..256)
    val packet = ByteArray(5 + payload.size + 2)
    packet[0] = 0xA5.toByte()
    packet[1] = 0x5A
    packet[2] = type.toByte()
    packet[3] = payload.size.toByte()
    packet[4] = (payload.size ushr 8).toByte()
    payload.copyInto(packet, 5)
    val crc = crc16Ccitt(packet, 2, 3 + payload.size)
    packet[packet.lastIndex - 1] = crc.toByte()
    packet[packet.lastIndex] = (crc ushr 8).toByte()
    return packet
}

internal fun extractGmlanFirmwareVersion(imageOrInfo: ByteArray): String? {
    val prefix = GMLAN_FIRMWARE_VERSION_PREFIX.toByteArray(Charsets.US_ASCII)
    if (imageOrInfo.size < prefix.size + 2) return null
    for (start in 0..imageOrInfo.size - prefix.size) {
        var matches = true
        for (offset in prefix.indices) {
            if (imageOrInfo[start + offset] != prefix[offset]) { matches = false; break }
        }
        if (!matches) continue
        val valueStart = start + prefix.size
        var end = valueStart
        while (end < imageOrInfo.size && end - valueStart <= MAX_GMLAN_VERSION_LENGTH &&
            imageOrInfo[end] != ';'.code.toByte()) end++
        if (end == valueStart || end >= imageOrInfo.size || imageOrInfo[end] != ';'.code.toByte()) continue
        val version = imageOrInfo.copyOfRange(valueStart, end).toString(Charsets.US_ASCII)
        if (version.all { it.isLetterOrDigit() || it in ".-_" }) return version
    }
    return null
}

internal const val GMLAN_FIRMWARE_VERSION_PREFIX = "TS10_GMLAN_FW_VERSION="
private const val MAX_GMLAN_VERSION_LENGTH = 32

/** Incremental parser for A5 5A/type/length/payload/CRC16 USB records. */
internal class Esp32CanUsbStreamParser {
    private var buffer = ByteArray(0)

    fun reset() { buffer = ByteArray(0) }

    fun accept(bytes: ByteArray): List<Esp32UsbRecord> {
        if (bytes.isNotEmpty()) buffer += bytes
        val records = mutableListOf<Esp32UsbRecord>()
        while (buffer.size >= 7) {
            val magic = buffer.indexOfMagic()
            if (magic < 0) {
                buffer = buffer.takeLast(1).toByteArray()
                break
            }
            if (magic > 0) buffer = buffer.copyOfRange(magic, buffer.size)
            if (buffer.size < 7) break
            val length = (buffer[3].toInt() and 0xff) or ((buffer[4].toInt() and 0xff) shl 8)
            if (length !in 1..MAX_USB_PAYLOAD) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            val packetLength = 5 + length + 2
            if (buffer.size < packetLength) break
            val expected = (buffer[packetLength - 2].toInt() and 0xff) or
                ((buffer[packetLength - 1].toInt() and 0xff) shl 8)
            val actual = crc16Ccitt(buffer, 2, 3 + length)
            if (actual == expected) {
                records += Esp32UsbRecord(
                    type = buffer[2].toInt() and 0xff,
                    payload = buffer.copyOfRange(5, 5 + length),
                )
                buffer = buffer.copyOfRange(packetLength, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }
        return records
    }

    private fun ByteArray.indexOfMagic(): Int {
        for (index in 0 until size - 1) {
            if (this[index] == 0xA5.toByte() && this[index + 1] == 0x5A.toByte()) return index
        }
        return -1
    }

    companion object { private const val MAX_USB_PAYLOAD = 256 }
}

internal fun crc16Ccitt(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
    var crc = 0xffff
    for (index in offset until offset + length) {
        crc = crc xor ((bytes[index].toInt() and 0xff) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xffff
    }
    return crc
}

internal data class Esp32CanChunkResult(
    val recordType: Int? = null,
    val record: ByteArray? = null,
    val lostChunks: Int = 0,
    val malformed: Boolean = false,
)

internal class Esp32CanChunkAssembler {
    private var expectedChunkSequence: Int? = null
    private var recordType = 0
    private var record: ByteArray? = null
    private var filled = 0

    fun reset() {
        record = null
        filled = 0
    }

    fun resetStream() {
        expectedChunkSequence = null
        reset()
    }

    fun accept(packet: ByteArray): Esp32CanChunkResult {
        if (packet.size !in CHUNK_HEADER_SIZE..ATT_SAFE_PAYLOAD ||
            packet[0].toInt() and 0xff != CHUNK_MAGIC
        ) {
            reset()
            return Esp32CanChunkResult(malformed = true)
        }

        val chunkSequence = (packet[2].toInt() and 0xff) or
            ((packet[3].toInt() and 0xff) shl 8)
        val sequenceDelta = expectedChunkSequence?.let { expected ->
            (chunkSequence - expected) and 0xffff
        } ?: 0
        // A delta in the backward half of the 16-bit range means the ESP
        // restarted its counter (or an old notification arrived), not that
        // tens of thousands of BLE chunks disappeared.
        val lostChunks = sequenceDelta.takeIf { it in 1..MAX_FORWARD_SEQUENCE_GAP } ?: 0
        expectedChunkSequence = (chunkSequence + 1) and 0xffff
        if (sequenceDelta != 0) reset()

        val incomingType = packet[1].toInt() and 0xff
        val offset = packet[4].toInt() and 0xff
        val totalLength = packet[5].toInt() and 0xff
        val payloadLength = packet.size - CHUNK_HEADER_SIZE
        if (totalLength !in 1..MAX_RECORD_SIZE ||
            offset + payloadLength > totalLength ||
            (offset == 0 && payloadLength == 0)
        ) {
            reset()
            return Esp32CanChunkResult(lostChunks = lostChunks, malformed = true)
        }

        if (offset == 0) {
            recordType = incomingType
            record = ByteArray(totalLength)
            filled = 0
        }
        val target = record
        if (target == null || incomingType != recordType || offset != filled || totalLength != target.size) {
            reset()
            return Esp32CanChunkResult(lostChunks = lostChunks, malformed = true)
        }

        packet.copyInto(target, destinationOffset = offset, startIndex = CHUNK_HEADER_SIZE)
        filled += payloadLength
        if (filled != target.size) return Esp32CanChunkResult(lostChunks = lostChunks)

        val completedType = recordType
        val completed = target.copyOf()
        reset()
        return Esp32CanChunkResult(
            recordType = completedType,
            record = completed,
            lostChunks = lostChunks,
        )
    }

    companion object {
        private const val CHUNK_MAGIC = 0xCA
        private const val CHUNK_HEADER_SIZE = 6
        private const val ATT_SAFE_PAYLOAD = 20
        private const val MAX_RECORD_SIZE = 64
        private const val MAX_FORWARD_SEQUENCE_GAP = 0x7fff
    }
}

internal object Esp32CanBleProtocol {
    const val RECORD_CAN_FRAME = 1

    fun decodeFrame(record: ByteArray): Esp32CanFrame? {
        if (record.size != 28 || record[0].toInt() and 0xff !in 1..3) return null
        val buffer = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val flags = record[1].toInt() and 0xff
        val dlc = record[2].toInt() and 0xff
        if (dlc !in 0..8) return null
        val identifier = buffer.getInt(16).toLong() and UINT_MASK
        val extended = flags and 0x01 != 0
        if ((!extended && identifier > 0x7ffL) || (extended && identifier > 0x1fffffffL)) return null
        return Esp32CanFrame(
            sequence = buffer.getInt(4).toLong() and UINT_MASK,
            timestampUs = buffer.getLong(8),
            identifier = identifier,
            flags = flags,
            dlc = dlc,
            data = record.copyOfRange(20, 20 + dlc),
            bus = if (record[0].toInt() and 0xff >= 2) {
                Esp32CanBus.fromWire(record[3].toInt() and 0xff)
            } else Esp32CanBus.UNKNOWN,
        )
    }

    fun decodeStats(value: ByteArray): Esp32CanRemoteStats? {
        if (value.size != 20 || value[0].toInt() and 0xff !in 1..3) return null
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val version = value[0].toInt() and 0xff
        val flags = value[1].toInt() and 0xff
        val adaptiveStats = version >= 2
        return Esp32CanRemoteStats(
            connected = if (adaptiveStats) flags and (BLE_RAW_FLAG or BLE_FILTERED_FLAG) != 0 else flags != 0,
            bitrateKbps = buffer.getShort(2).toInt() and 0xffff,
            receivedFrames = buffer.getInt(4).toLong() and UINT_MASK,
            queueDrops = buffer.getInt(8).toLong() and UINT_MASK,
            noClientDrops = if (adaptiveStats) 0 else buffer.getInt(12).toLong() and UINT_MASK,
            filteredFrames = if (adaptiveStats) buffer.getInt(12).toLong() and UINT_MASK else 0,
            twaiErrors = buffer.getInt(16).toLong() and UINT_MASK,
            streamMode = when {
                adaptiveStats && flags and BLE_FILTERED_FLAG != 0 -> "BLE senales conocidas"
                adaptiveStats && flags and BLE_RAW_FLAG != 0 -> "BLE RAW"
                else -> "BLE"
            },
        )
    }

    fun decodeUsbStats(value: ByteArray): Esp32CanUsbStats? {
        val version = value.firstOrNull()?.toInt()?.and(0xff) ?: return null
        if ((version == 2 && value.size < 28) ||
            (version == 3 && value.size < 40) ||
            version !in 2..3
        ) {
            return null
        }
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val flags = value[1].toInt() and 0xff
        val extendedTwai = version >= 3 && value.size >= 100
        return if (version >= 3) {
            Esp32CanUsbStats(
                hsStarted = flags and USB_HS_STARTED_FLAG != 0,
                msStarted = flags and USB_MS_STARTED_FLAG != 0,
                swStarted = flags and USB_SW_STARTED_FLAG != 0,
                hsReceived = buffer.uint32(4),
                msReceived = buffer.uint32(8),
                swReceived = buffer.uint32(12),
                queueDrops = buffer.uint32(16),
                swErrors = buffer.uint32(20),
                hsErrors = buffer.uint32(24),
                msErrors = buffer.uint32(28),
                filteredFrames = buffer.uint32(32),
                noClientDrops = buffer.uint32(36),
                swBusErrorAlerts = if (extendedTwai) buffer.uint32(40) else 0,
                swRxQueueFullAlerts = if (extendedTwai) buffer.uint32(44) else 0,
                swErrorPassiveAlerts = if (extendedTwai) buffer.uint32(48) else 0,
                swBusOffAlerts = if (extendedTwai) buffer.uint32(52) else 0,
                swAboveErrorWarningAlerts = if (extendedTwai) buffer.uint32(56) else 0,
                swBelowErrorWarningAlerts = if (extendedTwai) buffer.uint32(60) else 0,
                swControllerState = if (extendedTwai) buffer.uint32(64).takeUnless { it == UINT_MASK } else null,
                swMessagesPending = if (extendedTwai) buffer.uint32(68) else 0,
                swTxErrorCounter = if (extendedTwai) buffer.uint32(72) else 0,
                swRxErrorCounter = if (extendedTwai) buffer.uint32(76) else 0,
                swRxMissedCount = if (extendedTwai) buffer.uint32(80) else 0,
                swRxOverrunCount = if (extendedTwai) buffer.uint32(84) else 0,
                swBusErrorCount = if (extendedTwai) buffer.uint32(88) else 0,
                swRxEdges = if (extendedTwai) buffer.uint32(92) else 0,
                swRxLevelHigh = if (extendedTwai) buffer.uint32(96) != 0L else null,
            )
        } else {
            Esp32CanUsbStats(
                hsStarted = flags and USB_HS_STARTED_FLAG != 0,
                msStarted = flags and USB_MS_STARTED_FLAG != 0,
                swStarted = false,
                hsReceived = buffer.uint32(4),
                msReceived = buffer.uint32(8),
                swReceived = 0,
                queueDrops = buffer.uint32(12),
                swErrors = 0,
                hsErrors = buffer.uint32(16),
                msErrors = buffer.uint32(20),
                filteredFrames = if (value.size >= 28) buffer.uint32(24) else 0,
                noClientDrops = if (value.size >= 32) buffer.uint32(28) else 0,
            )
        }
    }

    private fun ByteBuffer.uint32(offset: Int) = getInt(offset).toLong() and UINT_MASK

    private const val UINT_MASK = 0xffffffffL
    private const val BLE_RAW_FLAG = 0x20
    private const val BLE_FILTERED_FLAG = 0x40
    private const val USB_SW_STARTED_FLAG = 0x01
    private const val USB_HS_STARTED_FLAG = 0x02
    private const val USB_MS_STARTED_FLAG = 0x04
}

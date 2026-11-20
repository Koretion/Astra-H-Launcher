package com.ts10.inspector.vehicle.can.firmware

import com.ts10.inspector.ui.fail
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Minimal ESP32-C3 ROM loader used only for the one-time installation of the OTA firmware. */
internal class Esp32RomBootloader(private val io: Io) {
    interface Io {
        /** Applies the native USB-Serial/JTAG reset sequence and reopens the ROM port. */
        fun enterBootloader()
        /** Releases the download strap and resets the chip back into its installed application. */
        fun resetToApplication()
        fun write(packet: ByteArray)
        fun read(timeoutMs: Int): ByteArray?
        fun drainInput()
    }

    fun installApplication(image: ByteArray, onProgress: (Int, UiText) -> Unit) {
        if (image.isEmpty()) fail(R.string.rom_empty_image)
        onProgress(1, UiText.of(R.string.rom_entering))
        io.enterBootloader()
        synchronize()

        val security = checkedCommand(OP_SECURITY_INFO, expectedDataSize = SECURITY_INFO_SIZE, timeoutMs = 3_000).data
        val flags = security.leInt(0)
        val flashCryptCount = security[4].toInt() and 0xff
        val chipId = security.leInt(12)
        if (chipId != ESP32_C3_CHIP_ID) fail(R.string.rom_wrong_device, chipId)
        if (!(flags and SECURITY_RESTRICTED_FLAGS == 0 && Integer.bitCount(flashCryptCount) % 2 == 0)) {
            fail(R.string.rom_secure_board)
        }

        checkedCommand(OP_SPI_ATTACH, ByteArray(8), timeoutMs = 3_000)
        // READ_FLASH is only provided by Espressif's RAM stub, which this minimal ROM-only
        // installer does not upload. The accepted GMLAN Arduino artifact uses this fixed app0
        // layout; attempting to inspect 0x8000 with a ROM command stranded the board in ROM.
        val applications = listOf(
            Partition(APPLICATION_OTA0_OFFSET, APPLICATION_SIZE, "app0"),
            Partition(APPLICATION_OTA1_OFFSET, APPLICATION_SIZE, "app1"),
        )
        if (!applications.all { image.size <= it.size }) fail(R.string.rom_image_too_big, image.size, APPLICATION_SIZE)
        onProgress(3, UiText.of(R.string.rom_partitions_ok))
        applications.forEachIndexed { index, application ->
            flashAndVerify(
                image = image,
                application = application,
                progressStart = if (index == 0) 4 else 49,
                progressEnd = if (index == 0) 47 else 92,
                onProgress = onProgress,
            )
        }

        onProgress(98, UiText.of(R.string.rom_verified_rebooting))
        // A verified image is already safe. Some native USB ports disappear before FLASH_END replies.
        runCatching { checkedCommand(OP_FLASH_END, leInts(0), timeoutMs = 2_000) }
        // Do not rely on FLASH_END alone: native USB can leave the chip in download mode even
        // after acknowledging, or disconnect before the acknowledgement reaches Android.
        io.resetToApplication()
        onProgress(100, UiText.of(R.string.rom_done))
    }

    private fun flashAndVerify(
        image: ByteArray,
        application: Partition,
        progressStart: Int,
        progressEnd: Int,
        onProgress: (Int, UiText) -> Unit,
    ) {
        val blocks = (image.size + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE
        checkedCommand(
            OP_FLASH_BEGIN,
            leInts(image.size, blocks, FLASH_BLOCK_SIZE, application.offset, 0),
            timeoutMs = ERASE_TIMEOUT_MS,
        )

        for (sequence in 0 until blocks) {
            val from = sequence * FLASH_BLOCK_SIZE
            val count = minOf(FLASH_BLOCK_SIZE, image.size - from)
            val block = ByteArray(FLASH_BLOCK_SIZE) { 0xff.toByte() }
            image.copyInto(block, endIndex = from + count, destinationOffset = 0, startIndex = from)
            val payload = leInts(block.size, sequence, 0, 0) + block
            var lastError: Throwable? = null
            for (attempt in 0 until FLASH_WRITE_ATTEMPTS) {
                val result = runCatching { checkedCommand(OP_FLASH_DATA, payload, xorChecksum(block), FLASH_BLOCK_TIMEOUT_MS) }
                if (result.isSuccess) {
                    lastError = null
                    break
                }
                lastError = result.exceptionOrNull()
                if (attempt + 1 < FLASH_WRITE_ATTEMPTS) io.drainInput()
            }
            lastError?.let { throw it }
            val percent = progressStart + ((sequence + 1) * (progressEnd - progressStart) / blocks)
            onProgress(percent, UiText.of(R.string.rom_writing, application.label))
        }

        onProgress(progressEnd, UiText.of(R.string.rom_verifying, application.label))
        val remoteMd5 = checkedCommand(
            OP_FLASH_MD5,
            leInts(application.offset, image.size, 0, 0),
            expectedDataSize = MD5_HEX_SIZE,
            timeoutMs = VERIFY_TIMEOUT_MS,
        ).data.toString(Charsets.US_ASCII).lowercase()
        val localMd5 = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
        if (remoteMd5 != localMd5) fail(R.string.rom_md5_mismatch, application.label)
    }

    private fun synchronize() {
        var lastError: Throwable? = null
        repeat(SYNC_ATTEMPTS) {
            io.drainInput()
            runCatching {
                checkedCommand(OP_SYNC, byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }, timeoutMs = 500)
            }.onSuccess {
                io.drainInput() // ROM emits seven additional SYNC replies.
                return
            }.onFailure { lastError = it }
        }
        throw IllegalStateException("Could not sync with the ROM bootloader", lastError)
    }

    private fun checkedCommand(
        opcode: Int,
        payload: ByteArray = byteArrayOf(),
        checksum: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        expectedDataSize: Int = 0,
    ): Response {
        io.write(encodeRequest(opcode, payload, checksum))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val decoder = SlipDecoder()
        while (System.nanoTime() < deadline) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1).toInt()
            val bytes = io.read(remainingMs) ?: continue
            for (frame in decoder.accept(bytes)) {
                if (frame.size < RESPONSE_HEADER_SIZE) continue
                val header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
                val direction = header.get().toInt() and 0xff
                val returnedOpcode = header.get().toInt() and 0xff
                val dataSize = header.short.toInt() and 0xffff
                val value = header.int
                if (direction != RESPONSE_DIRECTION || returnedOpcode != opcode || dataSize > header.remaining()) continue
                val dataAndStatus = ByteArray(dataSize).also(header::get)
                check(dataAndStatus.size >= expectedDataSize + 2) { "Incomplete ROM reply for 0x${opcode.toString(16)}" }
                val status = dataAndStatus[expectedDataSize].toInt() and 0xff
                val reason = dataAndStatus[expectedDataSize + 1].toInt() and 0xff
                check(status == 0) { "The bootloader rejected 0x${opcode.toString(16)} (status $status, reason $reason)" }
                return Response(value, dataAndStatus.copyOf(expectedDataSize))
            }
        }
        error("Timed out waiting for ROM reply to 0x${opcode.toString(16)}")
    }

    private data class Response(val value: Int, val data: ByteArray)
    private data class Partition(val offset: Int, val size: Int, val label: String)

    internal class SlipDecoder {
        private val frame = ByteArrayOutputStream()
        private var started = false
        private var escaped = false

        fun accept(bytes: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            for (byte in bytes) {
                when {
                    byte == SLIP_END -> {
                        if (started && frame.size() > 0) result += frame.toByteArray()
                        frame.reset()
                        started = true
                        escaped = false
                    }
                    !started -> Unit
                    escaped -> {
                        frame.write(when (byte) { SLIP_ESC_END -> 0xc0; SLIP_ESC_ESC -> 0xdb; else -> byte.toInt() and 0xff })
                        escaped = false
                    }
                    byte == SLIP_ESC -> escaped = true
                    else -> frame.write(byte.toInt() and 0xff)
                }
            }
            return result
        }
    }

    companion object {
        internal fun encodeRequest(opcode: Int, payload: ByteArray, checksum: Int = 0): ByteArray {
            val raw = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(REQUEST_DIRECTION.toByte()).put(opcode.toByte()).putShort(payload.size.toShort())
                .putInt(checksum).put(payload).array()
            val escaped = ByteArrayOutputStream(raw.size + 2)
            escaped.write(SLIP_END.toInt() and 0xff)
            raw.forEach { byte ->
                when (byte) {
                    SLIP_END -> { escaped.write(SLIP_ESC.toInt() and 0xff); escaped.write(SLIP_ESC_END.toInt() and 0xff) }
                    SLIP_ESC -> { escaped.write(SLIP_ESC.toInt() and 0xff); escaped.write(SLIP_ESC_ESC.toInt() and 0xff) }
                    else -> escaped.write(byte.toInt() and 0xff)
                }
            }
            escaped.write(SLIP_END.toInt() and 0xff)
            return escaped.toByteArray()
        }

        private fun xorChecksum(bytes: ByteArray): Int = bytes.fold(0xef) { result, byte -> result xor (byte.toInt() and 0xff) }
        private fun leInts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN).also { buffer -> values.forEach(buffer::putInt) }.array()
        private fun ByteArray.leInt(offset: Int): Int = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private const val REQUEST_DIRECTION = 0
        private const val RESPONSE_DIRECTION = 1
        private const val OP_FLASH_BEGIN = 0x02
        private const val OP_FLASH_DATA = 0x03
        private const val OP_FLASH_END = 0x04
        private const val OP_SYNC = 0x08
        private const val OP_SPI_ATTACH = 0x0d
        private const val OP_FLASH_MD5 = 0x13
        private const val OP_SECURITY_INFO = 0x14
        private const val ESP32_C3_CHIP_ID = 5
        private const val FLASH_BLOCK_SIZE = 0x400
        private const val APPLICATION_OTA0_OFFSET = 0x10000
        private const val APPLICATION_OTA1_OFFSET = 0x150000
        private const val APPLICATION_SIZE = 0x140000
        private const val FLASH_WRITE_ATTEMPTS = 3
        private const val SECURITY_INFO_SIZE = 20
        private const val SECURITY_RESTRICTED_FLAGS = (1 shl 0) or (1 shl 2)
        private const val MD5_HEX_SIZE = 32
        private const val RESPONSE_HEADER_SIZE = 8
        private const val DEFAULT_TIMEOUT_MS = 3_000
        private const val FLASH_BLOCK_TIMEOUT_MS = 5_000
        private const val ERASE_TIMEOUT_MS = 60_000
        private const val VERIFY_TIMEOUT_MS = 20_000
        private const val SYNC_ATTEMPTS = 5
        private val SLIP_END = 0xc0.toByte()
        private val SLIP_ESC = 0xdb.toByte()
        private val SLIP_ESC_END = 0xdc.toByte()
        private val SLIP_ESC_ESC = 0xdd.toByte()
    }
}

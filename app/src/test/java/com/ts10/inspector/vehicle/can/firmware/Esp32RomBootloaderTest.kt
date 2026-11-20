package com.ts10.inspector.vehicle.can.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.ArrayDeque

class Esp32RomBootloaderTest {
    @Test
    fun `SLIP request escapes delimiters and escape bytes`() {
        val encoded = Esp32RomBootloader.encodeRequest(0x03, byteArrayOf(0xc0.toByte(), 0xdb.toByte()))
        val decoded = Esp32RomBootloader.SlipDecoder().accept(encoded).single()

        assertEquals(0, decoded[0].toInt())
        assertEquals(0x03, decoded[1].toInt())
        assertArrayEquals(byteArrayOf(0xc0.toByte(), 0xdb.toByte()), decoded.copyOfRange(8, 10))
    }

    @Test
    fun `ROM installation writes and verifies both OTA slots`() {
        val image = ByteArray(1_501) { ((it * 37) and 0xff).toByte() }
        val io = FakeRomIo(image)
        val progress = mutableListOf<Int>()

        Esp32RomBootloader(io).installApplication(image) { percent, _ -> progress += percent }

        assertTrue(io.entered)
        assertTrue(io.reset)
        assertEquals(listOf(0x10000, 0x150000), io.flashOffsets)
        assertEquals(listOf(image.size, image.size), io.flashSizes)
        assertArrayEquals(image + image, io.written.toByteArray().let {
            it.copyOfRange(0, image.size) + it.copyOfRange(2 * 0x400, 2 * 0x400 + image.size)
        })
        assertEquals(100, progress.last())
        assertTrue(io.operations.containsAll(listOf(0x08, 0x14, 0x0d, 0x02, 0x03, 0x13, 0x04)))
        assertTrue(0x0e !in io.operations)
    }

    private class FakeRomIo(private val expectedImage: ByteArray) : Esp32RomBootloader.Io {
        val operations = mutableListOf<Int>()
        val written = ByteArrayOutputStream()
        var entered = false
        var reset = false
        val flashOffsets = mutableListOf<Int>()
        val flashSizes = mutableListOf<Int>()
        private val responses = ArrayDeque<ByteArray>()

        override fun enterBootloader() { entered = true }
        override fun resetToApplication() { reset = true }

        override fun write(packet: ByteArray) {
            val raw = Esp32RomBootloader.SlipDecoder().accept(packet).single()
            val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            header.get()
            val opcode = header.get().toInt() and 0xff
            val size = header.short.toInt() and 0xffff
            header.int
            val payload = ByteArray(size).also(header::get)
            operations += opcode
            val responseData = when (opcode) {
                0x14 -> ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(0)
                    position(12)
                    putInt(5)
                }.array()
                0x02 -> {
                    val begin = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    flashSizes += begin.int
                    begin.int
                    begin.int
                    flashOffsets += begin.int
                    byteArrayOf()
                }
                0x03 -> {
                    val block = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    val blockSize = block.int
                    block.int
                    block.position(16)
                    ByteArray(blockSize).also { block.get(it); written.write(it) }
                    byteArrayOf()
                }
                0x13 -> MessageDigest.getInstance("MD5").digest(expectedImage)
                    .joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
                else -> byteArrayOf()
            }
            responses += response(opcode, responseData)
        }

        override fun read(timeoutMs: Int): ByteArray? = if (responses.isEmpty()) null else responses.removeFirst()
        override fun drainInput() { responses.clear() }

        private fun response(opcode: Int, data: ByteArray): ByteArray {
            val raw = ByteBuffer.allocate(8 + data.size + 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).put(opcode.toByte()).putShort((data.size + 2).toShort()).putInt(1)
                .put(data).put(0).put(0).array()
            val framed = ByteArrayOutputStream()
            framed.write(0xc0)
            raw.forEach { value ->
                when (value.toInt() and 0xff) {
                    0xc0 -> { framed.write(0xdb); framed.write(0xdc) }
                    0xdb -> { framed.write(0xdb); framed.write(0xdd) }
                    else -> framed.write(value.toInt() and 0xff)
                }
            }
            framed.write(0xc0)
            return framed.toByteArray()
        }
    }
}

package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Esp32UsbRecordEncodingTest {
    @Test fun `host record round trips through stream parser`() {
        val payload = byteArrayOf(1, 2, 3, 0xA5.toByte(), 0x5A)
        val encoded = encodeEsp32UsbRecord(0x22, payload)
        val decoded = Esp32CanUsbStreamParser().accept(encoded).single()
        assertEquals(0x22, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test fun `extracts version marker embedded in firmware image`() {
        val image = byteArrayOf(0xE9.toByte(), 1, 2) +
            "binary-${GMLAN_FIRMWARE_VERSION_PREFIX}1.0.0-beta_2;tail".toByteArray()
        assertEquals("1.0.0-beta_2", extractGmlanFirmwareVersion(image))
    }

    @Test fun `rejects missing or malformed firmware version marker`() {
        assertEquals(null, extractGmlanFirmwareVersion("no marker".toByteArray()))
        assertEquals(null, extractGmlanFirmwareVersion("${GMLAN_FIRMWARE_VERSION_PREFIX}bad version;".toByteArray()))
    }
}

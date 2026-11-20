package com.ts10.inspector.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAddressNormalizerTest {
    @Test
    fun `normalizes common QF Bluetooth address formats`() {
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeBluetoothAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeBluetoothAddress("AABBCCDDEEFF"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeBluetoothAddress("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun `rejects missing or malformed addresses`() {
        assertNull(normalizeBluetoothAddress(null))
        assertNull(normalizeBluetoothAddress(""))
        assertNull(normalizeBluetoothAddress("not-a-mac"))
        assertNull(normalizeBluetoothAddress("AA:BB:CC:DD:EE"))
    }
}

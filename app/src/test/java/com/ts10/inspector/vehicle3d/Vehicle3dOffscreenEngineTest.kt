package com.ts10.inspector.vehicle3d

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Vehicle3dOffscreenEngineTest {
    @Test
    fun `readback flips EGL rows and converts RGBA bytes to Android ARGB`() {
        // On little-endian Android the integer stores the glReadPixels RGBA bytes as ABGR.
        val bottomRed = 0xFF0000FF.toInt()
        val topBlue = 0xFFFF0000.toInt()
        val converted = IntArray(2)

        rgbaBottomUpToArgb(intArrayOf(bottomRed, topBlue), converted, width = 1, height = 2)

        assertArrayEquals(intArrayOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt()), converted)
    }
}

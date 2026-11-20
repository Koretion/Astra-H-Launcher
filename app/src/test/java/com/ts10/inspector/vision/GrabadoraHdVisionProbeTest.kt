package com.ts10.inspector.vision

import com.ts10.inspector.camera.grabadoraCameraDeviceName
import com.ts10.inspector.diagnostics.appendDiagnosticEvent
import com.ts10.inspector.diagnostics.incrementDiagnosticCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GrabadoraHdVisionProbeTest {
    @Test
    fun `surface id matches Grabadora HD client and surface composite key`() {
        assertEquals("123_456".hashCode(), grabadoraSurfaceId(clientId = 123, surfaceHash = 456))
    }

    @Test
    fun `surface id changes for another client or surface`() {
        val original = grabadoraSurfaceId(clientId = 123, surfaceHash = 456)
        assertNotEquals(original, grabadoraSurfaceId(clientId = 124, surfaceHash = 456))
        assertNotEquals(original, grabadoraSurfaceId(clientId = 123, surfaceHash = 457))
    }

    @Test
    fun `camera service selects its active camera when UsbManager cannot expose it`() {
        assertEquals("", grabadoraCameraDeviceName(null))
        assertEquals("", grabadoraCameraDeviceName(""))
    }

    @Test
    fun `visible USB camera name is preserved`() {
        assertEquals("/dev/bus/usb/001/004", grabadoraCameraDeviceName("/dev/bus/usb/001/004"))
    }

    @Test
    fun `local vision accepts only packed RGB compatible frames`() {
        assertEquals(true, isLocalVisionFrameCompatible(planeCount = 1, pixelStride = 4))
        assertEquals(true, isLocalVisionFrameCompatible(planeCount = 1, pixelStride = 3))
        assertEquals(false, isLocalVisionFrameCompatible(planeCount = 0, pixelStride = null))
        assertEquals(false, isLocalVisionFrameCompatible(planeCount = 1, pixelStride = 1))
        assertEquals(false, isLocalVisionFrameCompatible(planeCount = 3, pixelStride = 1))
    }

    @Test
    fun `diagnostic history remains bounded and keeps newest events`() {
        val history = (1..5).fold(emptyList<String>()) { current, value ->
            appendDiagnosticEvent(current, value.toString(), limit = 3)
        }
        assertEquals(listOf("3", "4", "5"), history)
    }

    @Test
    fun `diagnostic shape counters accumulate independently`() {
        val counts = incrementDiagnosticCount(
            incrementDiagnosticCount(emptyMap(), "17:84"),
            "17:84",
        )
        assertEquals(2L, counts["17:84"])
        assertEquals(null, counts["18:84"])
    }
}

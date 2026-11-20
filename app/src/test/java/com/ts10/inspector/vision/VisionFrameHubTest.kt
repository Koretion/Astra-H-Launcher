package com.ts10.inspector.vision

import com.ts10.inspector.camera.usbTransferType
import com.ts10.inspector.settings.DashboardSettings
import com.ts10.inspector.settings.LocalVisionCaptureMode
import com.ts10.inspector.settings.LocalVisionMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class VisionFrameHubTest {
    @Test
    fun `new installations default to validated private OpenGL capture`() {
        assertEquals(LocalVisionCaptureMode.PRIVATE_GL, DashboardSettings().localVisionCaptureMode)
    }

    @Test
    fun localVisionModesCycleThroughSafeDiagnosticOrder() {
        assertEquals(LocalVisionMode.CAPTURE_ONLY, LocalVisionMode.OFF.next())
        assertEquals(LocalVisionMode.ACTIVE, LocalVisionMode.CAPTURE_ONLY.next())
        assertEquals(LocalVisionMode.OFF, LocalVisionMode.ACTIVE.next())
    }

    @Test
    fun captureModesCycleThroughComparableSources() {
        assertEquals(LocalVisionCaptureMode.RGBA, LocalVisionCaptureMode.PRIVATE_GL.next())
        assertEquals(LocalVisionCaptureMode.PRIVATE_GL, LocalVisionCaptureMode.RGBA.next())
    }

    @Test
    fun sharedRgbFrameKeepsPackedChannels() {
        val frame = VisionRgbFrame(2, 1, intArrayOf(0x123456, 0xabcdef))

        assertEquals(0x123456, frame.rgb(0, 0))
        assertEquals(0xabcdef, frame.rgb(1, 0))
    }

    @Test
    fun usbTransferTypesAreReadableInExports() {
        assertEquals("CONTROL", usbTransferType(0))
        assertEquals("ISOCHRONOUS", usbTransferType(1))
        assertEquals("BULK", usbTransferType(2))
        assertEquals("INTERRUPT", usbTransferType(3))
        assertEquals("UNKNOWN(9)", usbTransferType(9))
    }

    @Test
    fun rgbxFramesCanBeDownscaledForTheSharedHub() {
        val input = ByteBuffer.wrap(
            byteArrayOf(
                0x10, 0x20, 0x30, 0,
                0x40, 0x50, 0x60, 0,
                0x70, 0x7f, 0x01, 0,
                0x02, 0x03, 0x04, 0,
            ),
        )

        val frame = copyRgbxToVisionFrame(input, 2, 2, 2, 2)

        assertEquals(0x102030, frame.rgb(0, 0))
        assertEquals(0x405060, frame.rgb(1, 0))
        assertEquals(0x707f01, frame.rgb(0, 1))
        assertEquals(0x020304, frame.rgb(1, 1))
    }

    @Test
    fun openGlReadbackCanBeFlippedToTopDownCoordinates() {
        val bottomUp = ByteBuffer.wrap(
            byteArrayOf(
                0x70, 0x7f, 0x01, 0,
                0x02, 0x03, 0x04, 0,
                0x10, 0x20, 0x30, 0,
                0x40, 0x50, 0x60, 0,
            ),
        )

        val frame = copyRgbxToVisionFrame(
            bottomUp,
            sourceWidth = 2,
            sourceHeight = 2,
            width = 2,
            height = 2,
            flipVertical = true,
        )

        assertEquals(0x102030, frame.rgb(0, 0))
        assertEquals(0x405060, frame.rgb(1, 0))
        assertEquals(0x707f01, frame.rgb(0, 1))
        assertEquals(0x020304, frame.rgb(1, 1))
    }
}

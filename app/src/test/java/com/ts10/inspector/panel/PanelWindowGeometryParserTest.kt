package com.ts10.inspector.panel

import com.ts10.inspector.ui.DesignGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelWindowGeometryParserTest {
    @Test
    fun `replaces only an existing fullscreen Waze task`() {
        assertTrue(shouldReplaceFullscreenWazeTask("com.waze", 1))
        assertFalse(shouldReplaceFullscreenWazeTask("com.waze", 5))
        assertFalse(shouldReplaceFullscreenWazeTask("com.waze", null))
        assertFalse(shouldReplaceFullscreenWazeTask("com.google.android.youtube", 1))
    }

    @Test
    fun `reserves the QF system row from the requested panel height`() {
        assertEquals(
            listOf(473, 0, 1280, 648),
            panelLaunchCoordinates(0, 0, 1280, 720, 720f / DesignGrid.Height),
        )
    }

    @Test
    fun `preserves old Waze height and moves its unused space to the top`() {
        assertEquals(
            listOf(473, 43, 1280, 648),
            wazeLaunchCoordinates(listOf(473, 0, 1280, 648)),
        )
    }

    @Test
    fun `accepts Waze settled across the full visible panel height`() {
        assertTrue(
            panelWazeTaskBoundsMatch(
                expected = listOf(473, 43, 1280, 648),
                observed = listOf(473, 115, 1280, 720),
            ),
        )
    }

    @Test
    fun `accepts Waze firmware translation when panel size is preserved`() {
        assertTrue(
            panelWazeTaskBoundsMatch(
                expected = listOf(473, 43, 1280, 648),
                observed = listOf(473, 72, 1280, 677),
            ),
        )
    }

    @Test
    fun `rejects Waze settlement that leaves a bottom gap`() {
        assertFalse(
            panelWazeTaskBoundsMatch(
                expected = listOf(473, 43, 1280, 648),
                observed = listOf(473, 115, 1280, 637),
            ),
        )
    }

    @Test
    fun `accepts clipped Waze geometry measured three pixels above physical bottom`() {
        assertTrue(
            panelWazeTaskBoundsMatch(
                expected = listOf(473, 43, 1280, 648),
                observed = listOf(473, 115, 1280, 717),
            ),
        )
    }

    @Test
    fun `rejects unclipped QF Waze task bounds beyond the physical display`() {
        assertFalse(
            panelWazeTaskBoundsMatch(
                expected = listOf(473, 43, 1280, 648),
                observed = listOf(473, 115, 1280, 763),
            ),
        )
    }

    @Test
    fun `reduces Waze command when QF preserves height below its system row`() {
        assertEquals(
            listOf(473, 43, 1280, 648),
            wazeAdaptiveCommandCoordinates(
                expected = listOf(473, 43, 1280, 648),
                current = listOf(473, 0, 1280, 648),
                observed = listOf(473, 72, 1280, 763),
            ),
        )
    }

    @Test
    fun `keeps reduced Waze command after it reaches the physical bottom`() {
        assertEquals(
            listOf(473, 43, 1280, 648),
            wazeAdaptiveCommandCoordinates(
                expected = listOf(473, 43, 1280, 648),
                current = listOf(473, 43, 1280, 648),
                observed = listOf(473, 115, 1280, 720),
            ),
        )
    }

    @Test
    fun `returns desired Waze command when current settlement leaves a gap`() {
        assertEquals(
            listOf(473, 43, 1280, 648),
            wazeAdaptiveCommandCoordinates(
                expected = listOf(473, 43, 1280, 648),
                current = listOf(473, 0, 1280, 648),
                observed = listOf(473, 115, 1280, 637),
            ),
        )
    }

    @Test
    fun `accepts QF vertical settlement when panel size is preserved`() {
        assertTrue(
            panelTaskBoundsMatch(
                expected = listOf(473, 0, 1280, 648),
                observed = listOf(473, 72, 1280, 720),
            ),
        )
    }

    @Test
    fun `rejects the clipped full height task seen in TS10 logs`() {
        assertFalse(
            panelTaskBoundsMatch(
                expected = listOf(473, 0, 1280, 648),
                observed = listOf(473, 72, 1280, 792),
            ),
        )
    }

    @Test
    fun `parses Android 10 QF freeform configuration`() {
        val configuration =
            "winConfig={ mBounds=Rect(493, 0 - 1280, 720) " +
                "mAppBounds=Rect(493, 0 - 1280, 720) mWindowingMode=freeform }"

        assertEquals(listOf(493, 0, 1280, 720), parseTaskWindowBounds(configuration))
        assertEquals(listOf(493, 0, 1280, 720), parseTaskWindowAppBounds(configuration))
        assertEquals(5, parseTaskWindowingMode(configuration))
    }

    @Test
    fun `parses numeric windowing mode and signed coordinates`() {
        val configuration =
            "windowConfiguration={mBounds=Rect(-10, 20 - 1270, 700) mWindowingMode=1}"

        assertEquals(listOf(-10, 20, 1270, 700), parseTaskWindowBounds(configuration))
        assertEquals(1, parseTaskWindowingMode(configuration))
    }

    @Test
    fun `returns unknown geometry when framework text omits it`() {
        val configuration = "RunningTaskInfo{taskId=42 topActivity=com.waze/.MainActivity}"

        assertNull(parseTaskWindowBounds(configuration))
        assertNull(parseTaskWindowAppBounds(configuration))
        assertNull(parseTaskWindowingMode(configuration))
    }

    @Test
    fun `ignores volatile framework sequence counters`() {
        val first =
            "{ land winConfig={ mBounds=Rect(493, 0 - 1280, 720) } as.12 s.41 }"
        val second =
            "{  land  winConfig={ mBounds=Rect(493, 0 - 1280, 720) } as.13 s.42 }"

        assertEquals(
            stableTaskConfigurationSignature(first),
            stableTaskConfigurationSignature(second),
        )
    }

    @Test
    fun `detects a real app bounds transition`() {
        val regular =
            "{ land winConfig={ mAppBounds=Rect(493, 31 - 1280, 720) } s.41 }"
        val immersive =
            "{ land winConfig={ mAppBounds=Rect(493, 0 - 1280, 720) } s.42 }"

        assertNotEquals(
            stableTaskConfigurationSignature(regular),
            stableTaskConfigurationSignature(immersive),
        )
    }

    @Test
    fun `prefers geometry repair over a simultaneous configuration transition`() {
        val action = choosePanelWindowAction(
            firstObservation = false,
            identityChanged = false,
            configurationChanged = true,
            wrongGeometry = true,
            correctionSettled = true,
            heartbeatDue = true,
            eagerFirstObservation = false,
        )

        assertEquals(PanelWindowAction.REASSERT_BOUNDS, action)
    }

    @Test
    fun `refreshes viewport for an eager first observation with clipped geometry`() {
        val action = choosePanelWindowAction(
            firstObservation = true,
            identityChanged = true,
            configurationChanged = false,
            wrongGeometry = true,
            correctionSettled = true,
            heartbeatDue = false,
            eagerFirstObservation = true,
        )

        assertEquals(PanelWindowAction.REFRESH_VIEWPORT, action)
    }

    @Test
    fun `uses exact bounds for the first observation of a regular app`() {
        val action = choosePanelWindowAction(
            firstObservation = true,
            identityChanged = true,
            configurationChanged = false,
            wrongGeometry = false,
            correctionSettled = true,
            heartbeatDue = true,
            eagerFirstObservation = false,
        )

        assertEquals(PanelWindowAction.REASSERT_BOUNDS, action)
    }

    @Test
    fun `refreshes viewport when any panel app changes activity`() {
        val action = choosePanelWindowAction(
            firstObservation = false,
            identityChanged = true,
            configurationChanged = false,
            wrongGeometry = false,
            correctionSettled = true,
            heartbeatDue = false,
            eagerFirstObservation = false,
        )

        assertEquals(PanelWindowAction.REFRESH_VIEWPORT, action)
    }

    @Test
    fun `refreshes viewport when an existing activity changes configuration`() {
        val action = choosePanelWindowAction(
            firstObservation = false,
            identityChanged = false,
            configurationChanged = true,
            wrongGeometry = false,
            correctionSettled = true,
            heartbeatDue = false,
            eagerFirstObservation = false,
        )

        assertEquals(PanelWindowAction.REFRESH_VIEWPORT, action)
    }

    @Test
    fun `leaves a stable task untouched when heartbeat is disabled`() {
        val action = choosePanelWindowAction(
            firstObservation = false,
            identityChanged = false,
            configurationChanged = false,
            wrongGeometry = false,
            correctionSettled = true,
            heartbeatDue = false,
            eagerFirstObservation = false,
        )

        assertEquals(PanelWindowAction.NONE, action)
    }

    @Test
    fun `does not react to configuration generated by its own resize pulse`() {
        val action = choosePanelWindowAction(
            firstObservation = false,
            identityChanged = false,
            configurationChanged = true,
            wrongGeometry = false,
            correctionSettled = false,
            heartbeatDue = true,
            eagerFirstObservation = false,
        )

        assertEquals(PanelWindowAction.NONE, action)
    }
}

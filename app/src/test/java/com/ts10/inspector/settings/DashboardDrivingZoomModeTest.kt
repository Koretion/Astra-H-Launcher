package com.ts10.inspector.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardDrivingZoomModeTest {
    @Test
    fun `new installations keep explicit standard driving zoom`() {
        assertEquals(DashboardDrivingZoomMode.STANDARD_1X, DashboardSettings().drivingZoomMode)
    }

    @Test
    fun `driving zoom follows requested inverse visual scale`() {
        assertEquals(2f, DashboardDrivingZoomMode.CLOSER_0_5X.sceneScale)
        assertEquals(1f, DashboardDrivingZoomMode.STANDARD_1X.sceneScale)
        assertEquals(0.5f, DashboardDrivingZoomMode.FARTHER_2X.sceneScale)
    }

    @Test
    fun `automatic zoom uses 1x through 60 and 2x from 61 kmh`() {
        val automatic = DashboardDrivingZoomMode.AUTOMATIC_1X_2X

        assertEquals(DashboardDrivingZoomMode.STANDARD_1X.sceneScale, automatic.sceneScaleForSpeed(0))
        assertEquals(DashboardDrivingZoomMode.STANDARD_1X.sceneScale, automatic.sceneScaleForSpeed(59))
        assertEquals(DashboardDrivingZoomMode.STANDARD_1X.sceneScale, automatic.sceneScaleForSpeed(60))
        assertEquals(DashboardDrivingZoomMode.FARTHER_2X.sceneScale, automatic.sceneScaleForSpeed(61))
        assertEquals(DashboardDrivingZoomMode.FARTHER_2X.sceneScale, automatic.sceneScaleForSpeed(120))
    }

    @Test
    fun `manual zoom ignores vehicle speed`() {
        DashboardDrivingZoomMode.entries
            .filterNot { it == DashboardDrivingZoomMode.AUTOMATIC_1X_2X }
            .forEach { mode ->
                assertEquals(mode.sceneScale, mode.sceneScaleForSpeed(0))
                assertEquals(mode.sceneScale, mode.sceneScaleForSpeed(120))
            }
    }

    @Test
    fun `automatic driving scale stays stable until stopped artwork takes over`() {
        val automatic = DashboardDrivingZoomMode.AUTOMATIC_1X_2X

        assertEquals(0.5f, automatic.sceneScaleForMotion(speedKmh = 0, lastMovingScale = 0.5f))
        assertEquals(1f, automatic.sceneScaleForMotion(speedKmh = 0, lastMovingScale = 1f))
        assertEquals(1f, automatic.sceneScaleForMotion(speedKmh = 60, lastMovingScale = 0.5f))
        assertEquals(0.5f, automatic.sceneScaleForMotion(speedKmh = 61, lastMovingScale = 1f))
    }
}

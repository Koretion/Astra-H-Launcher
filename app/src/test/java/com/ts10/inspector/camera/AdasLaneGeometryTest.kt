package com.ts10.inspector.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdasLaneGeometryTest {
    @Test
    fun `vehicle warning provides a conservative visual fallback`() {
        val state = AdasSceneState(
            warningActive = true,
            lastWarning = AdasWarning.FCW,
        ).withWarningVehicleFallback()

        assertEquals(1, state.vehicles.size)
        assertEquals(0f, state.vehicles.single().horizontalPosition)
        assertEquals(18f, state.vehicles.single().distanceMeters)
    }

    @Test
    fun `lane warning never fabricates a vehicle`() {
        val state = AdasSceneState(
            warningActive = true,
            lastWarning = AdasWarning.LDW,
        ).withWarningVehicleFallback()

        assertTrue(state.vehicles.isEmpty())
    }

    @Test
    fun `detected boundary sorts far and near points`() {
        val boundary = detectedAdasLaneBoundary(
            first = AdasPoint(120f, 410f),
            second = AdasPoint(360f, 90f),
        )

        assertEquals(AdasPoint(360f, 90f), boundary?.far)
        assertEquals(AdasPoint(120f, 410f), boundary?.near)
    }

    @Test
    fun `missing endpoint does not create a false boundary`() {
        val boundary = detectedAdasLaneBoundary(
            first = AdasPoint(0f, 0f),
            second = AdasPoint(120f, 410f),
        )

        assertNull(boundary)
    }

    @Test
    fun `point on camera edge remains a valid detection`() {
        val boundary = detectedAdasLaneBoundary(
            first = AdasPoint(0f, 90f),
            second = AdasPoint(120f, 410f),
        )

        assertEquals(AdasPoint(0f, 90f), boundary?.far)
        assertEquals(AdasPoint(120f, 410f), boundary?.near)
    }
}

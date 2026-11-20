package com.ts10.inspector.vehicle.qf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QfDataShareBridgeTest {
    @Test fun `maps center to image 45`() {
        val result = qfSteeringBroadcast(0f)
        assertEquals(45, result.angleLevel)
        assertEquals(0, result.angleDegrees)
        assertEquals(540f, result.rangeDegrees)
    }

    @Test fun `maps HS left-positive to QF left images`() {
        val result = qfSteeringBroadcast(270f)
        assertEquals(22, result.angleLevel)
        assertEquals(-270, result.angleDegrees)
    }

    @Test fun `maps HS right-negative to QF right images`() {
        val result = qfSteeringBroadcast(-270f)
        assertEquals(67, result.angleLevel)
        assertEquals(270, result.angleDegrees)
    }

    @Test fun `clamps full lock to image limits`() {
        assertEquals(0, qfSteeringBroadcast(900f).angleLevel)
        assertEquals(90, qfSteeringBroadcast(-900f).angleLevel)
    }

    @Test fun `stops retries only for a protected QF broadcast`() {
        assertTrue(isTerminalQfSwaError(SecurityException("protected broadcast")))
        assertFalse(isTerminalQfSwaError(IllegalStateException("temporary failure")))
    }
}

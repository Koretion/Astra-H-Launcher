package com.ts10.inspector.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteeringAngleMemoryTest {
    @Test
    fun `keeps the last valid angle while the sensor is not initialised`() {
        val memory = SteeringAngleMemory(writeIntervalMs = 1_000L)
        assertNull(memory.resolve(null, 0L))
        assertEquals(30f, memory.resolve(30f, 0L)!!, 0f)
        // A later change inside the write interval is stored once the interval has elapsed.
        assertEquals(45f, memory.resolve(45f, 500L)!!, 0f)
        assertEquals(30f, memory.resolve(null, 600L)!!, 0f)
        assertEquals(45f, memory.resolve(45f, 1_200L)!!, 0f)
        assertEquals(45f, memory.resolve(null, 1_300L)!!, 0f)
    }
}

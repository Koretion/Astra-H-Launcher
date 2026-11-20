package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanFaultDiscoveryTrackerTest {
    @Test
    fun `confirmed right lamp fault is exported but normal indicator frames are not`() {
        val tracker = CanFaultDiscoveryTracker()
        tracker.accept(frame(0, 0x260, 0x3A, 0x43, 0x7F))
        tracker.accept(frame(320_000, 0x260, 0x3A, 0x21, 0x7C))

        val candidates = tracker.snapshot()
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().confirmed)
        assertEquals("3A217C", candidates.single().payload)
    }

    @Test
    fun `repeated unknown variant becomes a correlation candidate`() {
        val tracker = CanFaultDiscoveryTracker()
        tracker.accept(frame(0, 0x555, 0x00, 0x00))
        tracker.accept(frame(1_000_000, 0x555, 0xA1, 0x02))
        tracker.accept(frame(2_000_000, 0x555, 0xA1, 0x02))
        tracker.accept(frame(3_000_000, 0x555, 0xA1, 0x02))

        val candidate = tracker.snapshot().single()
        assertEquals("A102", candidate.payload)
        assertEquals(3L, candidate.occurrences)
        assertEquals(1L, candidate.transitionsInto)
        assertEquals(1_000.0, candidate.averagePeriodMs!!, 0.001)
    }

    @Test
    fun `continuously changing decoded measurements are excluded`() {
        val tracker = CanFaultDiscoveryTracker()
        repeat(10) { index ->
            tracker.accept(frame(index * 100_000L, 0x375, 0x00, index))
        }

        assertTrue(tracker.snapshot().isEmpty())
    }

    private fun frame(timestampUs: Long, identifier: Long, vararg bytes: Int) = Esp32CanFrame(
        sequence = timestampUs,
        timestampUs = timestampUs,
        identifier = identifier,
        flags = 0,
        dlc = bytes.size,
        data = ByteArray(bytes.size) { bytes[it].toByte() },
        bus = Esp32CanBus.SW_CAN,
    )
}

package com.ts10.inspector.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneChangeDetectorTest {
    private val logs = mutableListOf<String>()
    private val detector = LaneChangeDetector { logs += it }
    private var now = 0L

    /** Feeds [durationMs] of 10 Hz samples. */
    private fun drive(
        durationMs: Long,
        steering: Float?,
        speed: Float = 45f,
        left: Boolean = false,
        right: Boolean = false,
        lane: RoadLaneGeometry? = null,
        lanes: Int? = 2,
    ): Int {
        var result = detector.laneFromRight
        val end = now + durationMs
        while (now < end) {
            result = detector.update(LaneChangeSample(now, speed, steering, left, right, lane, lanes))
            now += 100
        }
        return result
    }

    @Test
    fun `steering S shape confirms an indicated change to the left lane`() {
        // Straight-ahead reading of the real Astra sensor is about +4.5 deg.
        drive(3_000, steering = 4.5f)
        drive(1_000, steering = 4.5f + 6f, left = true)
        val lane = drive(1_500, steering = 4.5f - 3f, left = true)
        assertEquals(1, lane)
        assertTrue(logs.toString(), logs.last().contains("decision=commit") && logs.last().contains("steering=s-shape"))
    }

    @Test
    fun `real right lane change shape is recognised and bounded by the OSM edge`() {
        drive(3_000, steering = 4.5f, lanes = 3)
        // Capture of 2026-11-23 at 42 km/h: the wheel moves right (-6 deg) just before the first
        // flash reaches the CAN, then counter-steers.
        drive(800, steering = -1.5f, lanes = 3)
        drive(200, steering = -1.5f, right = true, lanes = 3)
        val lane = drive(2_000, steering = 7.5f, right = true, lanes = 3)
        // Index 0 is the rightmost lane: the change is rejected at the OSM edge from lane 0.
        assertEquals(0, lane)
        assertTrue(logs.last().contains("reject:osm-edge"))
    }

    @Test
    fun `junction turn with the indicator never moves the lane`() {
        drive(3_000, steering = 4.5f, speed = 30f, lanes = 3)
        drive(1_000, steering = 4.5f + 20f, speed = 18f, left = true, lanes = 3)
        drive(2_000, steering = 4.5f + 150f, speed = 15f, left = true, lanes = 3)
        val lane = drive(6_000, steering = 4.5f - 10f, speed = 22f, lanes = 3)
        assertEquals(0, lane)
        assertTrue(logs.last().contains("reject"))
    }

    @Test
    fun `indicator without steering or camera confirmation is rejected`() {
        drive(3_000, steering = 4.5f)
        drive(3_000, steering = 4.5f, left = true)
        val lane = drive(5_000, steering = 4.5f)
        assertEquals(0, lane)
        assertTrue(logs.last().contains("reject:no-confirmation"))
    }

    @Test
    fun `camera line crossing confirms the change without steering data`() {
        drive(1_000, steering = null, left = true, lane = lane(0f))
        drive(500, steering = null, left = true, lane = lane(0.12f))
        val lane = drive(300, steering = null, left = true, lane = lane(-0.10f))
        assertEquals(1, lane)
        assertTrue(logs.last().contains("camera=crossing"))
    }

    @Test
    fun `hazard lights and a single OSM lane never count`() {
        drive(3_000, steering = 4.5f)
        drive(2_000, steering = 10.5f, left = true, right = true)
        assertEquals(0, drive(2_000, steering = 1.5f, left = true, right = true))

        drive(3_000, steering = 4.5f, lanes = 1)
        drive(1_000, steering = 10.5f, left = true, lanes = 1)
        assertEquals(0, drive(1_500, steering = 1.5f, left = true, lanes = 1))
        assertTrue(logs.last().contains("reject:osm-single-lane"))
    }

    private fun lane(offset: Float) = RoadLaneGeometry(
        left = RoadLaneBoundary(0.45f + offset, 0.43f, 0.15f + offset, 0.94f, 0.3f),
        right = RoadLaneBoundary(0.55f + offset, 0.43f, 0.85f + offset, 0.94f, 0.3f),
        confidence = 0.3f,
    )
}

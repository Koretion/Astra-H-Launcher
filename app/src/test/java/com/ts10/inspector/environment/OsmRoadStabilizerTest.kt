package com.ts10.inspector.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsmRoadStabilizerTest {
    private fun road(
        id: Long,
        speed: Int?,
        lanes: Int? = 2,
        estimated: Boolean = false,
        highway: String = "motorway",
        ref: String? = "A-357",
        roundabout: Boolean = false,
    ) = OsmRoadContext(
        wayId = id, name = null, reference = ref, highway = highway, maxSpeedKmh = speed,
        lanesInTravelDirection = lanes, lanesEstimated = estimated, oneway = true, isRoundabout = roundabout,
        centerLineStyle = RoadLineStyle.SOLID, laneLineStyles = emptyList(), distanceMeters = 2.0,
        headingDifferenceDegrees = 0.0, curvature = 0f,
        tags = buildMap { put("highway", highway); ref?.let { put("ref", it) } },
    )

    @Test
    fun keepsSpeedOnNextSegmentOfSameRoad() {
        val stabilizer = OsmRoadStabilizer()
        assertEquals(80, stabilizer.stabilize(road(1, 80), 0L).maxSpeedKmh)
        assertEquals(80, stabilizer.stabilize(road(2, null), 1_000L).maxSpeedKmh)
        // Pasado el tiempo de espera ya no se inventa el límite.
        assertNull(stabilizer.stabilize(road(2, null), 70_000L).maxSpeedKmh)
    }

    @Test
    fun briefJumpToSlipRoadIsIgnored() {
        val stabilizer = OsmRoadStabilizer()
        stabilizer.stabilize(road(1, 80), 0L)
        val slip = road(9, null, lanes = 1, highway = "motorway_link", ref = null)
        assertEquals(1L, stabilizer.stabilize(slip, 500L).wayId)
        assertEquals(1L, stabilizer.stabilize(slip, 2_500L).wayId)
        // Mantenido 3 s, el cambio es real.
        assertEquals(9L, stabilizer.stabilize(slip, 3_600L).wayId)
    }

    @Test
    fun keepsRealLanesWhenNextSegmentOnlyEstimatesThem() {
        val stabilizer = OsmRoadStabilizer()
        stabilizer.stabilize(road(1, 80, lanes = 3), 0L)
        assertEquals(3, stabilizer.stabilize(road(2, 80, lanes = 1, estimated = true), 1_000L).lanesInTravelDirection)
    }

    @Test
    fun roundaboutKeepsTheZoneLimit() {
        val stabilizer = OsmRoadStabilizer()
        stabilizer.stabilize(road(1, 50, highway = "primary", ref = null), 0L)
        val roundabout = road(5, null, highway = "primary", ref = null, roundabout = true)
        assertEquals(50, stabilizer.stabilize(roundabout, 1_000L).maxSpeedKmh)
    }
}

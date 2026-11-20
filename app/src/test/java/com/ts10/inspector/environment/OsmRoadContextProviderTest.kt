package com.ts10.inspector.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmRoadContextProviderTest {
    @Test
    fun `downloads one compact road envelope including its speed tags`() {
        val query = buildOsmRoadQuery(40.12345678, -3.87654321, 900)

        assertTrue("around:900,40.1234568,-3.8765432" in query)
        assertEquals(1, Regex("way\\(around:").findAll(query).count())
        assertTrue("out tags geom" in query)
    }

    @Test
    fun `parses drivable ways from official OSM map fallback`() {
        val xml = """
            <osm version="0.6">
              <node id="1" lat="40.0" lon="-3.0" />
              <node id="2" lat="40.001" lon="-3.0" />
              <node id="3" lat="40.0" lon="-3.001" />
              <way id="42">
                <nd ref="1"/><nd ref="2"/>
                <tag k="highway" v="primary"/><tag k="maxspeed" v="80"/>
                <tag k="lanes" v="2"/>
              </way>
              <way id="43">
                <nd ref="1"/><nd ref="3"/><tag k="highway" v="footway"/>
              </way>
            </osm>
        """.trimIndent()

        val ways = parseOsmMapResponse(xml)

        assertEquals(1, ways.size)
        assertEquals(42L, ways.single().id)
        assertEquals("80", ways.single().tags["maxspeed"])
        assertEquals(2, ways.single().geometry.size)
    }

    @Test
    fun `fallback ways survive cache serialization`() {
        val original = listOf(
            OsmRoadWay(
                9L,
                mapOf("highway" to "secondary", "maxspeed" to "50", "lanes" to "2"),
                listOf(OsmCoordinate(40.0, -3.0), OsmCoordinate(40.001, -3.0)),
            ),
        )

        val restored = parseOsmRoadResponse(serializeOsmRoadWays(original))

        assertEquals(original, restored)
    }

    @Test
    fun `parses metric mph and unsupported speed values`() {
        assertEquals(30, parseMaxSpeedKmh("30"))
        assertEquals(80, parseMaxSpeedKmh("50 mph"))
        assertEquals(18, parseMaxSpeedKmh("10 knots"))
        assertNull(parseMaxSpeedKmh("ES:urban"))
        assertNull(parseMaxSpeedKmh("signals"))
    }

    @Test
    fun `uses directional lane count before estimated total`() {
        assertEquals(2 to false, resolveDirectionalLanes(mapOf("lanes:forward" to "2", "lanes" to "5"), true, false))
        assertEquals(3 to true, resolveDirectionalLanes(mapOf("lanes" to "5"), true, false))
        assertEquals(3 to false, resolveDirectionalLanes(mapOf("lanes" to "3"), true, true))
    }

    @Test
    fun `derives solid boundary from lane change restriction`() {
        val styles = resolveLaneLineStyles(3, "yes|not_left|no", "yes")

        assertEquals(listOf(RoadLineStyle.SOLID, RoadLineStyle.SOLID), styles)
        assertEquals(listOf(RoadLineStyle.NONE, RoadLineStyle.NONE), resolveLaneLineStyles(3, null, "no"))
    }

    @Test
    fun `matches nearby aligned road and applies directional tags`() {
        val road = OsmRoadWay(
            id = 42L,
            tags = mapOf(
                "highway" to "primary",
                "name" to "Avenida de prueba",
                "maxspeed:forward" to "70",
                "maxspeed:backward" to "50",
                "lanes:forward" to "2",
                "lanes:backward" to "1",
                "divider" to "solid_line",
            ),
            geometry = listOf(
                OsmCoordinate(40.0000, -3.0000),
                OsmCoordinate(40.0100, -3.0000),
            ),
        )

        val match = matchOsmRoad(
            ways = listOf(road),
            latitude = 40.005,
            longitude = -3.00005,
            bearingDegrees = 2f,
            previousWayId = null,
            maxDistanceMeters = 40.0,
        )

        assertNotNull(match)
        assertEquals(70, match?.maxSpeedKmh)
        assertEquals(2, match?.lanesInTravelDirection)
        assertFalse(match?.lanesEstimated ?: true)
        assertEquals(RoadLineStyle.SOLID, match?.centerLineStyle)
        assertTrue((match?.distanceMeters ?: 100.0) < 10.0)
    }

    @Test
    fun `rejects road outside matching radius`() {
        val road = OsmRoadWay(
            id = 1,
            tags = mapOf("highway" to "residential", "maxspeed" to "30"),
            geometry = listOf(OsmCoordinate(40.0, -3.0), OsmCoordinate(40.01, -3.0)),
        )

        val match = matchOsmRoad(listOf(road), 40.005, -3.002, 0f, null, 40.0)

        assertNull(match)
    }

    @Test
    fun `rejects travel against a one way road`() {
        val road = OsmRoadWay(
            id = 2,
            tags = mapOf("highway" to "primary", "oneway" to "yes", "maxspeed" to "50"),
            geometry = listOf(OsmCoordinate(40.0, -3.0), OsmCoordinate(40.01, -3.0)),
        )

        assertNull(matchOsmRoad(listOf(road), 40.005, -3.0, 180f, null, 40.0))
        assertNotNull(matchOsmRoad(listOf(road), 40.005, -3.0, 0f, null, 40.0))
    }

    @Test
    fun `nearby road without speed does not hide selected known limit`() {
        val selected = OsmRoadWay(
            id = 10,
            tags = mapOf("highway" to "primary", "maxspeed" to "80"),
            geometry = listOf(OsmCoordinate(40.0, -3.0), OsmCoordinate(40.01, -3.0)),
        )
        val untaggedParallel = OsmRoadWay(
            id = 11,
            tags = mapOf("highway" to "residential"),
            geometry = listOf(OsmCoordinate(40.0, -2.99996), OsmCoordinate(40.01, -2.99996)),
        )

        val match = matchOsmRoad(
            listOf(selected, untaggedParallel),
            40.005,
            -3.00001,
            0f,
            null,
            40.0,
        )

        assertEquals(80, match?.maxSpeedKmh)
    }

    @Test
    fun `inherits a unique speed from an adjacent segment of the same named road`() {
        val selected = OsmRoadWay(
            id = 20,
            tags = mapOf("highway" to "primary", "name" to "Avenida continua"),
            geometry = listOf(OsmCoordinate(40.0, -3.0), OsmCoordinate(40.01, -3.0)),
        )
        val taggedContinuation = OsmRoadWay(
            id = 21,
            tags = mapOf("highway" to "primary", "name" to "Avenida continua", "maxspeed" to "50"),
            geometry = listOf(OsmCoordinate(40.0, -2.9999), OsmCoordinate(40.01, -2.9999)),
        )

        val match = matchOsmRoad(listOf(selected, taggedContinuation), 40.005, -3.0, 0f, null, 40.0)

        assertEquals(50, match?.maxSpeedKmh)
    }

    @Test
    fun `derives a right bend from road geometry ahead`() {
        val geometry = listOf(
            OsmCoordinate(40.0000, -3.0000),
            OsmCoordinate(40.0010, -3.0000),
            OsmCoordinate(40.0020, -2.9998),
            OsmCoordinate(40.0028, -2.9992),
            OsmCoordinate(40.0032, -2.9982),
        )

        assertTrue(roadCurvatureAhead(geometry, segmentIndex = 0, forward = true) > 0f)
        assertTrue(roadCurvatureAhead(geometry, segmentIndex = 3, forward = false) < 0f)
    }

    @Test
    fun `exposes an OSM roundabout to the road renderer`() {
        val road = OsmRoadWay(
            id = 3,
            tags = mapOf(
                "highway" to "secondary",
                "junction" to "roundabout",
                "lanes" to "2",
            ),
            geometry = listOf(
                OsmCoordinate(40.0000, -3.0000),
                OsmCoordinate(40.0005, -2.9999),
                OsmCoordinate(40.0008, -2.9995),
            ),
        )

        val match = matchOsmRoad(listOf(road), 40.0004, -2.9999, 10f, null, 40.0)

        assertTrue(match?.isRoundabout == true)
        assertTrue(match?.oneway == true)
    }
}

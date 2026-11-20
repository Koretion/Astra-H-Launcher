package com.ts10.inspector.environment

import com.ts10.inspector.panel.PanelRepairBackoff
import com.ts10.inspector.vehicle.fuel.FuelReserveConfirmation
import com.ts10.inspector.vehicle.fuel.HsFuelGaugeFilter
import com.ts10.inspector.vehicle.fuel.isFuelVehicleStationary
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OctoberDriveRegressionTest {
    @Test fun `replay final capture reproduces three old alerts and rejects them with confirmation`() {
        val relative = "debugEsp32Sniffer/TS10_QF_Log_20261004_200717.txt"
        val capture = listOf(File(relative), File("../$relative")).firstOrNull { it.isFile }
        // Raw captures are local diagnostic artifacts, not required for a clean checkout.
        org.junit.Assume.assumeTrue("Local October capture available", capture != null)
        val oldFilter = HsFuelGaugeFilter(reserveWindowSize = 31)
        val newFilter = HsFuelGaugeFilter()
        val reserve = FuelReserveConfirmation()
        var oldLow = false
        var oldEdges = 0
        var newWarnings = 0
        capture!!.useLines { lines ->
            lines.filter { it.startsWith("esp_us=") && "bus=HS-CAN" in it && " id=381 " in it }
                .forEach { line ->
                    val raw = line.substringAfter(" data=").substring(10, 12).toInt(16)
                    if (raw !in 0x20..0xF0) return@forEach
                    val now = line.substringAfter("android_ns=").substringBefore(' ').toLong() / 1_000_000
                    oldFilter.accept(raw)?.let {
                        if (it.lowFuel && !oldLow) oldEdges++
                        oldLow = it.lowFuel
                    }
                    newFilter.accept(raw)?.let {
                        if (reserve.accept(it.senderLevel, now)) newWarnings++
                    }
                }
        }
        assertEquals(3, oldEdges)
        assertEquals(0, newWarnings)
    }

    @Test fun `reserve rejects bends and requires sustained filtered level`() {
        val filter = HsFuelGaugeFilter()
        val reserve = FuelReserveConfirmation()
        var now = 0L
        fun feed(raw: Int, count: Int): Boolean {
            var low = false
            repeat(count) {
                now += 100
                filter.accept(raw)?.let { low = reserve.accept(it.senderLevel, now) }
            }
            return low
        }
        assertFalse(feed(157, 400))
        assertFalse(feed(190, 50)) // five seconds in a bend
        assertFalse(feed(157, 400))
        assertTrue(feed(187, 1_000)) // real, sustained reserve
        assertTrue(feed(172, 400)) // hysteresis: do not rearm on tiny oscillations
        assertFalse(feed(157, 400))
    }

    @Test fun `reserve does not confirm across missing samples`() {
        val reserve = FuelReserveConfirmation()
        assertFalse(reserve.accept(190, 0))
        assertFalse(reserve.accept(190, 120_000))
    }

    @Test fun `unknown stale or moving speed cannot authorize refuel`() {
        assertFalse(isFuelVehicleStationary(null, 0))
        assertFalse(isFuelVehicleStationary(0, null))
        assertFalse(isFuelVehicleStationary(0, 3_001))
        assertFalse(isFuelVehicleStationary(30, 0))
        assertTrue(isFuelVehicleStationary(0, 100))
    }

    @Test fun `coarse network fix and old GPS cannot choose a road`() {
        assertFalse(isUsableRoadFix(300f, 0))
        assertFalse(isUsableRoadFix(null, 0))
        assertFalse(isUsableRoadFix(41f, 0))
        assertFalse(isUsableRoadFix(10f, 15_001))
        assertTrue(isUsableRoadFix(28f, 500))
        assertTrue(isUsableRoadFix(15f, 500))
    }

    @Test fun `parallel roads with conflicting limits suppress the sign`() {
        val main = OsmRoadWay(1, mapOf("highway" to "primary", "maxspeed" to "100"),
            listOf(OsmCoordinate(40.0, -3.0), OsmCoordinate(40.01, -3.0)))
        val parallel = OsmRoadWay(2, mapOf("highway" to "residential", "maxspeed" to "30"),
            listOf(OsmCoordinate(40.0, -3.0001), OsmCoordinate(40.01, -3.0001)))
        val result = matchOsmRoad(listOf(main, parallel), 40.005, -3.00005, 0f, null, 55.0)
        assertNotNull(result)
        assertNull(result!!.maxSpeedKmh)
    }

    @Test fun `crossing road cannot supply the speed limit`() {
        val crossing = OsmRoadWay(1, mapOf("highway" to "residential", "maxspeed" to "30"),
            listOf(OsmCoordinate(40.005, -3.01), OsmCoordinate(40.005, -2.99)))
        assertNull(matchOsmRoad(listOf(crossing), 40.005, -3.0, 0f, null, 55.0))
    }

    @Test fun `unresponsive window geometry backs off but a new task repairs immediately`() {
        val backoff = PanelRepairBackoff()
        assertTrue(backoff.allow("4516:637", 0))
        assertTrue(backoff.allow("4516:637", 400))
        assertTrue(backoff.allow("4516:637", 800))
        assertFalse(backoff.allow("4516:637", 1_200))
        assertFalse(backoff.allow("4516:637", 10_800))
        assertTrue(backoff.allow("4517:677", 11_000))
    }
}

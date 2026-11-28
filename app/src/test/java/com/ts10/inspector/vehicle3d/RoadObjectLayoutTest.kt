package com.ts10.inspector.vehicle3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadObjectLayoutTest {
    private fun car(id: Long, lateral: Float, ahead: Float, kind: Vehicle3dRoadObjectKind = Vehicle3dRoadObjectKind.CAR) =
        Vehicle3dRoadObject(id, kind, lateral, ahead, egoLane = false)

    @Test
    fun dropsVehiclesOffTheRoadAndCentresTheRest() {
        // Dos carriles y el coche en el derecho: solo existe el carril -1 a la izquierda.
        val result = arrangeRoadObjects(listOf(car(1, -0.8f, 10f), car(2, -2.3f, 12f), car(3, 0.2f, 20f)), 2, 0)
        assertEquals(listOf(1L, 3L), result.map { it.id }.sorted())
        assertEquals(-1f, result.first { it.id == 1L }.lateralLanes)
        assertTrue(result.first { it.id == 3L }.egoLane)
    }

    @Test
    fun vehiclesInTheSameLaneDoNotOverlap() {
        val result = arrangeRoadObjects(listOf(car(1, 0f, 2.5f), car(2, 0.1f, 2.5f + 3f)), 2, 0)
        val first = result.first { it.id == 1L }
        val second = result.first { it.id == 2L }
        assertTrue(second.aheadMeters >= first.aheadMeters + ROAD_OBJECT_LENGTH.getValue(Vehicle3dRoadObjectKind.CAR))
    }

    @Test
    fun duplicateTrackOfTheSameCarIsDropped() {
        val result = arrangeRoadObjects(listOf(car(1, 0f, 8f), car(2, 0.2f, 8.6f)), 2, 0)
        assertEquals(1, result.size)
    }
}

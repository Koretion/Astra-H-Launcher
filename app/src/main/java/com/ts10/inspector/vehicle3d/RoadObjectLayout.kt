package com.ts10.inspector.vehicle3d

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coloca los objetos detectados sobre la carretera dibujada. En la TS10 los coches se veían fuera
 * de los carriles, pegados y unos encima de otros: la distancia sale de una estimación monocular
 * (todos los cercanos caían en la distancia mínima) y el tracker a veces da dos pistas al mismo
 * coche. Aquí:
 * - lo que queda más de medio carril fuera de la calzada (vía paralela, aparcados, acera) no se
 *   dibuja; los peatones sí pueden estar en el arcén;
 * - los vehículos se centran en su carril, como circulan de verdad;
 * - en cada carril, de cerca a lejos, se descartan duplicados y se deja un hueco mínimo detrás
 *   del vehículo anterior para que no se solapen.
 */
internal fun arrangeRoadObjects(
    objects: List<Vehicle3dRoadObject>,
    /** Carriles OSM en el sentido de la marcha; 0 si no se conocen. */
    roadLanes: Int,
    /** Carril del coche contado desde la derecha. */
    laneFromRight: Int,
): List<Vehicle3dRoadObject> {
    val (leftmost, rightmost) = if (roadLanes > 0) {
        val own = laneFromRight.coerceIn(0, roadLanes - 1)
        -(roadLanes - 1 - own) to own
    } else {
        -UNKNOWN_ROAD_SIDE_LANES to UNKNOWN_ROAD_SIDE_LANES
    }
    val placed = objects.mapNotNull { item ->
        val person = item.kind == Vehicle3dRoadObjectKind.PERSON
        val margin = if (person) PERSON_SHOULDER_LANES else OFF_ROAD_LANES
        if (item.lateralLanes < leftmost - margin || item.lateralLanes > rightmost + margin) return@mapNotNull null
        if (person) return@mapNotNull item
        val lane = item.lateralLanes.roundToInt().coerceIn(leftmost, rightmost)
        item.copy(lateralLanes = lane.toFloat(), egoLane = lane == 0)
    }
    return placed.groupBy { it.lateralLanes.roundToInt() }.flatMap { (_, inLane) ->
        var previous: Vehicle3dRoadObject? = null
        var previousRawAhead = 0f
        inLane.sortedBy { it.aheadMeters }.mapNotNull { item ->
            val last = previous
            if (last == null) {
                previous = item
                previousRawAhead = item.aheadMeters
                return@mapNotNull item
            }
            // Dos pistas casi en el mismo punto son el mismo vehículo visto dos veces.
            if (abs(item.aheadMeters - previousRawAhead) < DUPLICATE_METERS && item.kind == last.kind) {
                return@mapNotNull null
            }
            previousRawAhead = item.aheadMeters
            // aheadMeters mide hasta la cara cercana: el siguiente empieza tras la cola del anterior.
            val minAhead = last.aheadMeters + lengthOf(last) + GAP_METERS
            val moved = if (item.aheadMeters < minAhead) item.copy(aheadMeters = minAhead) else item
            previous = moved
            moved
        }
    }
}

private fun lengthOf(item: Vehicle3dRoadObject): Float = ROAD_OBJECT_LENGTH[item.kind] ?: 4.3f

private const val UNKNOWN_ROAD_SIDE_LANES = 2
private const val OFF_ROAD_LANES = 0.6f
private const val PERSON_SHOULDER_LANES = 1.2f
private const val DUPLICATE_METERS = 1.5f
private const val GAP_METERS = 1.5f

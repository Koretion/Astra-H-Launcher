package com.ts10.inspector.vehicle3d

/** Same lane width as the road shader (`W`). */
internal const val LANE_WIDTH_METERS = 3.5f
internal const val CUBE_STRIDE_BYTES = 6 * 4
internal const val OBJECT_ALPHA = 0.94f
/** How much of the lane colour (red ahead in the car's lane, blue elsewhere) tints the body. */
internal const val OBJECT_TINT = 0.45f
internal val EGO_LANE_OBJECT = floatArrayOf(1f, 0.35f, 0.39f)
internal val OTHER_LANE_OBJECT = floatArrayOf(0.32f, 0.78f, 1f)
internal val DARK_OBJECT_BODY = floatArrayOf(0.62f, 0.66f, 0.70f)
internal val LIGHT_OBJECT_BODY = floatArrayOf(0.34f, 0.38f, 0.42f)
internal val OBJECT_GLASS = floatArrayOf(0.07f, 0.09f, 0.12f)
internal val OBJECT_TYRE = floatArrayOf(0.03f, 0.035f, 0.04f)
internal val OBJECT_TAIL = floatArrayOf(1f, 0.12f, 0.08f)

internal enum class PartRole { BODY, GLASS, TYRE, TAIL }

/** Box centred at (x, y, z) in the object's frame: x right, y up from the ground, +z towards its rear. */
internal class RoadObjectPart(val role: PartRole, val x: Float, val y: Float, val z: Float, val size: FloatArray)

private fun part(role: PartRole, x: Float, bottom: Float, z: Float, width: Float, height: Float, length: Float) =
    RoadObjectPart(role, x, bottom + height / 2f, z, floatArrayOf(width, height, length))

/** Four tyres and two tail lamps around a body of the given length and width. */
private fun wheelsAndLamps(width: Float, length: Float, axle: Float, lampY: Float): List<RoadObjectPart> = buildList {
    for (side in listOf(-1f, 1f)) {
        for (end in listOf(-1f, 1f)) {
            add(part(PartRole.TYRE, side * (width / 2f - 0.1f), 0f, end * axle, 0.26f, 0.62f, 0.64f))
        }
        add(part(PartRole.TAIL, side * (width / 2f - 0.22f), lampY, length / 2f + 0.01f, 0.34f, 0.14f, 0.04f))
    }
}

internal val ROAD_OBJECT_LENGTH: Map<Vehicle3dRoadObjectKind, Float> = mapOf(
    Vehicle3dRoadObjectKind.CAR to 4.3f,
    Vehicle3dRoadObjectKind.LARGE_VEHICLE to 10f,
    Vehicle3dRoadObjectKind.TWO_WHEELER to 1.9f,
    Vehicle3dRoadObjectKind.PERSON to 0.35f,
)

internal val ROAD_OBJECT_PARTS: Map<Vehicle3dRoadObjectKind, List<RoadObjectPart>> = mapOf(
    Vehicle3dRoadObjectKind.CAR to listOf(
        part(PartRole.BODY, 0f, 0.28f, 0f, 1.8f, 0.72f, 4.3f),
        part(PartRole.GLASS, 0f, 1.0f, 0.25f, 1.5f, 0.52f, 2.2f),
    ) + wheelsAndLamps(width = 1.8f, length = 4.3f, axle = 1.35f, lampY = 0.78f),
    Vehicle3dRoadObjectKind.LARGE_VEHICLE to listOf(
        part(PartRole.BODY, 0f, 0.4f, 0f, 2.5f, 2.6f, 10f),
        part(PartRole.GLASS, 0f, 1.7f, -4.6f, 2.3f, 1.0f, 0.9f),
    ) + wheelsAndLamps(width = 2.5f, length = 10f, axle = 3.6f, lampY = 0.7f),
    Vehicle3dRoadObjectKind.TWO_WHEELER to listOf(
        part(PartRole.TYRE, 0f, 0f, -0.7f, 0.14f, 0.62f, 0.62f),
        part(PartRole.TYRE, 0f, 0f, 0.7f, 0.14f, 0.62f, 0.62f),
        part(PartRole.BODY, 0f, 0.35f, 0f, 0.36f, 0.5f, 1.6f),
        part(PartRole.BODY, 0f, 0.85f, 0.15f, 0.46f, 0.75f, 0.45f),
        part(PartRole.GLASS, 0f, 1.6f, 0.1f, 0.3f, 0.3f, 0.3f),
        part(PartRole.TAIL, 0f, 0.8f, 0.82f, 0.18f, 0.1f, 0.04f),
    ),
    Vehicle3dRoadObjectKind.PERSON to listOf(
        part(PartRole.BODY, 0f, 0f, 0f, 0.42f, 0.85f, 0.3f),
        part(PartRole.BODY, 0f, 0.85f, 0f, 0.5f, 0.65f, 0.32f),
        part(PartRole.BODY, 0f, 1.52f, 0f, 0.24f, 0.26f, 0.24f),
    ),
)

/** 36 vertices of a unit cube centred at the origin, interleaved position (3) and normal (3). */
internal fun unitCubeVertices(): FloatArray {
    val faces = arrayOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
    )
    val out = ArrayList<Float>(36 * 6)
    faces.forEach { n ->
        // Two axes spanning the face, then the four corners as two triangles.
        val axisU = if (n[0] != 0f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        val axisV = floatArrayOf(
            n[1] * axisU[2] - n[2] * axisU[1],
            n[2] * axisU[0] - n[0] * axisU[2],
            n[0] * axisU[1] - n[1] * axisU[0],
        )
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to -1f, 1f to 1f, -1f to 1f)
        corners.forEach { (u, v) ->
            for (axis in 0 until 3) out += 0.5f * (n[axis] + u * axisU[axis] + v * axisV[axis])
            for (axis in 0 until 3) out += n[axis]
        }
    }
    return out.toFloatArray()
}

package com.ts10.inspector.vehicle3d

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Headlamp, fog-lamp and third brake light positions measured on the GLB in its rest pose. */
internal class LampAnchors(
    val headlamps: List<FloatArray>,
    val fogLamps: List<FloatArray>,
    val frontZ: Float,
    val rearZ: Float,
    val thirdBrake: FloatArray,
)

internal class LightPool(
    val centerZ: Float,
    val halfWidth: Float,
    val halfLength: Float,
    val color: FloatArray,
    val strength: Float,
)

internal class BeamSpec(
    val length: Float,
    val halfWidthDegrees: Float,
    val halfHeightDegrees: Float,
    val tiltDegrees: Float,
    val strength: Float,
)

private const val LAMP_INSET = 0.12f
internal const val BEAM_SEGMENTS = 24
/** Bytes per beam vertex: x, y, z and the distance along the beam. */
internal const val BEAM_STRIDE = 16
internal val DIPPED_BEAM = BeamSpec(length = 12f, halfWidthDegrees = 20f, halfHeightDegrees = 5f, tiltDegrees = 2.5f, strength = 0.16f)
internal val HIGH_BEAM = BeamSpec(length = 24f, halfWidthDegrees = 12f, halfHeightDegrees = 5f, tiltDegrees = 0.5f, strength = 0.13f)
internal val FOG_BEAM = BeamSpec(length = 7f, halfWidthDegrees = 34f, halfHeightDegrees = 4f, tiltDegrees = 3f, strength = 0.16f)

/** World bounds of every light node subtree, measured in the rest pose. */
private fun lightBounds(
    model: Vehicle3dGlbModel,
    lightNodes: Map<Int, String>,
    restLocal: List<FloatArray>,
): Map<String, Pair<FloatArray, FloatArray>> {
    val result = HashMap<String, Pair<FloatArray, FloatArray>>()
    val stack = ArrayDeque<Triple<Int, FloatArray, String?>>()
    model.roots.forEach { stack.addLast(Triple(it, Mat4.identity(), null)) }
    while (stack.isNotEmpty()) {
        val (index, parent, inherited) = stack.removeLast()
        val node = model.nodes[index]
        val own = lightNodes[index]
        val light = own ?: inherited
        val rest = if (own != null) {
            Mat4.fromTrs(node.translation, node.rotation, floatArrayOf(1f, 1f, 1f))
        } else {
            restLocal[index]
        }
        val world = Mat4.multiply(parent, rest)
        if (light != null && node.mesh >= 0) model.meshes[node.mesh].forEach { primitive ->
            val lo = primitive.position.min ?: return@forEach
            val hi = primitive.position.max ?: return@forEach
            val bounds = result.getOrPut(light) {
                floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE) to
                    floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            }
            for (corner in 0 until 8) {
                val p = Mat4.transformPoint(
                    world,
                    if (corner and 1 == 0) lo[0] else hi[0],
                    if (corner and 2 == 0) lo[1] else hi[1],
                    if (corner and 4 == 0) lo[2] else hi[2],
                )
                for (axis in 0 until 3) {
                    bounds.first[axis] = minOf(bounds.first[axis], p[axis])
                    bounds.second[axis] = maxOf(bounds.second[axis], p[axis])
                }
            }
        }
        node.children.forEach { stack.addLast(Triple(it, world, light)) }
    }
    return result
}

/** Beam origins from the GLB lamp nodes, with body-proportional fallbacks if a node is missing. */
internal fun measureLamps(
    model: Vehicle3dGlbModel,
    lightNodes: Map<Int, String>,
    restLocal: List<FloatArray>,
    center: FloatArray,
    halfExtents: FloatArray,
    groundY: Float,
): LampAnchors {
    val bounds = lightBounds(model, lightNodes, restLocal)
    val frontZ = center[2] - halfExtents[2]
    fun pair(name: String, fallbackHalfX: Float, fallbackHeight: Float): List<FloatArray> {
        val b = bounds[name]
        if (b == null) {
            val y = groundY + fallbackHeight
            val z = frontZ + 0.12f
            return listOf(
                floatArrayOf(center[0] - fallbackHalfX, y, z),
                floatArrayOf(center[0] + fallbackHalfX, y, z),
            )
        }
        val (lo, hi) = b
        val y = (lo[1] + hi[1]) / 2f
        val z = lo[2] + 0.04f
        // One node normally holds both lamps; a single lamp is mirrored about the centre line.
        val halfX = if (hi[0] - lo[0] > 0.5f) {
            (hi[0] - lo[0]) / 2f - LAMP_INSET
        } else {
            abs((lo[0] + hi[0]) / 2f - center[0])
        }
        return listOf(
            floatArrayOf(center[0] - halfX, y, z),
            floatArrayOf(center[0] + halfX, y, z),
        )
    }
    return LampAnchors(
        headlamps = pair(Vehicle3dRig.LIGHT_DIPPED, halfExtents[0] * 0.7f, 0.68f),
        fogLamps = pair(Vehicle3dRig.LIGHT_FRONT_FOG, halfExtents[0] * 0.62f, 0.34f),
        frontZ = frontZ,
        rearZ = center[2] + halfExtents[2],
        thirdBrake = bounds[Vehicle3dRig.LIGHT_THIRD_BRAKE]?.let { (lo, hi) ->
            floatArrayOf((lo[0] + hi[0]) / 2f, (lo[1] + hi[1]) / 2f, hi[2])
        } ?: floatArrayOf(center[0], center[1] + halfExtents[1] * 0.85f, center[2] + halfExtents[2] * 0.9f),
    )
}

/** Light cone from [apex] towards -Z: interleaved x, y, z and the 0..1 distance along the beam. */
internal fun beamCone(apex: FloatArray, spec: BeamSpec): FloatBuffer {
    val tilt = Math.toRadians(spec.tiltDegrees.toDouble())
    val dirY = -sin(tilt).toFloat()
    val dirZ = -cos(tilt).toFloat()
    val upY = cos(tilt).toFloat()
    val upZ = -sin(tilt).toFloat()
    val baseX = apex[0]
    val baseY = apex[1] + dirY * spec.length
    val baseZ = apex[2] + dirZ * spec.length
    val halfW = spec.length * tan(Math.toRadians(spec.halfWidthDegrees.toDouble())).toFloat()
    val halfH = spec.length * tan(Math.toRadians(spec.halfHeightDegrees.toDouble())).toFloat()
    val values = FloatArray(BEAM_SEGMENTS * 3 * 4)
    var cursor = 0
    fun put(x: Float, y: Float, z: Float, along: Float) {
        values[cursor++] = x; values[cursor++] = y; values[cursor++] = z; values[cursor++] = along
    }
    fun ring(i: Int, along: Float) {
        val angle = 2.0 * Math.PI * i / BEAM_SEGMENTS
        val across = cos(angle).toFloat() * halfW
        val lift = sin(angle).toFloat() * halfH
        put(baseX + across, baseY + upY * lift, baseZ + upZ * lift, along)
    }
    for (i in 0 until BEAM_SEGMENTS) {
        put(apex[0], apex[1], apex[2], 0f)
        ring(i, 1f)
        ring(i + 1, 1f)
    }
    return floatBuffer(values)
}

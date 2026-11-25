package com.ts10.inspector.vehicle3d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class Vehicle3dSceneTest {
    private val model by lazy {
        Vehicle3dGlbModel.parse(File("src/main/assets/${Vehicle3dOffscreenEngine.MODEL_ASSET}").readBytes())
    }

    @Test
    fun `launcher GLB exposes every animated and light node`() {
        val names = model.nodes.map { it.name }.toSet()
        val required = listOf(
            Vehicle3dRig.DOOR_LEFT, Vehicle3dRig.DOOR_RIGHT, Vehicle3dRig.HOOD, Vehicle3dRig.TAILGATE,
            Vehicle3dRig.WINDOW_LEFT, Vehicle3dRig.WINDOW_RIGHT, Vehicle3dRig.STEERING_WHEEL,
        ) + Vehicle3dRig.STEER_PIVOTS + Vehicle3dRig.SPIN_PIVOTS + Vehicle3dRig.ALL_LIGHTS
        assertEquals(emptyList<String>(), required.filterNot(names::contains))
        assertTrue(model.materials.any { it.name == Vehicle3dRig.PAINT_MATERIAL })
    }

    @Test
    fun `light nodes ship switched off and every light has a lit mesh`() {
        Vehicle3dRig.ALL_LIGHTS.forEach { name ->
            val node = model.nodes[model.nodeIndex(name)]
            assertArrayEquals(name, floatArrayOf(0f, 0f, 0f), node.scale, 0f)
            assertTrue(name, node.children.isNotEmpty())
        }
    }

    @Test
    fun `primitives stay inside the binary chunk and use 16-bit indices`() {
        val size = model.binary.capacity()
        model.meshes.flatten().forEach { primitive ->
            assertTrue(primitive.position.offset + primitive.position.count * 12 <= size)
            assertTrue(primitive.normal.offset + primitive.normal.count * 12 <= size)
            assertTrue(primitive.indices.offset % 2 == 0)
            assertTrue(primitive.indices.offset + primitive.indices.count * 2 <= size)
            assertTrue(primitive.position.count <= 65_536)
        }
    }

    @Test
    fun `honeycomb texture is power of two for GLES 2 repeat`() {
        model.images.forEach { png ->
            val width = readPngInt(png, 16)
            val height = readPngInt(png, 20)
            assertTrue(width and (width - 1) == 0 && height and (height - 1) == 0)
        }
    }

    @Test
    fun `doors open fully, settle and close again`() {
        val animator = Vehicle3dSceneAnimator()
        val open = Vehicle3dSceneInput(doorLeftOpen = true)
        repeat(60) { animator.step(open, 0.05f) }
        assertEquals(1f, animator.doorLeft, 0f)
        assertFalse(animator.step(open, 0.05f))
        repeat(60) { animator.step(Vehicle3dSceneInput(), 0.05f) }
        assertEquals(0f, animator.doorLeft, 0f)
        assertArrayEquals(Mat4.identity(), animator.motions().getValue(Vehicle3dRig.DOOR_LEFT), 1e-6f)
    }

    @Test
    fun `moving vehicle keeps animating and spins the wheels forwards`() {
        val animator = Vehicle3dSceneAnimator()
        val driving = Vehicle3dSceneInput(speedKmh = 36f)
        assertTrue(animator.step(driving, 0.05f))
        // 10 m/s for 0.05 s on a 0.315 m wheel is about 1.59 rad.
        assertEquals(10f * 0.05f / Vehicle3dRig.WHEEL_RADIUS_M, animator.wheelSpinRadians, 1e-4f)
        assertEquals(vehicle3dCameraTarget(driving), animator.orbit)
    }

    @Test
    fun `driving camera is centred on a straight road and follows OSM bends`() {
        val straight = vehicle3dCameraTarget(Vehicle3dSceneInput(speedKmh = 50f))
        assertEquals(0f, straight.yawDegrees, 0f)
        assertEquals(0f, straight.lateralMeters, 0f)
        val right = vehicle3dCameraTarget(Vehicle3dSceneInput(speedKmh = 50f, roadCurve = 0.2f))
        assertTrue(right.yawDegrees < 0f && right.lateralMeters > 0f)
        val left = vehicle3dCameraTarget(Vehicle3dSceneInput(speedKmh = 50f, roadCurve = -0.2f))
        assertTrue(left.yawDegrees > 0f && left.lateralMeters < 0f)
    }

    @Test
    fun `driving fades the showroom floor into the road and parking brings it back`() {
        val animator = Vehicle3dSceneAnimator()
        animator.step(Vehicle3dSceneInput(), 0.05f)
        assertEquals(1f, animator.showroom, 0f)
        repeat(40) { animator.step(Vehicle3dSceneInput(speedKmh = 40f), 0.05f) }
        assertEquals(0f, animator.showroom, 0f)
        assertTrue(animator.travelMeters > 0f)
        repeat(40) { animator.step(Vehicle3dSceneInput(), 0.05f) }
        assertEquals(1f, animator.showroom, 0f)
        assertFalse(vehicle3dRoadMode(Vehicle3dSceneInput(speedKmh = 40f, topView = true)))
    }

    @Test
    fun `stopping glides the camera from the rear driving view to the parked three-quarter view`() {
        val animator = Vehicle3dSceneAnimator()
        repeat(20) { animator.step(Vehicle3dSceneInput(speedKmh = 50f), 1f / 30f) }
        val parked = vehicle3dCameraTarget(Vehicle3dSceneInput())
        var previous = animator.orbit
        var frames = 0
        while (animator.step(Vehicle3dSceneInput(), 1f / 30f)) {
            val delta = abs(((animator.orbit.yawDegrees - previous.yawDegrees + 540f) % 360f) - 180f)
            assertTrue("salto de ${delta}° en un fotograma", delta < 20f)
            previous = animator.orbit
            frames++
        }
        assertTrue("transición demasiado corta: $frames fotogramas", frames > 20)
        assertEquals(parked.yawDegrees, (animator.orbit.yawDegrees + 360f) % 360f, 0.5f)
        assertEquals(parked.pitchDegrees, animator.orbit.pitchDegrees, 0.2f)
    }

    @Test
    fun `tyre page glides to an aerial view with the nose up and the left side on the left`() {
        val animator = Vehicle3dSceneAnimator()
        animator.step(Vehicle3dSceneInput(), 0.05f)
        val top = Vehicle3dSceneInput(topView = true)
        var frames = 0
        while (animator.step(top, 1f / 30f)) frames++
        assertTrue(frames > 20)
        assertEquals(89.5f, animator.orbit.pitchDegrees, 0.2f)

        val center = floatArrayOf(0f, 0.7f, 0f)
        val eye = Mat4.orbitEye(center, 2.4f, animator.orbit, 30f)
        val view = Mat4.lookAt(eye, center)
        val front = Mat4.transformPoint(view, 0f, 0.7f, -2f)
        val left = Mat4.transformPoint(view, -0.9f, 0.7f, 0f)
        assertTrue("el morro debe quedar arriba", front[1] > 1.9f)
        assertTrue("el lado izquierdo debe quedar a la izquierda", left[0] < -0.8f)
        // Driving does not pull the camera away from the tyre page.
        assertEquals(vehicle3dCameraTarget(top), vehicle3dCameraTarget(top.copy(speedKmh = 80f)))
    }

    @Test
    fun `interior upholstery materials are dark, not glTF default white`() {
        listOf("ASTRA_TAPICERIA_OPC_V40", "ASTRA_REJILLA_ALTAVOZ_PUERTA_NEGRA_V20").forEach { name ->
            val material = model.materials.first { it.name == name }
            assertTrue(name, material.baseColor.take(3).all { it < 0.1f })
        }
    }

    @Test
    fun `steering wheel angle maps to clamped road wheel angle`() {
        val animator = Vehicle3dSceneAnimator()
        repeat(100) { animator.step(Vehicle3dSceneInput(steeringWheelDegrees = 540f), 0.05f) }
        assertEquals(Vehicle3dRig.MAX_ROAD_WHEEL_DEGREES, animator.roadWheelDegrees, 0.01f)
    }

    @Test
    fun `lights follow confirmed signals and indicators blink`() {
        val input = Vehicle3dSceneInput(dippedBeam = true, brake = true, leftIndicator = true)
        val on = vehicle3dLitNodes(input, indicatorLampOn = true)
        val off = vehicle3dLitNodes(input, indicatorLampOn = false)
        assertTrue(Vehicle3dRig.LIGHT_POSITION in on && Vehicle3dRig.LIGHT_DIPPED in on)
        assertTrue(Vehicle3dRig.LIGHT_BRAKE in on && Vehicle3dRig.LIGHT_THIRD_BRAKE in on)
        assertTrue(Vehicle3dRig.LIGHT_LEFT in on)
        assertFalse(Vehicle3dRig.LIGHT_LEFT in off)
        assertFalse(Vehicle3dRig.LIGHT_HIGH in on)
        assertEquals(emptySet<String>(), vehicle3dLitNodes(Vehicle3dSceneInput(), true))
    }

    @Test
    fun `quaternion rotation matches axis rotation`() {
        val half = Math.toRadians(45.0)
        val fromQuaternion = Mat4.fromTrs(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, Math.sin(half).toFloat(), 0f, Math.cos(half).toFloat()),
            floatArrayOf(1f, 1f, 1f),
        )
        val rotated = Mat4.transformPoint(fromQuaternion, 0f, 0f, -1f)
        // +90 degrees about Y turns the car's front (-Z) towards its left (-X).
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f), rotated, 1e-5f)
        assertArrayEquals(fromQuaternion, Mat4.rotation(90f, 0f, 1f, 0f), 1e-6f)
    }

    @Test
    fun `road objects glide to detector updates and fade out when lost`() {
        val animator = Vehicle3dSceneAnimator()
        val car = Vehicle3dRoadObject(7, Vehicle3dRoadObjectKind.CAR, 0f, 10f, egoLane = true)
        animator.step(Vehicle3dSceneInput(roadObjects = listOf(car)), 0.1f)
        assertEquals(10f, animator.roadObjects.single().aheadMeters, 1e-4f)
        assertTrue(animator.roadObjects.single().presence < 1f)

        assertTrue(animator.step(Vehicle3dSceneInput(roadObjects = listOf(car.copy(aheadMeters = 20f))), 0.1f))
        val gliding = animator.roadObjects.single().aheadMeters
        assertTrue(gliding > 10f && gliding < 20f)

        repeat(10) { animator.step(Vehicle3dSceneInput(), 0.1f) }
        assertTrue(animator.roadObjects.isEmpty())
    }

    @Test
    fun `unit cube has 36 vertices inside half a metre with unit normals`() {
        val cube = unitCubeVertices()
        assertEquals(36 * 6, cube.size)
        for (vertex in 0 until 36) {
            for (axis in 0 until 3) assertEquals(0.5f, abs(cube[vertex * 6 + axis]), 1e-6f)
            val n = (3 until 6).sumOf { (cube[vertex * 6 + it] * cube[vertex * 6 + it]).toDouble() }
            assertEquals(1.0, n, 1e-6)
        }
    }

    private fun readPngInt(png: ByteArray, offset: Int): Int =
        (png[offset].toInt() and 0xff shl 24) or (png[offset + 1].toInt() and 0xff shl 16) or
            (png[offset + 2].toInt() and 0xff shl 8) or (png[offset + 3].toInt() and 0xff)
}

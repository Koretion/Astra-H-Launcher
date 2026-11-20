package com.ts10.inspector.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LocalVisionObjectDetectorTest {
    @Test
    fun `decodes normalized YOLO coordinates outside letterbox padding`() {
        val spec = TensorSpec(
            shape = intArrayOf(1, 6, 7),
            dataType = DataType.FLOAT32,
            scale = 0f,
            zeroPoint = 0,
        )
        val values = FloatArray(spec.elements)
        values[0] = 0.5f // center x
        values[7] = 0.5f // center y
        values[14] = 0.25f // width
        values[21] = 0.25f // height
        values[28] = 0.9f // person confidence
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        buffer.rewind()

        val detections = decodeYoloOutput(
            buffer = buffer,
            spec = spec,
            transform = LetterboxTransform(1280, 720, 320, 320, 0.25f, 0f, 70f),
            scoreThreshold = 0.35f,
            iouThreshold = 0.5f,
        )

        assertEquals(1, detections.size)
        assertEquals(RoadObjectKind.PERSON, detections.single().kind)
        assertEquals(0.375f, detections.single().box.left, 0.0001f)
        assertEquals(0.625f, detections.single().box.right, 0.0001f)
    }

    @Test
    fun `continues decoding YOLO exports that use pixel coordinates`() {
        val spec = TensorSpec(
            shape = intArrayOf(1, 6, 7),
            dataType = DataType.FLOAT32,
            scale = 0f,
            zeroPoint = 0,
        )
        val values = FloatArray(spec.elements)
        values[0] = 160f
        values[7] = 160f
        values[14] = 80f
        values[21] = 80f
        values[28] = 0.9f
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        buffer.rewind()

        val detections = decodeYoloOutput(
            buffer = buffer,
            spec = spec,
            transform = LetterboxTransform(1280, 720, 320, 320, 0.25f, 0f, 70f),
            scoreThreshold = 0.35f,
            iouThreshold = 0.5f,
        )

        assertEquals(1, detections.size)
        assertEquals(0.375f, detections.single().box.left, 0.0001f)
        assertEquals(0.625f, detections.single().box.right, 0.0001f)
    }

    @Test
    fun `maps relevant COCO road labels without accepting unrelated objects`() {
        assertEquals(RoadObjectKind.CAR, roadObjectKind("car"))
        assertEquals(RoadObjectKind.PERSON, roadObjectKind(" PERSON "))
        assertEquals(RoadObjectKind.TRUCK, roadObjectKind("Truck"))
        assertEquals(RoadObjectKind.TRAFFIC_LIGHT, roadObjectKind("traffic light"))
        assertEquals(RoadObjectKind.STOP_SIGN, roadObjectKind("stop sign"))
        assertNull(roadObjectKind("dog"))
    }

    @Test
    fun `decodes traffic lights and stop signs from COCO output`() {
        val spec = TensorSpec(
            shape = intArrayOf(1, 16, 17),
            dataType = DataType.FLOAT32,
            scale = 0f,
            zeroPoint = 0,
        )
        val values = FloatArray(spec.elements)
        fun value(feature: Int, candidate: Int, value: Float) {
            values[feature * 17 + candidate] = value
        }
        value(0, 0, 0.30f)
        value(1, 0, 0.40f)
        value(2, 0, 0.10f)
        value(3, 0, 0.20f)
        value(13, 0, 0.82f) // 4 + COCO traffic light class 9
        value(0, 1, 0.70f)
        value(1, 1, 0.40f)
        value(2, 1, 0.10f)
        value(3, 1, 0.20f)
        value(15, 1, 0.76f) // 4 + COCO stop sign class 11
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        buffer.rewind()

        val detections = decodeYoloOutput(
            buffer = buffer,
            spec = spec,
            transform = LetterboxTransform(1280, 720, 320, 320, 0.25f, 0f, 70f),
            scoreThreshold = 0.35f,
            iouThreshold = 0.5f,
        )

        assertEquals(
            setOf(RoadObjectKind.TRAFFIC_LIGHT, RoadObjectKind.STOP_SIGN),
            detections.mapTo(mutableSetOf()) { it.kind },
        )
    }

    @Test
    fun `normalizes and clips detector coordinates`() {
        assertEquals(
            NormalizedDetectionBox(0f, 0.25f, 1f, 1f),
            normalizedDetectionBox(-10f, 90f, 700f, 400f, 640, 360),
        )
    }

    @Test
    fun `rejects empty boxes and invalid image dimensions`() {
        assertNull(normalizedDetectionBox(10f, 10f, 10f, 20f, 640, 360))
        assertNull(normalizedDetectionBox(10f, 10f, 20f, 20f, 0, 360))
    }

    @Test
    fun `adaptive interval keeps inference below the background cpu budget`() {
        assertEquals(650L, adaptiveDetectorIntervalMs(100L))
        assertEquals(850L, adaptiveDetectorIntervalMs(300L))
        assertEquals(2_500L, adaptiveDetectorIntervalMs(1_200L))
    }

    @Test
    fun `p95 latency exposes slow samples without being dominated by one maximum`() {
        val samples = LongArray(20) { index -> if (index == 19) 2_000L else 100L + index }

        assertEquals(118L, percentile95LatencyMs(samples))
        assertEquals(0L, percentile95LatencyMs(longArrayOf()))
    }

    @Test
    fun `nms removes overlapping boxes of the same class but retains other road classes`() {
        val strongestCar = YoloCandidate(
            RoadObjectKind.CAR,
            0.9f,
            NormalizedDetectionBox(0.1f, 0.1f, 0.5f, 0.5f),
        )
        val duplicateCar = YoloCandidate(
            RoadObjectKind.CAR,
            0.7f,
            NormalizedDetectionBox(0.12f, 0.12f, 0.52f, 0.52f),
        )
        val overlappingPerson = YoloCandidate(
            RoadObjectKind.PERSON,
            0.8f,
            NormalizedDetectionBox(0.12f, 0.12f, 0.52f, 0.52f),
        )

        val result = nonMaximumSuppression(
            listOf(duplicateCar, overlappingPerson, strongestCar),
            iouThreshold = 0.5f,
            limit = 12,
        )

        assertEquals(2, result.size)
        assertSame(strongestCar, result[0])
        assertSame(overlappingPerson, result[1])
    }

    @Test
    fun `iou is zero for separate boxes and one for identical boxes`() {
        val box = NormalizedDetectionBox(0.1f, 0.2f, 0.5f, 0.8f)
        val separate = NormalizedDetectionBox(0.6f, 0.2f, 0.9f, 0.8f)

        assertEquals(1f, intersectionOverUnion(box, box), 0.0001f)
        assertEquals(0f, intersectionOverUnion(box, separate), 0.0001f)
    }
}

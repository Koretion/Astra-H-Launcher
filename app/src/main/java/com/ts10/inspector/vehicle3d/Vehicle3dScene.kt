package com.ts10.inspector.vehicle3d

import com.ts10.inspector.environment.RoadLineStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything the 3D vehicle shows. Built from confirmed launcher/CAN state only. */
internal data class Vehicle3dSceneInput(
    /** sRGB paint colour (ARGB) chosen by the user. */
    val paintArgb: Int = 0xFFE7E9EC.toInt(),
    /** Color del suelo del showroom elegido por el usuario; null usa el suelo de estudio. */
    val floorArgb: Int? = null,
    val dark: Boolean = true,
    val speedKmh: Float = 0f,
    /** Vista de marcha; se mantiene unos segundos tras detenerse, ver [ParkedViewDebouncer]. */
    val drivingView: Boolean = speedKmh >= VEHICLE_3D_DRIVING_KMH,
    /** Steering-wheel angle, positive to the left (counter-clockwise). */
    val steeringWheelDegrees: Float = 0f,
    val doorLeftOpen: Boolean = false,
    val doorRightOpen: Boolean = false,
    val hoodOpen: Boolean = false,
    val tailgateOpen: Boolean = false,
    val windowLeftDown: Boolean = false,
    val windowRightDown: Boolean = false,
    val positionLights: Boolean = false,
    val dippedBeam: Boolean = false,
    val highBeam: Boolean = false,
    val brake: Boolean = false,
    val reverse: Boolean = false,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val frontFog: Boolean = false,
    val rearFog: Boolean = false,
    /** Aerial view (nose up) shown with the tyre pressures. */
    val topView: Boolean = false,
    /** Close-up requested by an open settings section; only honoured while parked. */
    val focus: Vehicle3dFocus = Vehicle3dFocus.NONE,
    /** Driving zoom as in the 2D scene: 2 is closer, 0.5 farther. Not applied while parked. */
    val drivingZoom: Float = 1f,
    /** OSM lanes in the travel direction; 0 draws a plain road without lane data. */
    val roadLanes: Int = 0,
    /** Lane the car occupies, counted from the right as in the 2D scene. */
    val activeLaneFromRight: Int = 0,
    /** Same signed bend as the 2D road (positive bends right), see [resolveRenderedRoadCurve]. */
    val roadCurve: Float = 0f,
    val roadOneway: Boolean = true,
    val roadCenterLine: RoadLineStyle = RoadLineStyle.SOLID,
    val roadLaneLines: List<RoadLineStyle> = emptyList(),
    /** Confirmed camera detections placed on the road, nearest first. */
    val roadObjects: List<Vehicle3dRoadObject> = emptyList(),
)

internal enum class Vehicle3dRoadObjectKind { CAR, LARGE_VEHICLE, TWO_WHEELER, PERSON }

/** A tracked road user relative to the car: lanes to the side and metres ahead of the bumper. */
internal data class Vehicle3dRoadObject(
    val id: Long,
    val kind: Vehicle3dRoadObjectKind,
    /** Lane widths from the centre of the car's lane, positive to the right. */
    val lateralLanes: Float,
    /** Metres from the front bumper to the nearest face of the object. */
    val aheadMeters: Float,
    val egoLane: Boolean,
)

/** Road object as drawn: smoothed between detector updates and faded in and out. */
internal data class Vehicle3dAnimatedRoadObject(
    val id: Long,
    val kind: Vehicle3dRoadObjectKind,
    val lateralLanes: Float,
    val aheadMeters: Float,
    val egoLane: Boolean,
    /** 0 invisible, 1 fully shown. */
    val presence: Float,
)

/** Node names and motion limits of the V45 GLB, measured from its exported Blender actions. */
internal object Vehicle3dRig {
    const val DOOR_LEFT = "PIVOTE_PUERTA_LEFT_4C"
    const val DOOR_RIGHT = "PIVOTE_PUERTA_RIGHT_4C"
    const val HOOD = "PIVOTE_CAPO_FASE4B"
    const val TAILGATE = "PIVOTE_PROVISIONAL_PORTON_3GC"
    const val WINDOW_LEFT = "ASTRA_CRISTAL_DELANTERO_LEFT_4C"
    const val WINDOW_RIGHT = "ASTRA_CRISTAL_DELANTERO_RIGHT_4C"
    const val STEERING_WHEEL = "PIVOTE_VOLANTE_DIRECCION"
    const val PAINT_MATERIAL = "ASTRA_PINTURA_CARROCERIA"
    val STEER_PIVOTS = listOf("PIVOTE_DIRECCION_DELANTERA_IZQUIERDA", "PIVOTE_DIRECCION_DELANTERA_DERECHA")
    val SPIN_PIVOTS = listOf(
        "PIVOTE_GIRO_DELANTERA_IZQUIERDA", "PIVOTE_GIRO_DELANTERA_DERECHA",
        "PIVOTE_GIRO_TRASERA_IZQUIERDA", "PIVOTE_GIRO_TRASERA_DERECHA",
    )

    // Local glTF axes (front of the car is -Z, left is -X, up is +Y).
    const val DOOR_DEGREES = 60f
    const val HOOD_DEGREES = 41f
    const val TAILGATE_DEGREES = 60f
    val WINDOW_LEFT_TRAVEL = floatArrayOf(-0.105f, -0.28f, 0f)
    val WINDOW_RIGHT_TRAVEL = floatArrayOf(0.105f, -0.28f, 0f)
    const val WHEEL_RADIUS_M = 0.315f
    /** Astra H: about 2.7 steering-wheel turns lock to lock for ±32° at the road wheels. */
    const val STEERING_RATIO = 15f
    const val MAX_ROAD_WHEEL_DEGREES = 32f

    const val LIGHT_POSITION = "LUZ_POSICION"
    const val LIGHT_DIPPED = "LUZ_CRUCE"
    const val LIGHT_HIGH = "LUZ_CARRETERA"
    const val LIGHT_BRAKE = "LUZ_FRENO"
    const val LIGHT_THIRD_BRAKE = "LUZ_TERCERA_LUZ_FRENO"
    const val LIGHT_REVERSE = "LUZ_MARCHA_ATRAS"
    const val LIGHT_LEFT = "LUZ_INTERMITENTE_IZQUIERDO"
    const val LIGHT_RIGHT = "LUZ_INTERMITENTE_DERECHO"
    const val LIGHT_FRONT_FOG = "LUZ_ANTINIEBLA_DELANTERA"
    const val LIGHT_REAR_FOG = "LUZ_ANTINIEBLA_TRASERA"
    val ALL_LIGHTS = listOf(
        LIGHT_POSITION, LIGHT_DIPPED, LIGHT_HIGH, LIGHT_BRAKE, LIGHT_THIRD_BRAKE, LIGHT_REVERSE,
        LIGHT_LEFT, LIGHT_RIGHT, LIGHT_FRONT_FOG, LIGHT_REAR_FOG,
    )
}

internal enum class Vehicle3dFocus { NONE, ENGINE, CABIN, FUEL }

/** Orbit camera around the vehicle centre: yaw 0 looks from behind, 180 from the front, 270 from the left. */
internal data class Vehicle3dOrbit(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val distanceFactor: Float,
    /** Screen shift of the car in metres, positive to the right; used to follow road bends. */
    val lateralMeters: Float = 0f,
    /** Look-at point as fractions of the car's half extents from its centre (front is -Z). */
    val targetX: Float = 0f,
    val targetY: Float = DEFAULT_TARGET_Y,
    val targetZ: Float = 0f,
    val fovDegrees: Float = DEFAULT_FOV_DEGREES,
)

/** The default view looks slightly below the body centre. */
internal const val DEFAULT_TARGET_Y = -0.25f
internal const val DEFAULT_FOV_DEGREES = 30f
/** Gran angular del interior: ver el salpicadero entero desde la altura de la cabeza. */
internal const val CABIN_FOV_DEGREES = 74f

internal fun vehicle3dCameraTarget(input: Vehicle3dSceneInput): Vehicle3dOrbit = when {
    // 89.5° rather than 90°: lookAt with a vertical view direction has no defined screen axis.
    input.topView -> Vehicle3dOrbit(0f, 89.5f, 1.05f)
    // Straight behind the car; on a bend it turns and slides towards the virtual OSM road.
    vehicle3dRoadMode(input) -> Vehicle3dOrbit(
        // 0f - x, not -x: a straight road must give +0°, equal to the other straight-behind orbits.
        yawDegrees = 0f - input.roadCurve * DRIVING_CURVE_YAW_DEGREES,
        pitchDegrees = 20f,
        distanceFactor = DRIVING_DISTANCE / input.drivingZoom,
        lateralMeters = input.roadCurve * DRIVING_CURVE_SHIFT_METERS,
    )
    input.drivingView -> Vehicle3dOrbit(0f, 20f, DRIVING_DISTANCE / input.drivingZoom)
    input.reverse -> Vehicle3dOrbit(0f, 20f, DRIVING_DISTANCE)
    // Engine bay from the front and above, with the bonnet opened by the caller.
    input.focus == Vehicle3dFocus.ENGINE -> Vehicle3dOrbit(180f, 40f, 0.42f, targetY = 0.1f, targetZ = -0.62f)
    // Inside, between the front seats at head height, looking at the dashboard and the radio.
    // Rear three-quarter from the right, where the fuel filler is.
    input.focus == Vehicle3dFocus.FUEL -> Vehicle3dOrbit(55f, 16f, SHOWROOM_DISTANCE)
    input.focus == Vehicle3dFocus.CABIN -> Vehicle3dOrbit(
        yawDegrees = 0f,
        pitchDegrees = 14f,
        distanceFactor = 0.24f,
        targetY = 0.02f,
        targetZ = -0.36f,
        fovDegrees = CABIN_FOV_DEGREES,
    )
    // De frente y más alta: algo más lejos para que el paragolpes no toque el borde inferior.
    input.hoodOpen -> Vehicle3dOrbit(200f, 22f, SHOWROOM_DISTANCE * 1.1f)
    input.tailgateOpen -> Vehicle3dOrbit(28f, 18f, SHOWROOM_DISTANCE)
    input.doorLeftOpen -> Vehicle3dOrbit(250f, 18f, SHOWROOM_DISTANCE)
    input.doorRightOpen -> Vehicle3dOrbit(110f, 18f, SHOWROOM_DISTANCE)
    else -> Vehicle3dOrbit(215f, 14f, SHOWROOM_DISTANCE)
}

/**
 * La cámara encaja la esfera del coche en la altura de la imagen, que tiene la proporción ancha
 * del carrusel: a distancia 1 el coche quedaba pequeño en el centro. Las vistas exteriores se
 * acercan para que llene el hueco; el interior, el motor y la vista cenital ya están encuadrados.
 */
private const val SHOWROOM_DISTANCE = 0.66f

private const val DRIVING_CURVE_YAW_DEGREES = 28f
private const val DRIVING_DISTANCE = 0.95f
private const val DRIVING_CURVE_SHIFT_METERS = 2.2f

/** From this speed the camera sits behind the car and the showroom floor becomes the road. */
internal const val VEHICLE_3D_DRIVING_KMH = 3f

/** The road replaces the showroom floor only while driving forwards with the rear camera. */
internal fun vehicle3dRoadMode(input: Vehicle3dSceneInput): Boolean =
    !input.topView && input.drivingView

/** Time-based state that turns [Vehicle3dSceneInput] into node motions. Not thread-safe. */
internal class Vehicle3dSceneAnimator {
    var doorLeft = 0f; private set
    var doorRight = 0f; private set
    var hood = 0f; private set
    var tailgate = 0f; private set
    var windowLeft = 0f; private set
    var windowRight = 0f; private set
    var wheelSpinRadians = 0f; private set
    var roadWheelDegrees = 0f; private set
    var steeringWheelDegrees = 0f; private set
    /** 1 = showroom floor and reflection, 0 = road. */
    var showroom = 1f; private set
    /** Distance driven, wrapped, so the dashed lane lines move with the car. */
    var travelMeters = 0f; private set
    var orbit = vehicle3dCameraTarget(Vehicle3dSceneInput()); private set
    var roadObjects: List<Vehicle3dAnimatedRoadObject> = emptyList(); private set
    /** Carril activo animado (fraccionario) para que la carretera se deslice en un cambio de carril. */
    var laneIndex = 0f; private set
    private var laneFrom = 0f
    private var laneTo = 0f
    private var laneProgress = 1f
    private var initialized = false

    /** Advances the animation; returns true while another frame is needed. */
    fun step(input: Vehicle3dSceneInput, dtSeconds: Float): Boolean {
        val dt = dtSeconds.coerceIn(0f, 0.25f)
        val target = vehicle3dCameraTarget(input)
        if (!initialized) {
            orbit = target
            // Start with the wheel where it is, not swinging over from the centre.
            steeringWheelDegrees = input.steeringWheelDegrees.coerceIn(-540f, 540f)
            showroom = if (vehicle3dRoadMode(input)) 0f else 1f
            laneIndex = activeLaneTarget(input)
            laneTo = laneIndex
            initialized = true
        }
        val laneTarget = activeLaneTarget(input)
        if (laneTarget != laneTo) {
            laneFrom = laneIndex
            laneTo = laneTarget
            laneProgress = 0f
        }
        if (laneProgress < 1f) {
            laneProgress = min(1f, laneProgress + dt / (LANE_CHANGE_SECONDS * max(1f, abs(laneTo - laneFrom))))
            laneIndex = laneFrom + (laneTo - laneFrom) * ease(laneProgress)
        }
        val part = dt / PART_SECONDS
        val window = dt / WINDOW_SECONDS
        doorLeft = approach(doorLeft, input.doorLeftOpen.toProgress(), part)
        doorRight = approach(doorRight, input.doorRightOpen.toProgress(), part)
        hood = approach(hood, input.hoodOpen.toProgress(), part)
        tailgate = approach(tailgate, input.tailgateOpen.toProgress(), part)
        windowLeft = approach(windowLeft, input.windowLeftDown.toProgress(), window)
        windowRight = approach(windowRight, input.windowRightDown.toProgress(), window)

        val metresPerSecond = input.speedKmh / 3.6f * if (input.reverse) -1f else 1f
        wheelSpinRadians = ((wheelSpinRadians + metresPerSecond / Vehicle3dRig.WHEEL_RADIUS_M * dt) % TWO_PI)
        travelMeters = (travelMeters + metresPerSecond * dt) % TRAVEL_WRAP_METERS
        val showroomTarget = if (vehicle3dRoadMode(input)) 0f else 1f
        showroom = approach(showroom, showroomTarget, dt / CAMERA_SECONDS)
        val wheelTarget = input.steeringWheelDegrees.coerceIn(-540f, 540f)
        steeringWheelDegrees += (wheelTarget - steeringWheelDegrees) * min(1f, dt * STEERING_RESPONSE)
        roadWheelDegrees = (steeringWheelDegrees / Vehicle3dRig.STEERING_RATIO)
            .coerceIn(-Vehicle3dRig.MAX_ROAD_WHEEL_DEGREES, Vehicle3dRig.MAX_ROAD_WHEEL_DEGREES)

        val blend = min(1f, dt / CAMERA_SECONDS * 3f)
        orbit = Vehicle3dOrbit(
            yawDegrees = orbit.yawDegrees + shortestDegrees(orbit.yawDegrees, target.yawDegrees) * blend,
            pitchDegrees = orbit.pitchDegrees + (target.pitchDegrees - orbit.pitchDegrees) * blend,
            distanceFactor = orbit.distanceFactor + (target.distanceFactor - orbit.distanceFactor) * blend,
            lateralMeters = orbit.lateralMeters + (target.lateralMeters - orbit.lateralMeters) * blend,
            targetX = orbit.targetX + (target.targetX - orbit.targetX) * blend,
            targetY = orbit.targetY + (target.targetY - orbit.targetY) * blend,
            targetZ = orbit.targetZ + (target.targetZ - orbit.targetZ) * blend,
            fovDegrees = orbit.fovDegrees + (target.fovDegrees - orbit.fovDegrees) * blend,
        )
        val cameraMoving = abs(shortestDegrees(orbit.yawDegrees, target.yawDegrees)) > 0.2f ||
            abs(orbit.lateralMeters - target.lateralMeters) > 0.01f ||
            abs(orbit.targetX - target.targetX) > 0.002f ||
            abs(orbit.targetY - target.targetY) > 0.002f ||
            abs(orbit.targetZ - target.targetZ) > 0.002f ||
            abs(orbit.fovDegrees - target.fovDegrees) > 0.1f ||
            abs(orbit.pitchDegrees - target.pitchDegrees) > 0.1f ||
            abs(orbit.distanceFactor - target.distanceFactor) > 0.002f
        val partsMoving = doorLeft != input.doorLeftOpen.toProgress() || doorRight != input.doorRightOpen.toProgress() ||
            hood != input.hoodOpen.toProgress() || tailgate != input.tailgateOpen.toProgress() ||
            windowLeft != input.windowLeftDown.toProgress() || windowRight != input.windowRightDown.toProgress() ||
            showroom != showroomTarget || laneProgress < 1f
        val objectsMoving = stepRoadObjects(input.roadObjects, dt)
        return cameraMoving || partsMoving || objectsMoving || abs(metresPerSecond) > 0.05f ||
            abs(wheelTarget - steeringWheelDegrees) > 0.5f || input.leftIndicator || input.rightIndicator
    }

    /** Local motion (post-multiplied onto the rest transform) for every animated node name. */
    fun motions(): Map<String, FloatArray> = buildMap {
        put(Vehicle3dRig.DOOR_LEFT, Mat4.rotation(-Vehicle3dRig.DOOR_DEGREES * ease(doorLeft), 0f, 1f, 0f))
        put(Vehicle3dRig.DOOR_RIGHT, Mat4.rotation(Vehicle3dRig.DOOR_DEGREES * ease(doorRight), 0f, 1f, 0f))
        put(Vehicle3dRig.HOOD, Mat4.rotation(Vehicle3dRig.HOOD_DEGREES * ease(hood), 1f, 0f, 0f))
        put(Vehicle3dRig.TAILGATE, Mat4.rotation(-Vehicle3dRig.TAILGATE_DEGREES * ease(tailgate), 1f, 0f, 0f))
        put(Vehicle3dRig.WINDOW_LEFT, Mat4.translation(Vehicle3dRig.WINDOW_LEFT_TRAVEL, ease(windowLeft)))
        put(Vehicle3dRig.WINDOW_RIGHT, Mat4.translation(Vehicle3dRig.WINDOW_RIGHT_TRAVEL, ease(windowRight)))
        put(Vehicle3dRig.STEERING_WHEEL, Mat4.rotation(steeringWheelDegrees, 0f, 1f, 0f))
        Vehicle3dRig.STEER_PIVOTS.forEach { put(it, Mat4.rotation(roadWheelDegrees, 0f, 1f, 0f)) }
        // Forward travel (-Z) turns the top of the tyre forwards: negative rotation about +X.
        val spin = -Math.toDegrees(wheelSpinRadians.toDouble()).toFloat()
        Vehicle3dRig.SPIN_PIVOTS.forEach { put(it, Mat4.rotation(spin, 1f, 0f, 0f)) }
    }

    /**
     * The detector publishes every ~0.65 s: positions glide towards each update instead of jumping,
     * new tracks fade in where they appear and lost ones fade out where they were last seen.
     */
    private fun stepRoadObjects(targets: List<Vehicle3dRoadObject>, dt: Float): Boolean {
        val blend = min(1f, dt / OBJECT_SMOOTH_SECONDS)
        val fade = dt / OBJECT_FADE_SECONDS
        val previous = roadObjects.associateBy { it.id }
        var moving = false
        val next = ArrayList<Vehicle3dAnimatedRoadObject>(targets.size + previous.size)
        targets.forEach { target ->
            val current = previous[target.id]
            val lateral = current?.let { it.lateralLanes + (target.lateralLanes - it.lateralLanes) * blend }
                ?: target.lateralLanes
            val ahead = current?.let { it.aheadMeters + (target.aheadMeters - it.aheadMeters) * blend }
                ?: target.aheadMeters
            val presence = approach(current?.presence ?: 0f, 1f, fade)
            if (presence < 1f || abs(lateral - target.lateralLanes) > 0.01f || abs(ahead - target.aheadMeters) > 0.05f) {
                moving = true
            }
            next += Vehicle3dAnimatedRoadObject(target.id, target.kind, lateral, ahead, target.egoLane, presence)
        }
        val alive = targets.mapTo(HashSet()) { it.id }
        roadObjects.forEach { gone ->
            if (gone.id in alive) return@forEach
            val presence = approach(gone.presence, 0f, fade)
            if (presence > 0f) {
                moving = true
                next += gone.copy(presence = presence)
            }
        }
        roadObjects = next
        return moving
    }

    private fun Boolean.toProgress() = if (this) 1f else 0f

    private fun activeLaneTarget(input: Vehicle3dSceneInput): Float =
        input.activeLaneFromRight.coerceIn(0, max(1, input.roadLanes) - 1).toFloat()

    private companion object {
        const val PART_SECONDS = 0.9f
        /** Lo que tarda la carretera en deslizarse un carril, como un cambio de carril real. */
        const val LANE_CHANGE_SECONDS = 1.8f
        const val WINDOW_SECONDS = 3.5f
        const val CAMERA_SECONDS = 1.1f
        const val STEERING_RESPONSE = 8f
        const val OBJECT_SMOOTH_SECONDS = 0.35f
        const val OBJECT_FADE_SECONDS = 0.4f
        const val TWO_PI = (2 * PI).toFloat()
        /** Multiple of the 9 m dash period, so wrapping never makes the dashes jump. */
        const val TRAVEL_WRAP_METERS = 900f

        fun approach(value: Float, target: Float, delta: Float): Float =
            if (value < target) min(target, value + delta) else max(target, value - delta)

        fun ease(t: Float): Float = t * t * (3f - 2f * t)

        fun shortestDegrees(from: Float, to: Float): Float {
            var delta = (to - from) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return delta
        }
    }
}
/** Which light nodes are lit (scale 1). [indicatorLampOn] is the phase locked to the real flasher. */
internal fun vehicle3dLitNodes(input: Vehicle3dSceneInput, indicatorLampOn: Boolean): Set<String> = buildSet {
    val anyHeadlight = input.positionLights || input.dippedBeam || input.highBeam
    if (anyHeadlight) add(Vehicle3dRig.LIGHT_POSITION)
    if (input.dippedBeam || input.highBeam) add(Vehicle3dRig.LIGHT_DIPPED)
    if (input.highBeam) add(Vehicle3dRig.LIGHT_HIGH)
    if (input.brake) {
        add(Vehicle3dRig.LIGHT_BRAKE)
        add(Vehicle3dRig.LIGHT_THIRD_BRAKE)
    }
    if (input.reverse) add(Vehicle3dRig.LIGHT_REVERSE)
    if (input.frontFog) add(Vehicle3dRig.LIGHT_FRONT_FOG)
    if (input.rearFog) add(Vehicle3dRig.LIGHT_REAR_FOG)
    if (indicatorLampOn && input.leftIndicator) add(Vehicle3dRig.LIGHT_LEFT)
    if (indicatorLampOn && input.rightIndicator) add(Vehicle3dRig.LIGHT_RIGHT)
}

/** Column-major 4x4 matrices, as OpenGL expects. Pure Kotlin so the scene logic runs in JVM tests. */
internal object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (column in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
            out[column * 4 + row] = sum
        }
        return out
    }

    fun fromTrs(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
        val (x, y, z, w) = q
        val out = FloatArray(16)
        out[0] = (1 - 2 * (y * y + z * z)) * s[0]; out[1] = (2 * (x * y + z * w)) * s[0]; out[2] = (2 * (x * z - y * w)) * s[0]
        out[4] = (2 * (x * y - z * w)) * s[1]; out[5] = (1 - 2 * (x * x + z * z)) * s[1]; out[6] = (2 * (y * z + x * w)) * s[1]
        out[8] = (2 * (x * z + y * w)) * s[2]; out[9] = (2 * (y * z - x * w)) * s[2]; out[10] = (1 - 2 * (x * x + y * y)) * s[2]
        out[12] = t[0]; out[13] = t[1]; out[14] = t[2]; out[15] = 1f
        return out
    }

    fun rotation(degrees: Float, x: Float, y: Float, z: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        val s = sin(half)
        return fromTrs(floatArrayOf(0f, 0f, 0f), floatArrayOf(x * s, y * s, z * s, cos(half)), floatArrayOf(1f, 1f, 1f))
    }

    fun translation(direction: FloatArray, amount: Float): FloatArray = identity().also {
        it[12] = direction[0] * amount; it[13] = direction[1] * amount; it[14] = direction[2] * amount
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
        return FloatArray(16).also {
            it[0] = f / aspect; it[5] = f; it[10] = (far + near) / (near - far); it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    fun lookAt(eye: FloatArray, target: FloatArray, up: FloatArray = floatArrayOf(0f, 1f, 0f)): FloatArray {
        val f = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = normalize(cross(f, up))
        val u = cross(s, f)
        return floatArrayOf(
            s[0], u[0], -f[0], 0f,
            s[1], u[1], -f[1], 0f,
            s[2], u[2], -f[2], 0f,
            -dot(s, eye), -dot(u, eye), dot(f, eye), 1f,
        )
    }

    /** Eye position for an orbit around [center] at [radius] scaled distance. */
    fun orbitEye(center: FloatArray, radius: Float, orbit: Vehicle3dOrbit, fovYDegrees: Float): FloatArray {
        val distance = radius / sin(Math.toRadians(fovYDegrees / 2.0)).toFloat() * 0.92f * orbit.distanceFactor
        val yaw = Math.toRadians(orbit.yawDegrees.toDouble())
        val pitch = Math.toRadians(orbit.pitchDegrees.toDouble())
        return floatArrayOf(
            center[0] + (distance * sin(yaw) * cos(pitch)).toFloat(),
            center[1] + (distance * sin(pitch)).toFloat(),
            center[2] + (distance * cos(yaw) * cos(pitch)).toFloat(),
        )
    }

    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(dot(v, v)).takeIf { it > 0f } ?: 1f
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }
}

package com.ts10.inspector.vehicle3d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.ts10.inspector.environment.RoadLineStyle
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class OffscreenGlesRenderer(
    private val width: Int,
    private val height: Int,
    private val model: Vehicle3dGlbModel,
    /** Render into an ImageReader and hand Compose a hardware bitmap instead of reading pixels back. */
    preferHardwareOutput: Boolean = true,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    var multisampled = false
        private set
    private val program: Int
    private val shadowProgram: Int
    private val buffers = IntArray(3)
    private val textures: IntArray
    // Only the glReadPixels fallback needs CPU copies of the frame.
    private val pixels: IntBuffer by lazy { IntBuffer.allocate(width * height) }
    private val argb by lazy { IntArray(width * height) }
    private val bitmaps by lazy { List(3) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } }
    private var nextBitmap = 0
    private var imageReader: ImageReader? = null
    /** Frames on screen or possibly still drawn by the RenderThread; older ones go back to the reader. */
    private val heldFrames = ArrayDeque<Image>()
    val hardwareOutput: Boolean get() = imageReader != null
    val outputPath: String get() = if (hardwareOutput) "ImageReader hardware bitmap" else "glReadPixels"

    private val restLocal: List<FloatArray> = model.nodes.map { Mat4.fromTrs(it.translation, it.rotation, it.scale) }
    private val lightNodes: Map<Int, String> = Vehicle3dRig.ALL_LIGHTS.associateBy { model.nodeIndex(it) }.filterKeys { it >= 0 }
    private val center: FloatArray
    private val radius: Float
    private val groundY: Float
    private val halfExtents: FloatArray
    private val paintMaterial = model.materials.indexOfFirst { it.name == Vehicle3dRig.PAINT_MATERIAL }

    // Uniform locations.
    private val uViewProj: Int
    private val uModel: Int
    private val uBaseColor: Int
    private val uEmissive: Int
    private val uRoughness: Int
    private val uMetallic: Int
    private val uHasTexture: Int
    private val uTexture: Int
    private val uEye: Int
    private val uSky: Int
    private val uGround: Int
    private val aPosition: Int
    private val aNormal: Int
    private val aUv: Int
    private val uMirror: Int
    private val uFloorY: Int
    private val uReflect: Int
    private val uFadeHeight: Int
    private val uBackground: Int
    private val uFloor: Int
    private val uFloorCenter: Int
    private val uFloorRadius: Int
    private val uFadeBottom: Int
    private val uCabin: Int
    private val uCabinMin: Int
    private val uCabinMax: Int
    private val sViewProj: Int
    private val sPosition: Int
    private val sStrength: Int
    private val floorProgram: Int
    private val studioProgram: Int
    private val stViewProj: Int
    private val stPosition: Int
    private val stWall: Int
    private val stStrength: Int
    private val stHorizon: Int
    private val fViewProj: Int
    private val fPosition: Int
    private val fBackground: Int
    private val fFloor: Int
    private val fFadeBottom: Int
    private val shadowQuad: FloatBuffer
    private val roadProgram: Int
    private val rViewProj: Int
    private val rModel: Int
    private val rPosition: Int
    private val rAsphalt: Int
    private val rLine: Int
    private val rEdge: Int
    private val rActive: Int
    private val rOpacity: Int
    private val rFrontZ: Int
    private val rCarX: Int
    private val rCurve: Int
    private val rLanes: Int
    private val rLaneIndex: Int
    private val rTravel: Int
    private val rStyles: Int
    private val rCenterYellow: Int
    private val rFadeBottom: Int
    private val poolProgram: Int
    private val pViewProj: Int
    private val pPosition: Int
    private val pColor: Int
    private val pStrength: Int
    private val beamProgram: Int
    private val bViewProj: Int
    private val bPosition: Int
    private val bAlong: Int
    private val bMirror: Int
    private val bFloorY: Int
    private val bColor: Int
    private val bStrength: Int
    private val lamps: LampAnchors
    private val dippedBeams: List<FloatBuffer>
    private val highBeams: List<FloatBuffer>
    private val fogBeams: List<FloatBuffer>
    private val boxProgram: Int
    private val xViewProj: Int
    private val xModel: Int
    private val xPosition: Int
    private val xNormal: Int
    private val xColor: Int
    private val xAlpha: Int
    private val xEmissive: Int
    private val xFrontZ: Int
    private val xFadeBottom: Int
    private val unitCube: FloatBuffer = floatBuffer(unitCubeVertices())

    init {
        createEgl(preferHardwareOutput)
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        shadowProgram = linkProgram(SHADOW_VERTEX_SHADER, SHADOW_FRAGMENT_SHADER)
        floorProgram = linkProgram(SHADOW_VERTEX_SHADER, FLOOR_FRAGMENT_SHADER)
        studioProgram = linkProgram(SHADOW_VERTEX_SHADER, STUDIO_FRAGMENT_SHADER)
        stViewProj = GLES20.glGetUniformLocation(studioProgram, "uViewProj")
        stPosition = GLES20.glGetAttribLocation(studioProgram, "aPosition")
        stWall = GLES20.glGetUniformLocation(studioProgram, "uWall")
        stStrength = GLES20.glGetUniformLocation(studioProgram, "uStrength")
        stHorizon = GLES20.glGetUniformLocation(studioProgram, "uHorizon")
        roadProgram = linkProgram(ROAD_VERTEX_SHADER, ROAD_FRAGMENT_SHADER)
        poolProgram = linkProgram(SHADOW_VERTEX_SHADER, POOL_FRAGMENT_SHADER)
        beamProgram = linkProgram(BEAM_VERTEX_SHADER, BEAM_FRAGMENT_SHADER)
        boxProgram = linkProgram(BOX_VERTEX_SHADER, BOX_FRAGMENT_SHADER)
        xViewProj = GLES20.glGetUniformLocation(boxProgram, "uViewProj")
        xModel = GLES20.glGetUniformLocation(boxProgram, "uModel")
        xPosition = GLES20.glGetAttribLocation(boxProgram, "aPosition")
        xNormal = GLES20.glGetAttribLocation(boxProgram, "aNormal")
        xColor = GLES20.glGetUniformLocation(boxProgram, "uColor")
        xAlpha = GLES20.glGetUniformLocation(boxProgram, "uAlpha")
        xEmissive = GLES20.glGetUniformLocation(boxProgram, "uEmissive")
        xFrontZ = GLES20.glGetUniformLocation(boxProgram, "uFrontZ")
        xFadeBottom = GLES20.glGetUniformLocation(boxProgram, "uFadeBottom")
        rViewProj = GLES20.glGetUniformLocation(roadProgram, "uViewProj")
        rModel = GLES20.glGetUniformLocation(roadProgram, "uModel")
        rPosition = GLES20.glGetAttribLocation(roadProgram, "aPosition")
        rAsphalt = GLES20.glGetUniformLocation(roadProgram, "uAsphalt")
        rLine = GLES20.glGetUniformLocation(roadProgram, "uLine")
        rEdge = GLES20.glGetUniformLocation(roadProgram, "uEdge")
        rActive = GLES20.glGetUniformLocation(roadProgram, "uActive")
        rOpacity = GLES20.glGetUniformLocation(roadProgram, "uOpacity")
        rFrontZ = GLES20.glGetUniformLocation(roadProgram, "uFrontZ")
        rCarX = GLES20.glGetUniformLocation(roadProgram, "uCarX")
        rCurve = GLES20.glGetUniformLocation(roadProgram, "uCurve")
        rLanes = GLES20.glGetUniformLocation(roadProgram, "uLanes")
        rLaneIndex = GLES20.glGetUniformLocation(roadProgram, "uLaneIndex")
        rTravel = GLES20.glGetUniformLocation(roadProgram, "uTravel")
        rStyles = GLES20.glGetUniformLocation(roadProgram, "uStyles")
        rCenterYellow = GLES20.glGetUniformLocation(roadProgram, "uCenterYellow")
        rFadeBottom = GLES20.glGetUniformLocation(roadProgram, "uFadeBottom")
        pViewProj = GLES20.glGetUniformLocation(poolProgram, "uViewProj")
        pPosition = GLES20.glGetAttribLocation(poolProgram, "aPosition")
        pColor = GLES20.glGetUniformLocation(poolProgram, "uColor")
        pStrength = GLES20.glGetUniformLocation(poolProgram, "uStrength")
        bViewProj = GLES20.glGetUniformLocation(beamProgram, "uViewProj")
        bPosition = GLES20.glGetAttribLocation(beamProgram, "aPosition")
        bAlong = GLES20.glGetAttribLocation(beamProgram, "aAlong")
        bMirror = GLES20.glGetUniformLocation(beamProgram, "uMirror")
        bFloorY = GLES20.glGetUniformLocation(beamProgram, "uFloorY")
        bColor = GLES20.glGetUniformLocation(beamProgram, "uColor")
        bStrength = GLES20.glGetUniformLocation(beamProgram, "uStrength")
        uMirror = GLES20.glGetUniformLocation(program, "uMirror")
        uFloorY = GLES20.glGetUniformLocation(program, "uFloorY")
        uReflect = GLES20.glGetUniformLocation(program, "uReflect")
        uFadeHeight = GLES20.glGetUniformLocation(program, "uFadeHeight")
        uBackground = GLES20.glGetUniformLocation(program, "uBackground")
        uFloor = GLES20.glGetUniformLocation(program, "uFloor")
        uFloorCenter = GLES20.glGetUniformLocation(program, "uFloorCenter")
        uFloorRadius = GLES20.glGetUniformLocation(program, "uFloorRadius")
        uFadeBottom = GLES20.glGetUniformLocation(program, "uFadeBottom")
        uCabin = GLES20.glGetUniformLocation(program, "uCabin")
        uCabinMin = GLES20.glGetUniformLocation(program, "uCabinMin")
        uCabinMax = GLES20.glGetUniformLocation(program, "uCabinMax")
        fViewProj = GLES20.glGetUniformLocation(floorProgram, "uViewProj")
        fPosition = GLES20.glGetAttribLocation(floorProgram, "aPosition")
        fBackground = GLES20.glGetUniformLocation(floorProgram, "uBackground")
        fFloor = GLES20.glGetUniformLocation(floorProgram, "uFloor")
        fFadeBottom = GLES20.glGetUniformLocation(floorProgram, "uFadeBottom")
        uViewProj = GLES20.glGetUniformLocation(program, "uViewProj")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor")
        uEmissive = GLES20.glGetUniformLocation(program, "uEmissive")
        uRoughness = GLES20.glGetUniformLocation(program, "uRoughness")
        uMetallic = GLES20.glGetUniformLocation(program, "uMetallic")
        uHasTexture = GLES20.glGetUniformLocation(program, "uHasTexture")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uEye = GLES20.glGetUniformLocation(program, "uEye")
        uSky = GLES20.glGetUniformLocation(program, "uSky")
        uGround = GLES20.glGetUniformLocation(program, "uGround")
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        sViewProj = GLES20.glGetUniformLocation(shadowProgram, "uViewProj")
        sPosition = GLES20.glGetAttribLocation(shadowProgram, "aPosition")
        sStrength = GLES20.glGetUniformLocation(shadowProgram, "uStrength")

        // One VBO and one IBO holding the whole BIN chunk: accessors are plain byte offsets.
        GLES20.glGenBuffers(2, buffers, 0)
        val binary = model.binary.duplicate().apply { position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, binary.capacity(), binary, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, binary.capacity(), binary, GLES20.GL_STATIC_DRAW)
        textures = IntArray(model.images.size)
        if (textures.isNotEmpty()) GLES20.glGenTextures(textures.size, textures, 0)
        model.images.forEachIndexed { index, png ->
            val image = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "Imagen $index ilegible" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            image.recycle()
        }

        // Rest-pose bounds of everything visible with the lights off.
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        traverse(emptyMap(), emptySet()) { world, primitive, _ ->
            val lo = primitive.position.min ?: return@traverse
            val hi = primitive.position.max ?: return@traverse
            for (corner in 0 until 8) {
                val p = Mat4.transformPoint(
                    world,
                    if (corner and 1 == 0) lo[0] else hi[0],
                    if (corner and 2 == 0) lo[1] else hi[1],
                    if (corner and 4 == 0) lo[2] else hi[2],
                )
                for (axis in 0 until 3) {
                    min[axis] = minOf(min[axis], p[axis])
                    max[axis] = maxOf(max[axis], p[axis])
                }
            }
        }
        center = FloatArray(3) { (min[it] + max[it]) / 2f }
        halfExtents = FloatArray(3) { (max[it] - min[it]) / 2f }
        radius = sqrt(halfExtents.sumOf { (it * it).toDouble() }).toFloat()
        groundY = min[1]
        shadowQuad = floatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f),
        )
        lamps = measureLamps(model, lightNodes, restLocal, center, halfExtents, groundY)
        dippedBeams = lamps.headlamps.map { beamCone(it, DIPPED_BEAM) }
        highBeams = lamps.headlamps.map { beamCone(it, HIGH_BEAM) }
        fogBeams = lamps.fogLamps.map { beamCone(it, FOG_BEAM) }
    }

    private fun createEgl(preferHardwareOutput: Boolean) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "eglInitialize" }
        if (!preferHardwareOutput || !createHardwareOutput()) createPbufferOutput()
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
    }

    /**
     * The ImageReader surface is consumed inside this process, never by the compositor, so it does
     * not share the SurfaceView/TextureView conflicts seen with Filament on the TS10.
     */
    private fun createHardwareOutput(): Boolean {
        val reader = runCatching {
            ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, READER_MAX_IMAGES,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
            )
        }.getOrElse { return false }
        for (samples in intArrayOf(4, 0)) {
            val config = chooseConfig(samples, EGL14.EGL_WINDOW_BIT) ?: continue
            val candidate = createContext(config) ?: continue
            val window = runCatching {
                EGL14.eglCreateWindowSurface(display, config, reader.surface, intArrayOf(EGL14.EGL_NONE), 0)
            }.getOrNull()
            if (window == null || window == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, candidate)
                continue
            }
            context = candidate
            surface = window
            multisampled = samples > 0
            imageReader = reader
            // The reader is not paced by vsync; a swap must never wait for one.
            EGL14.eglMakeCurrent(display, surface, surface, context)
            EGL14.eglSwapInterval(display, 0)
            return true
        }
        reader.close()
        return false
    }

    private fun createPbufferOutput() {
        val config = chooseConfig(samples = 4, EGL14.EGL_PBUFFER_BIT)?.also { multisampled = true }
            ?: checkNotNull(chooseConfig(samples = 0, EGL14.EGL_PBUFFER_BIT)) { "eglChooseConfig" }
        context = checkNotNull(createContext(config)) { "eglCreateContext" }
        surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
    }

    private fun createContext(config: EGLConfig): EGLContext? = EGL14.eglCreateContext(
        display, config, EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
    ).takeIf { it != EGL14.EGL_NO_CONTEXT }

    private fun chooseConfig(samples: Int, surfaceType: Int): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
            EGL14.EGL_SAMPLES, samples,
            EGL14.EGL_NONE,
        )
        val ok = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    /** Walks the node tree; light subtrees that are off are skipped like the GLB's scale 0. */
    private inline fun traverse(
        motions: Map<String, FloatArray>,
        litLights: Set<String>,
        crossinline visit: (FloatArray, GlbPrimitive, Boolean) -> Unit,
    ) {
        val stack = ArrayDeque<Triple<Int, FloatArray, Boolean>>()
        model.roots.forEach { stack.addLast(Triple(it, Mat4.identity(), false)) }
        while (stack.isNotEmpty()) {
            val (index, parent, insideLamp) = stack.removeLast()
            val node = model.nodes[index]
            val light = lightNodes[index]
            if (light != null && light !in litLights) continue
            val rest = if (light != null) {
                Mat4.fromTrs(node.translation, node.rotation, floatArrayOf(1f, 1f, 1f))
            } else {
                restLocal[index]
            }
            val local = motions[node.name]?.let { Mat4.multiply(rest, it) } ?: rest
            val world = Mat4.multiply(parent, local)
            val lamp = insideLamp || light != null
            if (node.mesh >= 0) model.meshes[node.mesh].forEach { visit(world, it, lamp) }
            node.children.forEach { stack.addLast(Triple(it, world, lamp)) }
        }
    }

    /**
     * Cielo de atardecer en franjas horizontales limpiadas con tijera: un degradado sin shader.
     * [blend] mezcla desde el color de fondo para que entre poco a poco.
     */
    /** Pared del estudio a pantalla completa, mezclada con alfa sobre el fondo ya borrado. */
    private fun drawStudio(wall: FloatArray, strength: Float) {
        if (strength <= 0f) return
        GLES20.glUseProgram(studioProgram)
        GLES20.glUniformMatrix4fv(stViewProj, 1, false, Mat4.identity(), 0)
        GLES20.glUniform3fv(stWall, 1, wall, 0)
        GLES20.glUniform1f(stStrength, strength)
        GLES20.glUniform1f(stHorizon, STUDIO_HORIZON)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
        shadowQuad.position(0)
        GLES20.glEnableVertexAttribArray(stPosition)
        GLES20.glVertexAttribPointer(stPosition, 2, GLES20.GL_FLOAT, false, 0, shadowQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(stPosition)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun clearSunset(rows: Int, background: FloatArray, blend: Float) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        val bands = SUNSET_BANDS
        for (band in 0 until bands) {
            val y0 = rows * band / bands
            val y1 = rows * (band + 1) / bands
            // GL cuenta las filas desde abajo; t = 0 arriba de la imagen, 1 abajo.
            val t = 1f - (y0 + y1) / 2f / rows
            val sky = sunsetColor(t)
            GLES20.glScissor(0, y0, width, y1 - y0)
            GLES20.glClearColor(
                background[0] + (sky[0] - background[0]) * blend,
                background[1] + (sky[1] - background[1]) * blend,
                background[2] + (sky[2] - background[2]) * blend,
                1f,
            )
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun sunsetColor(t: Float): FloatArray {
        val stops = SUNSET_STOPS
        val index = stops.indexOfFirst { it[0] >= t }.let { if (it <= 0) 1 else it }
        val a = stops[index - 1]
        val b = stops[index]
        val f = ((t - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
        return FloatArray(3) { a[it + 1] + (b[it + 1] - a[it + 1]) * f }
    }

    /** Duración de las fases del último fotograma, en nanosegundos. */
    var lastDrawNs = 0L
        private set
    var lastReadbackNs = 0L
        private set
    var lastConvertNs = 0L
        private set

    fun render(input: Vehicle3dSceneInput, animator: Vehicle3dSceneAnimator, litLights: Set<String>): Bitmap {
        val drawStarted = System.nanoTime()
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent render" }
        // Parado se dibuja solo la parte inferior (el hueco visible del carrusel); al conducir la
        // altura crece con la transición a carretera para que el encuadre no salte.
        val parkedRows = height * Vehicle3dFrame.PARKED_HEIGHT / Vehicle3dFrame.HEIGHT
        val roadBlend = if (input.topView) 0f else 1f - animator.showroom
        val viewportHeight = (parkedRows + (height - parkedRows) * roadBlend).toInt().coerceIn(1, height)
        GLES20.glViewport(0, 0, width, viewportHeight)
        val background = if (input.dark) DARK_BACKGROUND else LIGHT_BACKGROUND
        // The aerial view sits on the tyre card, so its frame is transparent and has no floor.
        // A hardware bitmap is premultiplied: the transparent aerial frame must clear to black or the
        // background colour would be added over the tyre card.
        if (input.topView && hardwareOutput) GLES20.glClearColor(0f, 0f, 0f, 0f)
        else GLES20.glClearColor(background[0], background[1], background[2], if (input.topView) 0f else 1f)
        val showroom = if (input.topView) 1f else animator.showroom
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // Desde el interior y con tema oscuro, por los cristales se ve un atardecer en vez de negro,
        // para que el salpicadero contraste. Aparece a medida que la cámara entra (su campo visual
        // pasa de 30° fuera a 74° dentro).
        val cabinBlend = ((animator.orbit.fovDegrees - DEFAULT_FOV_DEGREES) / (CABIN_FOV_DEGREES - DEFAULT_FOV_DEGREES))
            .coerceIn(0f, 1f)
        val showroomFloor = input.floorArgb?.let { customFloor(it, input.dark) } ?: if (input.dark) DARK_FLOOR else LIGHT_FLOOR
        if (input.dark && !input.topView && cabinBlend > 0f) clearSunset(viewportHeight, background, cabinBlend)
        if (!input.topView && showroom > 0f) {
            // La pared es siempre neutra aunque el suelo tenga color.
            drawStudio(studioWall(if (input.dark) DARK_FLOOR else LIGHT_FLOOR, background, input.dark), showroom * (1f - cabinBlend))
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE) // Blender meshes are double sided.

        // Moving eye and target against the camera's right vector slides the car across the frame.
        val yaw = Math.toRadians(animator.orbit.yawDegrees.toDouble())
        val shiftX = -animator.orbit.lateralMeters * cos(yaw).toFloat()
        val shiftZ = animator.orbit.lateralMeters * sin(yaw).toFloat()
        val orbit = animator.orbit
        val target = floatArrayOf(
            center[0] + orbit.targetX * halfExtents[0] + shiftX,
            center[1] + orbit.targetY * halfExtents[1],
            center[2] + orbit.targetZ * halfExtents[2] + shiftZ,
        )
        // The eye orbits a point above the look-at target; for the default view that is the centre.
        val pivot = floatArrayOf(target[0] - shiftX, target[1] - DEFAULT_TARGET_Y * halfExtents[1], target[2] - shiftZ)
        val eye = Mat4.orbitEye(pivot, radius, orbit, orbit.fovDegrees).also { it[0] += shiftX; it[2] += shiftZ }
        val viewProj = Mat4.multiply(
            // The far plane must reach the end of the road, well beyond the car; the near plane
            // stays short so the cabin view does not clip the dashboard.
            Mat4.perspective(orbit.fovDegrees, width.toFloat() / viewportHeight, 0.05f, max(radius * 12f, ROAD_FAR_METERS)),
            Mat4.lookAt(eye, target),
        )

        // While driving the floor spot fades into the background and the road takes its place.
        val floor = FloatArray(3) { background[it] + (showroomFloor[it] - background[it]) * showroom }
        if (!input.topView && showroom > 0f) drawFloor(viewProj, background, floor)
        val motions = animator.motions()
        val paint = srgbToLinear(input.paintArgb)
        // Glossy showroom floor: the car mirrored below the ground plane, fading out with depth.
        // It fades out while the camera rises so the aerial transition has no visible pop.
        val pitchFade = if (input.topView) 0f else
            ((REFLECTION_MAX_PITCH - animator.orbit.pitchDegrees) / REFLECTION_FADE_DEGREES).coerceIn(0f, 1f)
        val reflection = (if (input.dark) DARK_REFLECTION else LIGHT_REFLECTION) * pitchFade * showroom
        if (reflection > 0f) {
            val mirroredEye = floatArrayOf(eye[0], 2f * groundY - eye[1], eye[2])
            drawVehicle(viewProj, mirroredEye, motions, litLights, paint, input.dark, background, floor, reflection)
            drawBeams(viewProj, input, mirrored = true, strength = reflection / DARK_REFLECTION * MIRRORED_BEAM)
        }
        if (!input.topView && showroom < 1f) {
            drawRoad(viewProj, input, animator, 1f - showroom)
            drawRoadObjects(viewProj, input, animator, 1f - showroom)
        }
        drawShadow(viewProj, input.dark)
        if (!input.topView) drawLightPools(viewProj, input)
        drawVehicle(viewProj, eye, motions, litLights, paint, input.dark, background, floor, reflection = 0f)
        if (!input.topView) drawBeams(viewProj, input, mirrored = false, strength = 1f)
        if (!input.topView && input.brake) drawThirdBrakeHalo(viewProj, eye, input.dark)

        GLES20.glFinish()
        val drawFinished = System.nanoTime()
        imageReader?.let { reader ->
            val bitmap = publishHardwareFrame(reader)
            lastDrawNs = drawFinished - drawStarted
            lastReadbackNs = System.nanoTime() - drawFinished
            lastConvertNs = 0L
            return bitmap
        }
        pixels.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val readFinished = System.nanoTime()
        rgbaBottomUpToArgb(pixels.array(), argb, width, height)
        // Three bitmaps rotate so Compose never draws the one being written.
        val bitmap = bitmaps[nextBitmap]
        nextBitmap = (nextBitmap + 1) % bitmaps.size
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        lastDrawNs = drawFinished - drawStarted
        lastReadbackNs = readFinished - drawFinished
        lastConvertNs = System.nanoTime() - readFinished
        return bitmap
    }

    /**
     * glFinish above already waited for the GPU, so the queued buffer is complete when HWUI samples
     * it. The bitmap shares the buffer: an image only returns to the reader once newer frames
     * exist, the same margin the three rotating CPU bitmaps gave.
     */
    private fun publishHardwareFrame(reader: ImageReader): Bitmap {
        check(EGL14.eglSwapBuffers(display, surface)) { "eglSwapBuffers 0x${Integer.toHexString(EGL14.eglGetError())}" }
        var image = reader.acquireLatestImage()
        var attempts = 0
        while (image == null && attempts++ < ACQUIRE_RETRIES) {
            Thread.sleep(1)
            image = reader.acquireLatestImage()
        }
        checkNotNull(image) { "ImageReader returned no frame" }
        val bitmap = try {
            val buffer = checkNotNull(image.hardwareBuffer) { "Frame without HardwareBuffer" }
            try {
                checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))) {
                    "wrapHardwareBuffer rejected the frame"
                }
            } finally {
                buffer.close()
            }
        } catch (error: Throwable) {
            image.close()
            throw error
        }
        heldFrames.addLast(image)
        while (heldFrames.size > HELD_FRAMES) heldFrames.removeFirst().close()
        return bitmap
    }

    private fun drawVehicle(
        viewProj: FloatArray,
        eye: FloatArray,
        motions: Map<String, FloatArray>,
        litLights: Set<String>,
        paint: FloatArray,
        dark: Boolean,
        background: FloatArray,
        floor: FloatArray,
        /** 0 draws the real car; above 0 draws its mirror image with this strength. */
        reflection: Float,
    ) {
        val mirrored = reflection > 0f
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniform3fv(uEye, 1, eye, 0)
        GLES20.glUniform3fv(uSky, 1, if (dark) DARK_SKY else LIGHT_SKY, 0)
        GLES20.glUniform3fv(uGround, 1, if (dark) DARK_GROUND else LIGHT_GROUND, 0)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1f(uMirror, if (mirrored) 1f else 0f)
        GLES20.glUniform1f(uFloorY, groundY)
        GLES20.glUniform1f(uReflect, reflection)
        GLES20.glUniform1f(uFadeHeight, halfExtents[1] * REFLECTION_DEPTH)
        GLES20.glUniform1f(uFadeBottom, height * FLOOR_BOTTOM_FADE)
        GLES20.glUniform3fv(uCabinMin, 1, CABIN_MIN, 0)
        GLES20.glUniform3fv(uCabinMax, 1, CABIN_MAX, 0)
        GLES20.glUniform3fv(uBackground, 1, background, 0)
        GLES20.glUniform3fv(uFloor, 1, floor, 0)
        GLES20.glUniform2f(uFloorCenter, center[0], center[2])
        GLES20.glUniform2f(uFloorRadius, radius * FLOOR_RADIUS_X, radius * FLOOR_RADIUS_Z)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)

        // Mirror about the ground plane y = groundY.
        val mirror = if (mirrored) Mat4.identity().also { it[5] = -1f; it[13] = 2f * groundY } else null
        val blended = ArrayList<Triple<FloatArray, GlbPrimitive, Boolean>>()
        // El reflejo se mezcla con alfa sobre el suelo; el coche real es opaco.
        if (mirrored) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        GLES20.glDepthMask(true)
        traverse(motions, litLights) { local, primitive, lamp ->
            val world = mirror?.let { Mat4.multiply(it, local) } ?: local
            val material = model.materials.getOrNull(primitive.material)
            if (material?.blend == true) {
                blended += Triple(world, primitive, lamp)
            } else {
                draw(world, primitive, material, paint, lamp)
            }
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        blended.forEach { (world, primitive, lamp) ->
            draw(world, primitive, model.materials[primitive.material], paint, lamp)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Showroom floor: a lit spot under the car fading into the background colour. */
    private fun drawFloor(viewProj: FloatArray, background: FloatArray, floor: FloatArray) {
        GLES20.glUseProgram(floorProgram)
        val model = FloatArray(16).also {
            it[0] = radius * FLOOR_RADIUS_X
            it[6] = radius * FLOOR_RADIUS_Z
            it[9] = 1f
            it[12] = center[0]; it[13] = groundY; it[14] = center[2]; it[15] = 1f
        }
        GLES20.glUniformMatrix4fv(fViewProj, 1, false, Mat4.multiply(viewProj, model), 0)
        GLES20.glUniform3fv(fBackground, 1, background, 0)
        GLES20.glUniform3fv(fFloor, 1, floor, 0)
        GLES20.glUniform1f(fFadeBottom, height * FLOOR_BOTTOM_FADE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
        // Not written to depth: the mirrored car below the plane must stay visible through it.
        GLES20.glDepthMask(false)
        shadowQuad.position(0)
        GLES20.glEnableVertexAttribArray(fPosition)
        GLES20.glVertexAttribPointer(fPosition, 2, GLES20.GL_FLOAT, false, 0, shadowQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(fPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun draw(
        world: FloatArray,
        primitive: GlbPrimitive,
        material: GlbMaterial?,
        paint: FloatArray,
        lamp: Boolean,
    ) {
        val isPaint = primitive.material == paintMaterial
        val base = when {
            isPaint -> floatArrayOf(paint[0], paint[1], paint[2], 1f)
            // Window glass is exported almost white for Blender's transmission; without refraction it
            // would veil the cabin, so it is drawn as dark tinted glass that keeps the reflections.
            material != null && material.blend && material.baseColor[0] > 0.5f -> TINTED_GLASS
            material != null -> material.baseColor
            else -> DEFAULT_BASE
        }
        GLES20.glUniformMatrix4fv(uModel, 1, false, world, 0)
        // Glass and paint (roof, pillars) keep full light; everything else inside the cabin is occluded.
        GLES20.glUniform1f(uCabin, if (isPaint || material?.blend == true) 0f else 1f)
        GLES20.glUniform4fv(uBaseColor, 1, base, 0)
        val emissive = material?.emissive ?: NO_EMISSION
        // Lit lamps were barely visible after tone mapping: push them into the white-hot range.
        GLES20.glUniform3fv(
            uEmissive, 1,
            if (lamp) FloatArray(3) { emissive[it] * LAMP_EMISSIVE_GAIN } else emissive, 0,
        )
        // Paint gets a clear-coat look regardless of the exported factors.
        GLES20.glUniform1f(uRoughness, if (isPaint) 0.22f else material?.roughness ?: 0.6f)
        GLES20.glUniform1f(uMetallic, if (isPaint) 0f else material?.metallic ?: 0f)
        val image = material?.baseColorImage
        val uv = primitive.uv
        if (image != null && uv != null && image < textures.size) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[image])
            GLES20.glUniform1f(uHasTexture, 1f)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv.offset)
        } else {
            GLES20.glUniform1f(uHasTexture, 0f)
            GLES20.glDisableVertexAttribArray(aUv)
            GLES20.glVertexAttrib2f(aUv, 0f, 0f)
        }
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, primitive.position.offset)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, primitive.normal.offset)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, primitive.indices.count, GLES20.GL_UNSIGNED_SHORT, primitive.indices.offset)
    }

    /** Procedural road on the ground plane with the OSM lanes, fading with distance. */
    private fun drawRoad(
        viewProj: FloatArray,
        input: Vehicle3dSceneInput,
        animator: Vehicle3dSceneAnimator,
        opacity: Float,
    ) {
        GLES20.glUseProgram(roadProgram)
        val frontZ = lamps.frontZ
        val model = FloatArray(16).also {
            it[0] = ROAD_HALF_WIDTH
            it[6] = ROAD_HALF_LENGTH
            it[9] = 1f
            it[12] = center[0]; it[13] = groundY
            it[14] = frontZ - ROAD_HALF_LENGTH + ROAD_BEHIND_METERS; it[15] = 1f
        }
        val lanes = input.roadLanes.coerceIn(0, MAX_ROAD_LANES)
        val laneCount = lanes.coerceAtLeast(1)
        val styles = FloatArray(MAX_ROAD_LANES + 1)
        for (boundary in 0..laneCount) {
            val style = when {
                lanes == 0 -> RoadLineStyle.SOLID
                boundary == 0 -> if (input.roadOneway) RoadLineStyle.SOLID else input.roadCenterLine
                boundary == laneCount -> RoadLineStyle.SOLID
                else -> input.roadLaneLines.getOrNull(boundary - 1) ?: RoadLineStyle.DASHED
            }
            styles[boundary] = when (style) {
                RoadLineStyle.NONE -> 0f
                RoadLineStyle.SOLID -> 1f
                RoadLineStyle.DASHED, RoadLineStyle.UNKNOWN -> 2f
                RoadLineStyle.DOUBLE_SOLID -> 3f
            }
        }
        GLES20.glUniformMatrix4fv(rViewProj, 1, false, viewProj, 0)
        GLES20.glUniformMatrix4fv(rModel, 1, false, model, 0)
        GLES20.glUniform3fv(rAsphalt, 1, if (input.dark) DARK_ASPHALT else LIGHT_ASPHALT, 0)
        GLES20.glUniform3fv(rLine, 1, if (input.dark) DARK_LANE_LINE else LIGHT_LANE_LINE, 0)
        GLES20.glUniform3fv(rEdge, 1, ROAD_EDGE, 0)
        GLES20.glUniform3fv(rActive, 1, if (input.dark) DARK_ACTIVE_LANE else LIGHT_ACTIVE_LANE, 0)
        GLES20.glUniform1f(rOpacity, opacity)
        GLES20.glUniform1f(rFrontZ, frontZ)
        GLES20.glUniform1f(rCarX, center[0])
        GLES20.glUniform1f(rCurve, input.roadCurve * ROAD_CURVE_METERS)
        GLES20.glUniform1f(rLanes, laneCount.toFloat())
        GLES20.glUniform1f(rLaneIndex, animator.laneIndex.coerceIn(0f, laneCount - 1f))
        GLES20.glUniform1f(rTravel, animator.travelMeters)
        GLES20.glUniform1fv(rStyles, styles.size, styles, 0)
        GLES20.glUniform1f(rCenterYellow, if (lanes > 0 && !input.roadOneway) 1f else 0f)
        GLES20.glUniform1f(rFadeBottom, height * BOTTOM_FADE * 0.5f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        shadowQuad.position(0)
        GLES20.glEnableVertexAttribArray(rPosition)
        GLES20.glVertexAttribPointer(rPosition, 2, GLES20.GL_FLOAT, false, 0, shadowQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(rPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Camera detections as simple volumes on the road. Instancing the GLB would cost as much as the
     * car itself per object (the frame is vertex bound), so a handful of boxes stands in for each.
     */
    private fun drawRoadObjects(
        viewProj: FloatArray,
        input: Vehicle3dSceneInput,
        animator: Vehicle3dSceneAnimator,
        opacity: Float,
    ) {
        val objects = animator.roadObjects
        if (objects.isEmpty() || opacity <= 0f) return
        GLES20.glUseProgram(boxProgram)
        GLES20.glUniformMatrix4fv(xViewProj, 1, false, viewProj, 0)
        GLES20.glUniform1f(xFrontZ, lamps.frontZ)
        GLES20.glUniform1f(xFadeBottom, height * BOTTOM_FADE * 0.5f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        unitCube.position(0)
        GLES20.glVertexAttribPointer(xPosition, 3, GLES20.GL_FLOAT, false, CUBE_STRIDE_BYTES, unitCube)
        unitCube.position(3)
        GLES20.glVertexAttribPointer(xNormal, 3, GLES20.GL_FLOAT, false, CUBE_STRIDE_BYTES, unitCube)
        GLES20.glEnableVertexAttribArray(xPosition)
        GLES20.glEnableVertexAttribArray(xNormal)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
        val curve = input.roadCurve * ROAD_CURVE_METERS
        val relationBlend = if (input.dark) DARK_OBJECT_BODY else LIGHT_OBJECT_BODY
        // Farthest first so the nearer translucent volumes blend over them.
        objects.sortedByDescending { it.aheadMeters }.forEach { obj ->
            val parts = ROAD_OBJECT_PARTS.getValue(obj.kind)
            val length = ROAD_OBJECT_LENGTH.getValue(obj.kind)
            val ahead = obj.aheadMeters + length / 2f
            // Same bend as the road shader: lateral offset c*d^2, heading along its derivative.
            val yaw = -kotlin.math.atan(2f * curve * ahead)
            val half = yaw / 2f
            val objectFrame = Mat4.fromTrs(
                floatArrayOf(center[0] + obj.lateralLanes * LANE_WIDTH_METERS + curve * ahead * ahead, groundY, lamps.frontZ - ahead),
                floatArrayOf(0f, sin(half), 0f, cos(half)),
                floatArrayOf(1f, 1f, 1f),
            )
            val relation = if (obj.egoLane) EGO_LANE_OBJECT else OTHER_LANE_OBJECT
            val body = FloatArray(3) { relationBlend[it] + (relation[it] - relationBlend[it]) * OBJECT_TINT }
            val alpha = OBJECT_ALPHA * obj.presence * opacity
            parts.forEach { part ->
                val color = when (part.role) {
                    PartRole.BODY -> body
                    PartRole.GLASS -> OBJECT_GLASS
                    PartRole.TYRE -> OBJECT_TYRE
                    PartRole.TAIL -> OBJECT_TAIL
                }
                val position = Mat4.transformPoint(objectFrame, part.x, part.y, part.z)
                val model = Mat4.fromTrs(position, floatArrayOf(0f, sin(half), 0f, cos(half)), part.size)
                GLES20.glUniformMatrix4fv(xModel, 1, false, model, 0)
                GLES20.glUniform3fv(xColor, 1, color, 0)
                GLES20.glUniform1f(xAlpha, alpha)
                GLES20.glUniform1f(xEmissive, if (part.role == PartRole.TAIL) 1f else 0f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
            }
        }
        GLES20.glDisableVertexAttribArray(xPosition)
        GLES20.glDisableVertexAttribArray(xNormal)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Light that the lamps throw on the floor or the road, added on top of it. */
    private fun drawLightPools(viewProj: FloatArray, input: Vehicle3dSceneInput) {
        val themeGain = if (input.dark) 1f else LIGHT_THEME_GAIN
        val front = lamps.frontZ
        val rear = lamps.rearZ
        val pools = buildList {
            if (input.dippedBeam || input.highBeam) add(LightPool(front - 5.5f, 2.8f, 5.6f, HEADLAMP_COLOR, 0.7f))
            if (input.highBeam) add(LightPool(front - 13f, 3.4f, 10f, HEADLAMP_COLOR, 0.5f))
            if (input.frontFog) add(LightPool(front - 2.6f, 3.6f, 2.4f, FOG_COLOR, 0.55f))
            if (input.positionLights || input.dippedBeam || input.highBeam) {
                add(LightPool(rear + 0.7f, 1.3f, 0.8f, TAIL_COLOR, 0.28f))
            }
            if (input.brake) add(LightPool(rear + 1.0f, 1.6f, 1.2f, TAIL_COLOR, 0.34f))
            if (input.rearFog) add(LightPool(rear + 1.3f, 1.0f, 1.8f, TAIL_COLOR, 0.42f))
            if (input.reverse) add(LightPool(rear + 2.2f, 1.7f, 2.2f, REVERSE_COLOR, 0.34f))
        }
        if (pools.isEmpty()) return
        GLES20.glUseProgram(poolProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ZERO, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        shadowQuad.position(0)
        GLES20.glEnableVertexAttribArray(pPosition)
        GLES20.glVertexAttribPointer(pPosition, 2, GLES20.GL_FLOAT, false, 0, shadowQuad)
        pools.forEach { pool ->
            val model = FloatArray(16).also {
                it[0] = pool.halfWidth
                it[6] = pool.halfLength
                it[9] = 1f
                it[12] = center[0]; it[13] = groundY + 0.006f; it[14] = pool.centerZ; it[15] = 1f
            }
            GLES20.glUniformMatrix4fv(pViewProj, 1, false, Mat4.multiply(viewProj, model), 0)
            GLES20.glUniform3fv(pColor, 1, pool.color, 0)
            GLES20.glUniform1f(pStrength, pool.strength * themeGain)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        }
        GLES20.glDisableVertexAttribArray(pPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Translucent light cones in front of the lamps; [mirrored] draws them in the floor reflection. */
    private fun drawBeams(viewProj: FloatArray, input: Vehicle3dSceneInput, mirrored: Boolean, strength: Float) {
        val themeGain = if (input.dark) 1f else LIGHT_THEME_GAIN
        val cones = buildList {
            if (input.dippedBeam || input.highBeam) {
                dippedBeams.forEach { add(Triple(it, HEADLAMP_COLOR, DIPPED_BEAM.strength)) }
            }
            if (input.highBeam) highBeams.forEach { add(Triple(it, HEADLAMP_COLOR, HIGH_BEAM.strength)) }
            if (input.frontFog) fogBeams.forEach { add(Triple(it, FOG_COLOR, FOG_BEAM.strength)) }
        }
        if (cones.isEmpty() || strength <= 0f) return
        GLES20.glUseProgram(beamProgram)
        GLES20.glUniformMatrix4fv(bViewProj, 1, false, viewProj, 0)
        GLES20.glUniform1f(bMirror, if (mirrored) 1f else 0f)
        GLES20.glUniform1f(bFloorY, groundY)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ZERO, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glEnableVertexAttribArray(bPosition)
        GLES20.glEnableVertexAttribArray(bAlong)
        cones.forEach { (buffer, color, coneStrength) ->
            buffer.position(0)
            GLES20.glVertexAttribPointer(bPosition, 3, GLES20.GL_FLOAT, false, BEAM_STRIDE, buffer)
            buffer.position(3)
            GLES20.glVertexAttribPointer(bAlong, 1, GLES20.GL_FLOAT, false, BEAM_STRIDE, buffer)
            GLES20.glUniform3fv(bColor, 1, color, 0)
            GLES20.glUniform1f(bStrength, coneStrength * strength * themeGain)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, BEAM_SEGMENTS * 3)
        }
        GLES20.glDisableVertexAttribArray(bPosition)
        GLES20.glDisableVertexAttribArray(bAlong)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * The third brake lamp is a thin strip under the spoiler and hardly lit a pixel: a red glow
     * facing the camera makes it read. Only drawn when the rear of the car faces the camera.
     */
    private fun drawThirdBrakeHalo(viewProj: FloatArray, eye: FloatArray, dark: Boolean) {
        val lamp = lamps.thirdBrake
        val toEye = floatArrayOf(eye[0] - lamp[0], eye[1] - lamp[1], eye[2] - lamp[2])
        val length = sqrt(toEye[0] * toEye[0] + toEye[1] * toEye[1] + toEye[2] * toEye[2])
        if (length <= 0f || toEye[2] / length < 0.15f) return
        // Billboard basis: right = horizontal perpendicular to the view, up = world up.
        val horizontal = sqrt(toEye[0] * toEye[0] + toEye[2] * toEye[2]).coerceAtLeast(1e-4f)
        val rightX = toEye[2] / horizontal
        val rightZ = -toEye[0] / horizontal
        val model = FloatArray(16).also {
            it[0] = rightX * THIRD_BRAKE_HALO_WIDTH; it[2] = rightZ * THIRD_BRAKE_HALO_WIDTH
            it[5] = THIRD_BRAKE_HALO_HEIGHT
            it[10] = 1f
            it[12] = lamp[0]; it[13] = lamp[1]; it[14] = lamp[2] + 0.03f; it[15] = 1f
        }
        GLES20.glUseProgram(poolProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ZERO, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        shadowQuad.position(0)
        GLES20.glEnableVertexAttribArray(pPosition)
        GLES20.glVertexAttribPointer(pPosition, 2, GLES20.GL_FLOAT, false, 0, shadowQuad)
        GLES20.glUniformMatrix4fv(pViewProj, 1, false, Mat4.multiply(viewProj, model), 0)
        GLES20.glUniform3fv(pColor, 1, TAIL_COLOR, 0)
        GLES20.glUniform1f(pStrength, if (dark) 0.95f else 0.6f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(pPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** Soft contact shadow: a quad under the car with a radial falloff. */
    private fun drawShadow(viewProj: FloatArray, dark: Boolean) {
        GLES20.glUseProgram(shadowProgram)
        // The quad's local (x, y) maps to world (x, z) on the ground plane.
        val model = FloatArray(16).also {
            it[0] = halfExtents[0] * 1.15f
            it[6] = halfExtents[2] * 1.05f
            it[9] = 1f
            it[12] = center[0]; it[13] = groundY + 0.004f; it[14] = center[2]; it[15] = 1f
        }
        GLES20.glUniformMatrix4fv(sViewProj, 1, false, Mat4.multiply(viewProj, model), 0)
        GLES20.glUniform1f(sStrength, if (dark) 0.8f else 0.42f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        shadowQuad.position(0)
        GLES20.glEnableVertexAttribArray(sPosition)
        GLES20.glVertexAttribPointer(sPosition, 2, GLES20.GL_FLOAT, false, 0, shadowQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(sPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, surface, surface, context)
        GLES20.glDeleteBuffers(2, buffers, 0)
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(shadowProgram)
        GLES20.glDeleteProgram(floorProgram)
        GLES20.glDeleteProgram(studioProgram)
        GLES20.glDeleteProgram(roadProgram)
        GLES20.glDeleteProgram(boxProgram)
        GLES20.glDeleteProgram(poolProgram)
        GLES20.glDeleteProgram(beamProgram)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        // Published hardware bitmaps keep their own buffer reference and stay drawable.
        heldFrames.forEach { it.close() }
        heldFrames.clear()
        imageReader?.close()
        imageReader = null
        // Bitmaps are not recycled: Compose may still be drawing the last published frame.
    }

    private companion object {
        const val READER_MAX_IMAGES = 5
        const val HELD_FRAMES = 3
        const val ACQUIRE_RETRIES = 20
        const val LAMP_EMISSIVE_GAIN = 5.5f
        const val THIRD_BRAKE_HALO_WIDTH = 0.42f
        const val THIRD_BRAKE_HALO_HEIGHT = 0.12f
        const val MIRRORED_BEAM = 0.7f
        const val LIGHT_THEME_GAIN = 0.6f
        val HEADLAMP_COLOR = floatArrayOf(1f, 0.94f, 0.82f)
        val FOG_COLOR = floatArrayOf(1f, 0.92f, 0.72f)
        val TAIL_COLOR = floatArrayOf(1f, 0.08f, 0.06f)
        val REVERSE_COLOR = floatArrayOf(0.95f, 0.97f, 1f)
        const val ROAD_FAR_METERS = 160f
        const val ROAD_HALF_WIDTH = 32f
        const val ROAD_HALF_LENGTH = 40f
        const val ROAD_BEHIND_METERS = 12f
        /** Lateral metres per (metre ahead)^2 for a road curve of 1. */
        const val ROAD_CURVE_METERS = 0.03f
        const val MAX_ROAD_LANES = 6
        val DARK_ASPHALT = floatArrayOf(0.10f, 0.105f, 0.115f)
        val LIGHT_ASPHALT = floatArrayOf(0.80f, 0.81f, 0.83f)
        val DARK_LANE_LINE = floatArrayOf(0.85f, 0.86f, 0.88f)
        val LIGHT_LANE_LINE = floatArrayOf(0.31f, 0.35f, 0.39f)
        val ROAD_EDGE = floatArrayOf(0.90f, 0.29f, 0.31f)
        val DARK_ACTIVE_LANE = floatArrayOf(0.09f, 0.59f, 1f)
        val LIGHT_ACTIVE_LANE = floatArrayOf(0f, 0.47f, 0.85f)
        val DEFAULT_BASE = floatArrayOf(0.6f, 0.6f, 0.6f, 1f)
        val TINTED_GLASS = floatArrayOf(0.02f, 0.025f, 0.03f, 0.42f)
        val NO_EMISSION = floatArrayOf(0f, 0f, 0f)
        // Mismo color que el fondo del panel (DashboardTheme): la escena se funde con él.
        val DARK_BACKGROUND = floatArrayOf(0x05 / 255f, 0x06 / 255f, 0x07 / 255f)
        val LIGHT_BACKGROUND = floatArrayOf(0xF4 / 255f, 0xF2 / 255f, 0xEF / 255f)
        val DARK_SKY = floatArrayOf(0.55f, 0.6f, 0.7f)
        const val SUNSET_BANDS = 96
        /**
         * Posición (0 arriba, 1 abajo) y color. El parabrisas ocupa el tercio superior de la vista
         * interior: ahí van el malva y el naranja del horizonte; debajo, tras el salpicadero, oscuro.
         */
        val SUNSET_STOPS = arrayOf(
            floatArrayOf(0f, 0.10f, 0.10f, 0.26f),
            floatArrayOf(0.09f, 0.36f, 0.18f, 0.40f),
            floatArrayOf(0.18f, 0.88f, 0.42f, 0.30f),
            floatArrayOf(0.25f, 1.00f, 0.66f, 0.32f),
            floatArrayOf(0.33f, 0.20f, 0.12f, 0.12f),
            floatArrayOf(1f, 0.05f, 0.05f, 0.06f),
        )
        val DARK_GROUND = floatArrayOf(0.05f, 0.05f, 0.06f)
        val LIGHT_SKY = floatArrayOf(0.95f, 0.97f, 1f)
        val LIGHT_GROUND = floatArrayOf(0.35f, 0.35f, 0.37f)
        // sRGB floor colour at the centre of the spot (the background colour is reached at its edge).
        val DARK_FLOOR = floatArrayOf(0.2f, 0.205f, 0.225f)
        val LIGHT_FLOOR = floatArrayOf(1f, 1f, 1f)
        const val DARK_REFLECTION = 0.46f
        const val LIGHT_REFLECTION = 0.34f
        const val CUSTOM_FLOOR_DARK = 0.28f
        const val CUSTOM_FLOOR_LIGHT = 0.3f
        /** Disco de exposición, circular, algo mayor que el coche. */
        const val FLOOR_RADIUS_X = 0.98f
        const val FLOOR_RADIUS_Z = 0.98f
        /** El disco solo se suaviza en la última franja inferior de la imagen. */
        const val FLOOR_BOTTOM_FADE = 0.08f
        /** Altura (0 arriba, 1 abajo) donde la pared del estudio alcanza su tono más claro. */
        const val STUDIO_HORIZON = 0.55f
        const val STUDIO_WALL_DARK = 0.45f
        const val STUDIO_WALL_LIGHT = 0.9f
        const val REFLECTION_MAX_PITCH = 55f
        const val REFLECTION_FADE_DEGREES = 20f
        /** Mirrored depth (in car half-heights) over which the reflection fades out. */
        const val REFLECTION_DEPTH = 1.3f
        /** Bottom fraction of the frame where floor and reflection melt into the background. */
        const val BOTTOM_FADE = 0.4f
        /**
         * Cabin box in glTF world space (x right, y up, -z forward), measured on the V45 seats,
         * dashboard and headlining. The renderer has no shadow maps, so without this the interior
         * would be lit as if the car had no roof and the near-black upholstery would look grey.
         */
        val CABIN_MIN = floatArrayOf(-0.70f, 0.30f, -1.00f)
        val CABIN_MAX = floatArrayOf(0.70f, 1.35f, 1.45f)

        /**
         * Suelo elegido por el usuario (sRGB): en tema oscuro se atenúa para que el coche siga
         * destacando; en claro se aclara hacia blanco como el suelo de estudio.
         */
        /** Pared del estudio: algo más clara que el panel en oscuro y algo más gris que el suelo en claro. */
        fun studioWall(floor: FloatArray, background: FloatArray, dark: Boolean): FloatArray = FloatArray(3) {
            if (dark) background[it] + (floor[it] - background[it]) * STUDIO_WALL_DARK else floor[it] * STUDIO_WALL_LIGHT
        }

        fun customFloor(argb: Int, dark: Boolean): FloatArray = FloatArray(3) { i ->
            val c = ((argb shr (16 - 8 * i)) and 0xFF) / 255f
            if (dark) c * CUSTOM_FLOOR_DARK else 1f - (1f - c) * CUSTOM_FLOOR_LIGHT
        }

        fun srgbToLinear(argb: Int): FloatArray = floatArrayOf(
            channel(argb shr 16), channel(argb shr 8), channel(argb),
        )

        private fun channel(value: Int): Float {
            val c = (value and 0xff) / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }
}

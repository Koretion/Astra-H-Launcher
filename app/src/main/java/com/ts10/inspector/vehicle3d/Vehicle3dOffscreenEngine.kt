package com.ts10.inspector.vehicle3d

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ts10.inspector.diagnostics.TelemetryLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class Vehicle3dOffscreenState(
    val bitmap: Bitmap? = null,
    val status: String = "inactive",
    val frame: Long = 0,
    val renderMs: Long? = null,
    val error: String? = null,
)

/**
 * OpenGL ES 2.0 renderer of the Astra V45 GLB for the launcher.
 *
 * It renders exclusively into an EGL pbuffer and returns a Bitmap to Compose. It deliberately
 * never creates a SurfaceView, TextureView or window EGL surface, the paths which conflicted with
 * the TS10 compositor during the Filament laboratory tests. Frames are produced only while
 * something moves (parts, camera, wheels, indicators); a settled scene costs nothing.
 */
/**
 * Tamaño de la imagen 3D. Cubre la página del coche en el carrusel, desde la línea de la fecha hasta
 * los puntos (308 x 237 unidades). Parado solo se dibujan las [PARKED_HEIGHT] filas inferiores, con
 * la proporción del hueco visible bajo los botones (308 x 179); la franja superior queda del color
 * del fondo. Al conducir se usa toda la altura y la carretera sube por debajo de los botones.
 */
internal object Vehicle3dFrame {
    const val WIDTH = 480
    const val HEIGHT = 369
    const val PARKED_HEIGHT = 279
}

internal object Vehicle3dOffscreenEngine {
    const val MODEL_ASSET = "vehicle3d/astra_h_gtc_v45.glb"
    private const val WIDTH = Vehicle3dFrame.WIDTH
    private const val HEIGHT = Vehicle3dFrame.HEIGHT
    private const val FRAME_INTERVAL_MS = 50L
    private val mutableState = MutableStateFlow(Vehicle3dOffscreenState())
    val state = mutableState.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var renderer: OffscreenGlesRenderer? = null
    private val animator = Vehicle3dSceneAnimator()
    @Volatile private var input = Vehicle3dSceneInput()
    private var renderedInput: Vehicle3dSceneInput? = null
    private var loopScheduled = false
    private var lastTickMs = 0L
    private var frameNumber = 0L
    private var users = 0

    @Synchronized
    fun start(context: Context) {
        users++
        if (thread != null) return
        val appContext = context.applicationContext
        val renderThread = HandlerThread("TS10-vehicle-3d-offscreen", Process.THREAD_PRIORITY_DISPLAY)
            .apply { start() }
        thread = renderThread
        handler = Handler(renderThread.looper).also { target ->
            target.post {
                val started = SystemClock.elapsedRealtime()
                runCatching {
                    val model = Vehicle3dGlbModel.parse(appContext.assets.open(MODEL_ASSET).use { it.readBytes() })
                    OffscreenGlesRenderer(WIDTH, HEIGHT, model).also { renderer = it }
                }.onSuccess {
                    val loadMs = SystemClock.elapsedRealtime() - started
                    TelemetryLog.event("VEHICLE_3D_OFFSCREEN", "model loaded ms=$loadMs msaa=${it.multisampled}")
                    mutableState.value = Vehicle3dOffscreenState(status = "ready")
                    renderedInput = null
                    scheduleLoop()
                }.onFailure { failure("init", it) }
            }
        }
    }

    /** Publishes the latest vehicle state; the render thread animates towards it. */
    fun update(next: Vehicle3dSceneInput) {
        if (next == input) return
        input = next
        handler?.post { scheduleLoop() }
    }

    @Synchronized
    fun stop() {
        // The launcher activity and the overlay panel each start the engine; keep it running
        // until the last one releases it.
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        val renderThread = thread ?: return
        handler?.post {
            runCatching { renderer?.close() }
            renderer = null
            loopScheduled = false
            mutableState.value = mutableState.value.copy(status = "stopped")
            renderThread.quitSafely()
        }
        handler = null
        thread = null
    }

    /** Runs on the render thread. */
    private fun scheduleLoop() {
        if (loopScheduled || renderer == null) return
        loopScheduled = true
        lastTickMs = SystemClock.uptimeMillis()
        handler?.post(::tick)
    }

    private fun tick() {
        val activeRenderer = renderer
        if (activeRenderer == null) {
            loopScheduled = false
            return
        }
        val now = SystemClock.uptimeMillis()
        val current = input
        val animating = animator.step(current, (now - lastTickMs) / 1000f)
        lastTickMs = now
        if (animating || current != renderedInput) {
            try {
                val started = SystemClock.elapsedRealtimeNanos()
                val bitmap = activeRenderer.render(current, animator, vehicle3dLitNodes(current, now))
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                frameNumber++
                renderedInput = current
                mutableState.value = Vehicle3dOffscreenState(
                    bitmap = bitmap,
                    status = if (animating) "rendering" else "idle",
                    frame = frameNumber,
                    renderMs = elapsedMs,
                )
            } catch (error: Throwable) {
                loopScheduled = false
                failure("render", error)
                return
            }
        }
        if (animating) {
            handler?.postDelayed(::tick, FRAME_INTERVAL_MS)
        } else {
            loopScheduled = false
        }
    }

    private fun failure(stage: String, error: Throwable) {
        Log.e("Vehicle3dOffscreen", "$stage failed", error)
        TelemetryLog.event("VEHICLE_3D_OFFSCREEN", "$stage failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        runCatching { renderer?.close() }
        renderer = null
        mutableState.value = Vehicle3dOffscreenState(
            status = "fallback",
            error = "$stage: ${error.message ?: error.javaClass.simpleName}",
        )
    }
}

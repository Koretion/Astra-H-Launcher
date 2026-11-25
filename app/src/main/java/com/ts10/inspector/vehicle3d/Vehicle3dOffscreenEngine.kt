package com.ts10.inspector.vehicle3d

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.vehicle.TurnSignalFlash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class Vehicle3dOffscreenState(
    val bitmap: Bitmap? = null,
    val status: String = "inactive",
    val frame: Long = 0,
    val renderMs: Long? = null,
    val error: String? = null,
    val stats: Vehicle3dRenderStats = Vehicle3dRenderStats(),
)

/** Rendimiento del último minuto de animación. */
internal data class Vehicle3dRenderStats(
    val frames: Int = 0,
    val fps: Float = 0f,
    val renderAvgMs: Float = 0f,
    val renderP95Ms: Float = 0f,
    val renderMaxMs: Float = 0f,
    val drawAvgMs: Float = 0f,
    val readbackAvgMs: Float = 0f,
    val convertAvgMs: Float = 0f,
    val intervalAvgMs: Float = 0f,
)

/**
 * OpenGL ES 2.0 renderer of the Astra V45 GLB for the launcher.
 *
 * It renders off screen and returns a Bitmap to Compose: preferably a hardware bitmap wrapping an
 * in-process ImageReader buffer (no pixel copy), otherwise an EGL pbuffer read with glReadPixels.
 * It deliberately never creates a SurfaceView or TextureView, the paths which conflicted with the
 * TS10 compositor during the Filament laboratory tests. Frames are produced only while
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
    /** Caps how far ahead the indicator phase is judged when one frame was unusually slow. */
    private const val MAX_LAMP_LEAD_MS = 150L
    private const val STATS_WINDOW_MS = 60_000L
    // Un hueco mayor entre fotogramas se considera el comienzo de otra animación.
    private const val ANIMATION_GAP_MS = 500L
    private val mutableState = MutableStateFlow(Vehicle3dOffscreenState())
    val state = mutableState.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var renderer: OffscreenGlesRenderer? = null
    private var model: Vehicle3dGlbModel? = null
    private val animator = Vehicle3dSceneAnimator()
    @Volatile private var input = Vehicle3dSceneInput()
    private var renderedInput: Vehicle3dSceneInput? = null
    private var loopScheduled = false
    private var lastTickMs = 0L
    private var frameNumber = 0L
    private var lastRenderMs = 0L
    private val samples = ArrayDeque<FrameSample>()
    private var lastFrameStartedMs = 0L
    private var animationStartedMs = 0L
    private var animationFrames = 0
    private var animationRenderMs = 0L

    private class FrameSample(
        val atMs: Long,
        val renderMs: Float,
        val drawMs: Float,
        val readbackMs: Float,
        val convertMs: Float,
        val intervalMs: Float?,
    )
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
                    val parsed = Vehicle3dGlbModel.parse(appContext.assets.open(MODEL_ASSET).use { it.readBytes() })
                    model = parsed
                    OffscreenGlesRenderer(WIDTH, HEIGHT, parsed).also { renderer = it }
                }.onSuccess {
                    val loadMs = SystemClock.elapsedRealtime() - started
                    TelemetryLog.event("VEHICLE_3D_OFFSCREEN", "model loaded ms=$loadMs msaa=${it.multisampled} output=${it.outputPath}")
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

    private fun recordFrame(startedMs: Long, renderNs: Long, renderer: OffscreenGlesRenderer): Vehicle3dRenderStats {
        val renderMs = renderNs / 1_000_000f
        val continuing = lastFrameStartedMs != 0L && startedMs - lastFrameStartedMs <= ANIMATION_GAP_MS
        if (!continuing) {
            animationStartedMs = startedMs
            animationFrames = 0
            animationRenderMs = 0L
        }
        samples.addLast(
            FrameSample(
                atMs = startedMs,
                renderMs = renderMs,
                drawMs = renderer.lastDrawNs / 1_000_000f,
                readbackMs = renderer.lastReadbackNs / 1_000_000f,
                convertMs = renderer.lastConvertNs / 1_000_000f,
                intervalMs = if (continuing) (startedMs - lastFrameStartedMs).toFloat() else null,
            ),
        )
        lastFrameStartedMs = startedMs
        animationFrames++
        animationRenderMs += renderNs / 1_000_000L
        while (samples.isNotEmpty() && startedMs - samples.first().atMs > STATS_WINDOW_MS) samples.removeFirst()
        return computeStats()
    }

    private fun computeStats(): Vehicle3dRenderStats {
        if (samples.isEmpty()) return Vehicle3dRenderStats()
        val renders = samples.map { it.renderMs }.sorted()
        val intervals = samples.mapNotNull { it.intervalMs }
        val intervalAvg = if (intervals.isEmpty()) 0f else intervals.average().toFloat()
        return Vehicle3dRenderStats(
            frames = samples.size,
            fps = if (intervalAvg > 0f) 1000f / intervalAvg else 0f,
            renderAvgMs = renders.average().toFloat(),
            renderP95Ms = renders[((renders.size - 1) * 95) / 100],
            renderMaxMs = renders.last(),
            drawAvgMs = samples.map { it.drawMs }.average().toFloat(),
            readbackAvgMs = samples.map { it.readbackMs }.average().toFloat(),
            convertAvgMs = samples.map { it.convertMs }.average().toFloat(),
            intervalAvgMs = intervalAvg,
        )
    }

    /** Una línea por animación terminada, para comparar en los logs. */
    private fun finishAnimationSummary() {
        if (animationFrames < 2) return
        val durationMs = (lastFrameStartedMs - animationStartedMs).coerceAtLeast(1L)
        val stats = mutableState.value.stats
        fun f(value: Float) = "%.1f".format(java.util.Locale.US, value)
        TelemetryLog.event(
            "VEHICLE_3D_OFFSCREEN",
            "animation frames=$animationFrames durationMs=$durationMs " +
                "fps=${f((animationFrames - 1) * 1000f / durationMs)} " +
                "renderAvgMs=${animationRenderMs / animationFrames} windowP95Ms=${f(stats.renderP95Ms)} " +
                "drawMs=${f(stats.drawAvgMs)} readbackMs=${f(stats.readbackAvgMs)} convertMs=${f(stats.convertAvgMs)}",
        )
        animationFrames = 0
        lastFrameStartedMs = 0L
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
                // The lamp is judged for when this frame will be on screen, one render later.
                val lampOn = TurnSignalFlash.lampOn(
                    current.leftIndicator || current.rightIndicator,
                    SystemClock.elapsedRealtime() + lastRenderMs,
                )
                val bitmap = activeRenderer.render(current, animator, vehicle3dLitNodes(current, lampOn))
                val elapsedNs = SystemClock.elapsedRealtimeNanos() - started
                lastRenderMs = (elapsedNs / 1_000_000L).coerceAtMost(MAX_LAMP_LEAD_MS)
                frameNumber++
                renderedInput = current
                val stats = recordFrame(now, elapsedNs, activeRenderer)
                mutableState.value = Vehicle3dOffscreenState(
                    bitmap = bitmap,
                    status = if (animating) "rendering" else "idle",
                    frame = frameNumber,
                    renderMs = elapsedNs / 1_000_000L,
                    stats = stats,
                )
            } catch (error: Throwable) {
                if (activeRenderer.hardwareOutput && fallBackToReadback(activeRenderer, error)) {
                    handler?.post(::tick)
                    return
                }
                loopScheduled = false
                failure("render", error)
                return
            }
        }
        if (animating) {
            // Cadencia fija: el intervalo se cuenta desde el inicio de este fotograma, no desde que
            // termina el render; si el render lo supera, el siguiente se encadena sin espera.
            var delay = (FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - now)).coerceAtLeast(0L)
            // With the indicators on, also wake right before the real lamps switch.
            if (current.leftIndicator || current.rightIndicator) {
                val untilSwitch = TurnSignalFlash.msUntilChange(SystemClock.elapsedRealtime() + lastRenderMs)
                delay = minOf(delay, untilSwitch)
            }
            handler?.postDelayed(::tick, delay)
        } else {
            loopScheduled = false
            finishAnimationSummary()
        }
    }

    /** Output path of the running renderer, for the diagnostic report. */
    fun outputPath(): String = renderer?.outputPath ?: "--"

    /** A driver that accepts the ImageReader surface but fails on it keeps the 3D car via glReadPixels. */
    private fun fallBackToReadback(failed: OffscreenGlesRenderer, error: Throwable): Boolean {
        val parsed = model ?: return false
        TelemetryLog.event(
            "VEHICLE_3D_OFFSCREEN",
            "hardware output failed, using glReadPixels: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
        )
        runCatching { failed.close() }
        renderer = null
        return runCatching { OffscreenGlesRenderer(WIDTH, HEIGHT, parsed, preferHardwareOutput = false) }
            .onSuccess {
                renderer = it
                renderedInput = null
            }
            .isSuccess
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

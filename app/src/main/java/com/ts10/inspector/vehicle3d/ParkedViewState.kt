package com.ts10.inspector.vehicle3d

import android.os.SystemClock
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.LauncherState
import com.ts10.inspector.vision.LocalVisionObjectDetector
import com.ts10.inspector.vision.RoadObjectKind
import com.ts10.inspector.vision.TrafficLightColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** Señales que deciden si una detención es una parada real. */
internal data class ParkedViewSignals(
    val speedKmh: Float,
    val handbrake: Boolean = false,
    val anyOpening: Boolean = false,
    val redLightAhead: Boolean = false,
)

/**
 * Evita que la vista pase a "coche parado" en un stop o paso de cebra: tras detenerse espera
 * [STOP_HOLD_MS] (o [RED_LIGHT_HOLD_MS] si se ha visto un semáforo en rojo durante la detención).
 * Freno de mano o una puerta/capó/maletero abiertos confirman la parada al instante.
 * Al arrancar la app sin haber circulado se considera parado directamente. No es thread-safe.
 */
internal class ParkedViewDebouncer {
    private var parked = true
    private var stopStartMs: Long? = null
    private var redLightSeen = false

    /** Devuelve el estado actual y, si sigue pendiente, el instante en que se debe reevaluar. */
    fun update(signals: ParkedViewSignals, nowMs: Long): Pair<Boolean, Long?> {
        if (signals.speedKmh >= VEHICLE_3D_DRIVING_KMH) {
            parked = false
            stopStartMs = null
            redLightSeen = false
            return false to null
        }
        if (parked) return true to null
        if (signals.handbrake || signals.anyOpening) {
            parked = true
            return true to null
        }
        val start = stopStartMs ?: nowMs.also { stopStartMs = it }
        if (signals.redLightAhead) redLightSeen = true
        val deadline = start + if (redLightSeen) RED_LIGHT_HOLD_MS else STOP_HOLD_MS
        if (nowMs >= deadline) {
            parked = true
            return true to null
        }
        return false to deadline
    }

    companion object {
        const val STOP_HOLD_MS = 10_000L
        const val RED_LIGHT_HOLD_MS = 30_000L
    }
}

/** Estado compartido "vista de coche parado" para la escena 3D, el foco de Ajustes y el límite. */
internal object ParkedViewState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableParked = MutableStateFlow(true)
    val parked: StateFlow<Boolean> = mutableParked.asStateFlow()

    init {
        val debouncer = ParkedViewDebouncer()
        scope.launch {
            combine(
                LauncherRuntime.state,
                LocalVisionObjectDetector.state,
                GrabadoraHdCameraClient.state,
            ) { launcher, vision, camera ->
                ParkedViewSignals(
                    speedKmh = launcher.speedKmh.toFloat(),
                    handbrake = launcher.handbrake,
                    anyOpening = launcher.anyOpening(),
                    // Posición 1 es la cámara trasera: un semáforo ahí no cuenta.
                    redLightAhead = camera.selectedPosition != 1 && vision.trackedObjects.any {
                        it.kind == RoadObjectKind.TRAFFIC_LIGHT && it.trafficLightColor == TrafficLightColor.RED
                    },
                )
            }
                .distinctUntilChanged()
                .transformLatest { signals ->
                    var deadline: Long?
                    do {
                        val (value, next) = debouncer.update(signals, SystemClock.elapsedRealtime())
                        emit(value)
                        deadline = next
                        if (next != null) delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                    } while (deadline != null)
                }
                .collect { mutableParked.value = it }
        }
    }

    private fun LauncherState.anyOpening() = frontLeftDoorOpen || frontRightDoorOpen ||
        rearLeftDoorOpen || rearRightDoorOpen || trunkOpen || bonnetOpen
}

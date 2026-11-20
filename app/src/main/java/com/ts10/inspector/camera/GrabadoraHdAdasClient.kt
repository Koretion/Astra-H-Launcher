package com.ts10.inspector.camera

import androidx.annotation.StringRes
import com.ts10.inspector.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import com.ts10.inspector.diagnostics.appendDiagnosticEvent
import com.ts10.inspector.diagnostics.diagnosticEventTime
import com.ts10.inspector.diagnostics.incrementDiagnosticCount
import com.ts10.inspector.vehicle.fuel.distanceMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small Binder client for the exported ADAS service included in Grabadora HD.
 *
 * The protocol is intentionally implemented with Parcel instead of copying the APK's
 * obfuscated classes into this project. Grabadora HD remains responsible for the camera,
 * native detector and licence; the launcher only consumes its scene data.
 */
internal object GrabadoraHdAdasClient {
    private val mutableState = MutableStateFlow(AdasSceneState())
    val state: StateFlow<AdasSceneState> = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var applicationContext: Context? = null
    private var service: IBinder? = null
    private var binding = false
    private var started = false
    private var staleGeneration = 0L

    private val warningCallback = WarningCallback()
    private val resultCallback = ResultCallback()
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            service = binder
            runCatching {
                binder.linkToDeath(::handleServiceLoss, 0)
                registerCallback(binder, TRANSACTION_REGISTER_WARNING_CALLBACK, warningCallback)
                registerCallback(binder, TRANSACTION_REGISTER_RESULT_CALLBACK, resultCallback)
                val available = queryAvailable(binder)
                mutableState.value = mutableState.value.copy(
                    connected = true,
                    available = available,
                    warningCallbackRegistered = true,
                    resultCallbackRegistered = true,
                    status = if (available) "ADAS connected" else "ADAS waiting for camera/calibration",
                )
                Log.i(TAG, "Grabadora HD ADAS connected; available=$available")
            }.onFailure { error ->
                Log.w(TAG, "Unable to initialise Grabadora HD ADAS Binder", error)
                handleServiceLoss()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = handleServiceLoss()

        override fun onBindingDied(name: ComponentName) = handleServiceLoss()

        override fun onNullBinding(name: ComponentName) = handleServiceLoss()
    }

    fun start(context: Context) {
        applicationContext = context.applicationContext
        if (started && (service != null || binding)) return
        started = true
        bind()
    }

    private fun bind() {
        val context = applicationContext ?: return
        if (binding || service != null) return
        binding = true
        mutableState.value = mutableState.value.copy(status = "Conectando ADAS")
        val intent = Intent(ADAS_SERVICE_ACTION)
            .setComponent(ComponentName(GRABADORA_PACKAGE, ADAS_SERVICE_CLASS))
            .setPackage(GRABADORA_PACKAGE)
            .putExtra(EXTRA_SERVICE_PACKAGE, GRABADORA_PACKAGE)
            .putExtra(EXTRA_PROXY_PACKAGE, context.packageName)
            .putExtra(EXTRA_PROXY_PID, android.os.Process.myPid())
        val accepted = runCatching {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            Log.w(TAG, "Grabadora HD ADAS bind failed", error)
            false
        }
        if (!accepted) {
            binding = false
            mutableState.value = AdasSceneState(status = "Grabadora HD unavailable")
            scheduleReconnect()
        }
    }

    private fun handleServiceLoss() {
        service = null
        binding = false
        mutableState.value = mutableState.value.copy(
            connected = false,
            available = false,
            warningCallbackRegistered = false,
            resultCallbackRegistered = false,
            lane = null,
            vehicles = emptyList(),
            status = "ADAS disconnected",
        )
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val reconnectRunnable = Runnable(::bind)

    private fun registerCallback(binder: IBinder, transaction: Int, callback: IInterface) {
        transact(binder, transaction) { data ->
            data.writeInt(ADAS_CHANNEL)
            data.writeStrongBinder(callback.asBinder())
        }.recycle()
    }

    private fun queryAvailable(binder: IBinder): Boolean {
        val reply = transact(binder, TRANSACTION_AVAILABLE) { it.writeInt(ADAS_CHANNEL) }
        return try {
            reply.readInt() != 0
        } finally {
            reply.recycle()
        }
    }

    private fun transact(
        binder: IBinder,
        transaction: Int,
        write: (Parcel) -> Unit,
    ): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ADAS_SERVICE_DESCRIPTOR)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) {
                throw RemoteException("ADAS transaction $transaction was rejected")
            }
            reply.readException()
            return reply
        } catch (error: Throwable) {
            reply.recycle()
            throw error
        } finally {
            data.recycle()
        }
    }

    @Synchronized
    private fun recordCallbackShape(channel: String, code: Int, availableBytes: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(
            callbackShapes = incrementDiagnosticCount(
                current.callbackShapes,
                "$channel:$code:$availableBytes",
            ),
        )
    }

    @Synchronized
    private fun recordCallbackError(channel: String, code: Int, error: Throwable) {
        val current = mutableState.value
        mutableState.value = current.copy(
            callbackErrors = appendDiagnosticEvent(
                current.callbackErrors,
                "${diagnosticEventTime()} $channel code=$code ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                MAX_DIAGNOSTIC_EVENTS,
            ),
        )
    }

    @Synchronized
    private fun updateWarning(type: Int, activeValue: Int, configurationMask: Int) {
        val warning = AdasWarning.from(type)
        val current = mutableState.value
        mutableState.value = current.copy(
            lastWarning = warning,
            warningActive = activeValue != 0,
            warningEventCount = current.warningEventCount + 1,
            warningHistory = appendDiagnosticEvent(
                current.warningHistory,
                "${diagnosticEventTime()} type=$type label=${warning.label} active=$activeValue mask=$configurationMask",
                MAX_DIAGNOSTIC_EVENTS,
            ),
            status = if (activeValue != 0) warning.label else "ADAS connected",
        )
    }

    private fun updateScene(
        status: RawAdasStatus?,
        lane: RawAdasResult?,
        detectedVehicles: List<RawAdasResult>,
    ) {
        val laneGeometry = lane?.takeIf(RawAdasResult::hasGeometry)?.toLaneGeometry()
        val vehicles = detectedVehicles
            .asSequence()
            .filter(RawAdasResult::hasGeometry)
            .map { it.toDetectedVehicle() }
            .sortedByDescending(AdasDetectedVehicle::distanceMeters)
            .take(MAX_VISIBLE_VEHICLES)
            .toList()
        val now = SystemClock.elapsedRealtime()
        val current = mutableState.value
        val sample = "${diagnosticEventTime()} status=${status?.width}x${status?.height} " +
            "calibrating=${status?.calibrating} lane=${laneGeometry?.detectedBoundaryCount ?: 0} " +
            "vehicles=${vehicles.size} distances=${vehicles.joinToString { "%.1f".format(it.distanceMeters) }}"
        mutableState.value = current.copy(
            connected = true,
            available = true,
            calibrated = status?.calibrating == false,
            logicalWidth = status?.width?.takeIf { it > 0 } ?: ADAS_LOGICAL_WIDTH.toInt(),
            logicalHeight = status?.height?.takeIf { it > 0 } ?: ADAS_LOGICAL_HEIGHT.toInt(),
            lane = laneGeometry,
            vehicles = vehicles,
            lastFrameElapsedRealtime = now,
            resultFrameCount = current.resultFrameCount + 1,
            structuredSamples = appendDiagnosticEvent(
                current.structuredSamples,
                sample,
                MAX_STRUCTURED_SAMPLES,
            ),
            status = "ADAS activo",
        )
        val generation = ++staleGeneration
        mainHandler.postDelayed(
            {
                if (generation == staleGeneration) {
                    mutableState.value = mutableState.value.copy(
                        lane = null,
                        vehicles = emptyList(),
                        status = "ADAS without recent detections",
                    )
                }
            },
            RESULT_STALE_MS,
        )
    }

    private class WarningCallback : Binder(), IInterface {
        init {
            attachInterface(this, ADAS_WARNING_CALLBACK_DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(ADAS_WARNING_CALLBACK_DESCRIPTOR)
                return true
            }
            if (code in 1..0x00ffffff) data.enforceInterface(ADAS_WARNING_CALLBACK_DESCRIPTOR)
            val availableBytes = data.dataAvail()
            recordCallbackShape("warning", code, availableBytes)
            return runCatching { when (code) {
                WARNING_CALLBACK_TRANSACTION -> {
                    val type = data.readInt()
                    val active = data.readInt()
                    val configurationMask = data.readInt()
                    updateWarning(type, active, configurationMask)
                    true
                }

                in 1..8 -> true
                else -> super.onTransact(code, data, reply, flags)
            } }.getOrElse { error ->
                recordCallbackError("warning", code, error)
                true
            }
        }
    }

    private class ResultCallback : Binder(), IInterface {
        init {
            attachInterface(this, ADAS_RESULT_CALLBACK_DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(ADAS_RESULT_CALLBACK_DESCRIPTOR)
                return true
            }
            if (code in 1..0x00ffffff) data.enforceInterface(ADAS_RESULT_CALLBACK_DESCRIPTOR)
            val availableBytes = data.dataAvail()
            recordCallbackShape("result", code, availableBytes)
            if (code != RESULT_CALLBACK_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            return runCatching {
                val status = if (data.readInt() != 0) data.readRawStatus() else null
                val lane = if (data.readInt() != 0) data.readRawResult() else null
                val count = data.readInt().coerceIn(0, MAX_PARCEL_RESULTS)
                val vehicles = buildList {
                    repeat(count) {
                        if (data.readInt() != 0) add(data.readRawResult())
                    }
                }
                updateScene(status, lane, vehicles)
                true
            }.getOrElse { error ->
                recordCallbackError("result", code, error)
                true
            }
        }
    }

    private fun Parcel.readRawStatus() = RawAdasStatus(
        calibrating = readInt() != 0,
        width = readInt(),
        height = readInt(),
        vanishingX = readFloat(),
        vanishingY = readFloat(),
    )

    private fun Parcel.readRawResult() = RawAdasResult(
        points = List(4) { AdasPoint(readInt().toFloat(), readInt().toFloat()) },
        width = readFloat(),
        height = readFloat(),
        state = readInt(),
        distance = readFloat(),
    )

    private fun RawAdasResult.toLaneGeometry(): AdasLaneGeometry? {
        // Grabadora HD parcels the two points of the left edge as 0/3 and the right
        // edge as 1/2. Either edge can be absent, so preserve them independently instead
        // of turning missing (0, 0) points into a false lane line.
        return AdasLaneGeometry(
            left = detectedAdasLaneBoundary(points[0], points[3]),
            right = detectedAdasLaneBoundary(points[1], points[2]),
            state = state,
        ).takeIf { it.detectedBoundaryCount > 0 }
    }

    private fun RawAdasResult.toDetectedVehicle(): AdasDetectedVehicle {
        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        val derivedDistance = 5f + (1f - centerY / ADAS_LOGICAL_HEIGHT) * 75f
        return AdasDetectedVehicle(
            horizontalPosition = ((centerX / ADAS_LOGICAL_WIDTH) - 0.5f) * 2f,
            distanceMeters = (if (distance > 0f) distance else derivedDistance).coerceIn(2f, 120f),
            confidenceState = state,
        )
    }

    private data class RawAdasStatus(
        val calibrating: Boolean,
        val width: Int,
        val height: Int,
        val vanishingX: Float,
        val vanishingY: Float,
    )

    private data class RawAdasResult(
        val points: List<AdasPoint>,
        val width: Float,
        val height: Float,
        val state: Int,
        val distance: Float,
    ) {
        fun hasGeometry(): Boolean = points.any { it.x != 0f || it.y != 0f }
    }

    private const val TAG = "GrabadoraHdAdas"
    private const val GRABADORA_PACKAGE = "com.ankai.cardvr"
    private const val ADAS_SERVICE_CLASS = "git.my.adas.service.AdasService"
    private const val ADAS_SERVICE_ACTION = "git.my.adas.base.IAdasService"
    private const val ADAS_SERVICE_DESCRIPTOR = "git.my.adas.base.IAdasService"
    private const val ADAS_WARNING_CALLBACK_DESCRIPTOR = "git.my.adas.base.IAdasServiceCallback"
    private const val ADAS_RESULT_CALLBACK_DESCRIPTOR = "git.my.adas.base.IAdasServiceResultCallback"
    private const val EXTRA_SERVICE_PACKAGE = "service_package_name"
    private const val EXTRA_PROXY_PACKAGE = "proxy_package_name"
    private const val EXTRA_PROXY_PID = "proxy_calling_pid"
    private const val ADAS_CHANNEL = 1
    private const val TRANSACTION_REGISTER_WARNING_CALLBACK = 3
    private const val TRANSACTION_AVAILABLE = 10
    private const val TRANSACTION_REGISTER_RESULT_CALLBACK = 18
    private const val WARNING_CALLBACK_TRANSACTION = 7
    private const val RESULT_CALLBACK_TRANSACTION = 1
    private const val ADAS_LOGICAL_WIDTH = 800f
    private const val ADAS_LOGICAL_HEIGHT = 450f
    private const val MAX_VISIBLE_VEHICLES = 8
    private const val MAX_PARCEL_RESULTS = 32
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val RESULT_STALE_MS = 1_500L
    private const val MAX_DIAGNOSTIC_EVENTS = 80
    private const val MAX_STRUCTURED_SAMPLES = 30
}

@Immutable
internal data class AdasSceneState(
    val connected: Boolean = false,
    val available: Boolean = false,
    val warningCallbackRegistered: Boolean = false,
    val resultCallbackRegistered: Boolean = false,
    val calibrated: Boolean = false,
    val logicalWidth: Int = 800,
    val logicalHeight: Int = 450,
    val lane: AdasLaneGeometry? = null,
    val vehicles: List<AdasDetectedVehicle> = emptyList(),
    val lastWarning: AdasWarning = AdasWarning.NONE,
    val warningActive: Boolean = false,
    val warningEventCount: Long = 0L,
    val resultFrameCount: Long = 0L,
    val lastFrameElapsedRealtime: Long = 0L,
    val callbackShapes: Map<String, Long> = emptyMap(),
    val callbackErrors: List<String> = emptyList(),
    val warningHistory: List<String> = emptyList(),
    val structuredSamples: List<String> = emptyList(),
    val status: String = "ADAS not connected",
)

@Immutable
internal data class AdasPoint(val x: Float, val y: Float)

@Immutable
internal data class AdasLaneBoundary(
    val far: AdasPoint,
    val near: AdasPoint,
)

internal fun detectedAdasLaneBoundary(
    first: AdasPoint,
    second: AdasPoint,
): AdasLaneBoundary? {
    if (!first.isDetectedAdasPoint() || !second.isDetectedAdasPoint()) return null
    val sorted = listOf(first, second).sortedBy(AdasPoint::y)
    return AdasLaneBoundary(far = sorted.first(), near = sorted.last())
}

private fun AdasPoint.isDetectedAdasPoint(): Boolean = x != 0f || y != 0f

@Immutable
internal data class AdasLaneGeometry(
    val left: AdasLaneBoundary?,
    val right: AdasLaneBoundary?,
    val state: Int,
) {
    val detectedBoundaryCount: Int
        get() = (if (left != null) 1 else 0) + (if (right != null) 1 else 0)
}

@Immutable
internal data class AdasDetectedVehicle(
    val horizontalPosition: Float,
    val distanceMeters: Float,
    val confidenceState: Int,
)

/**
 * This Grabadora HD build emits warning callbacks even when its structured result callback is
 * silent. Only explicit vehicle warnings are safe to turn into a generic centre-lane object;
 * lane-departure warnings must never fabricate a vehicle.
 */
internal fun AdasSceneState.withWarningVehicleFallback(): AdasSceneState {
    if (vehicles.isNotEmpty() || !warningActive) return this
    val distance = when (lastWarning) {
        AdasWarning.FCW -> 18f
        AdasWarning.FVSA -> 28f
        AdasWarning.VB -> 35f
        else -> return this
    }
    return copy(
        vehicles = listOf(
            AdasDetectedVehicle(
                horizontalPosition = 0f,
                distanceMeters = distance,
                confidenceState = lastWarning.value,
            ),
        ),
    )
}

// `label` va a los registros en inglés; `text` es la etiqueta traducida de la interfaz.
internal enum class AdasWarning(val value: Int, val label: String, @StringRes val text: Int) {
    NONE(-1, "No warnings", R.string.adas_none),
    LDW(0, "Lane departure", R.string.adas_ldw),
    LDW_LEFT(1, "Lane departure left", R.string.adas_ldw_left),
    LDW_RIGHT(2, "Lane departure right", R.string.adas_ldw_right),
    FCW(3, "Forward collision risk", R.string.adas_fcw),
    FVSA(4, "Vehicle ahead moving off", R.string.adas_fvsa),
    VB(5, "Vehicle warning", R.string.adas_vb);

    companion object {
        fun from(value: Int): AdasWarning = entries.firstOrNull { it.value == value } ?: NONE
    }
}

package com.ts10.inspector.camera

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.Immutable
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.diagnostics.appendDiagnosticEvent
import com.ts10.inspector.diagnostics.diagnosticEventTime
import com.ts10.inspector.diagnostics.incrementDiagnosticCount
import com.ts10.inspector.settings.LocalVisionSource
import com.ts10.inspector.vision.GrabadoraHdSurfaceBridge
import com.ts10.inspector.vision.GrabadoraHdVisionProbe
import com.ts10.inspector.vision.LocalVisionPipeline
import com.ts10.inspector.vision.grabadoraSurfaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only diagnostic client for Grabadora HD's exported camera service.
 *
 * The ADAS result callback in AdasService is present but unused in Grabadora HD
 * 20260608.2.0. MyCameraService publishes lower-level MyTarget messages instead, so
 * this client retains a small set of raw samples until their command mapping can be
 * confirmed on the real TS10.
 */
internal object GrabadoraHdCameraClient {
    private val mutableState = MutableStateFlow(GrabadoraHdCameraState())
    val state: StateFlow<GrabadoraHdCameraState> = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callback = CameraCallback()
    private var applicationContext: Context? = null
    private var service: IBinder? = null
    private var binding = false
    private var started = false
    private var registeredDeviceName: String? = null
    private val clientId = ("ts10-adas-targets-" + android.os.Process.myPid()).hashCode()
    private val surfaceBridge = object : GrabadoraHdSurfaceBridge {
        override fun attach(cameraId: Int, surface: Surface) {
            attachVisionSurface(cameraId, surface)
        }

        override fun detach(cameraId: Int, surface: Surface) {
            detachVisionSurface(cameraId, surface)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            service = binder
            TelemetryLog.event("CAMERA_SERVICE", "connected component=$name")
            runCatching {
                binder.linkToDeath(::handleServiceLoss, 0)
                registerForCameraTargets(binder)
            }.onFailure { error ->
                Log.w(TAG, "Unable to initialise Grabadora HD camera diagnostics", error)
                TelemetryLog.event(
                    "CAMERA_SERVICE",
                    "initialization error=${error.javaClass.simpleName}:${error.message.orEmpty()}",
                )
                mutableState.value = mutableState.value.copy(
                    connected = true,
                    callbackRegistered = false,
                    status = "Camera service without callback: ${error.javaClass.simpleName}",
                )
                scheduleReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = handleServiceLoss()

        override fun onBindingDied(name: ComponentName) = handleServiceLoss()

        override fun onNullBinding(name: ComponentName) = handleServiceLoss()
    }

    fun start(context: Context) {
        applicationContext = context.applicationContext
        LocalVisionPipeline.start(context.applicationContext)
        if (started && (service != null || binding)) return
        started = true
        bind()
    }

    private fun bind() {
        val context = applicationContext ?: return
        if (binding || service != null) return
        binding = true
        TelemetryLog.event("CAMERA_SERVICE", "bind requested")
        mutableState.value = mutableState.value.copy(status = "Connecting to the camera service")
        val intent = Intent(CAMERA_SERVICE_ACTION)
            .setComponent(ComponentName(GRABADORA_PACKAGE, CAMERA_SERVICE_CLASS))
            .setPackage(GRABADORA_PACKAGE)
            .putExtra(EXTRA_SERVICE_PACKAGE, GRABADORA_PACKAGE)
            .putExtra(EXTRA_PROXY_PACKAGE, context.packageName)
            .putExtra(EXTRA_PROXY_PID, android.os.Process.myPid())
        val accepted = runCatching {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            Log.w(TAG, "Grabadora HD camera bind failed", error)
            false
        }
        if (!accepted) {
            TelemetryLog.event("CAMERA_SERVICE", "bind rejected")
            binding = false
            mutableState.value = mutableState.value.copy(
                connected = false,
                callbackRegistered = false,
                status = "Camera service unavailable",
            )
            scheduleReconnect()
        }
    }

    private fun registerForCameraTargets(binder: IBinder) {
        // Grabadora HD runs with broader USB visibility than a regular application on the TS10.
        // Its Binder contract deliberately accepts an empty name and resolves the first active
        // camera internally, so do not block registration when UsbManager hides that device.
        val detectedDeviceName = findUsbCamera()?.deviceName
        val serviceDeviceName = grabadoraCameraDeviceName(detectedDeviceName)
        val inventory = usbInventory()
        val beforeRegistration = mutableState.value
        TelemetryLog.event(
            "CAMERA_SERVICE",
            "registration attempt=${beforeRegistration.registrationAttempts + 1} " +
                "visible=${detectedDeviceName ?: "none"} devices=${inventory.size}",
        )
        mutableState.value = beforeRegistration.copy(
            registrationAttempts = beforeRegistration.registrationAttempts + 1,
            usbInventory = inventory,
            registrationHistory = appendDiagnosticEvent(
                beforeRegistration.registrationHistory,
                "${diagnosticEventTime()} attempt=${beforeRegistration.registrationAttempts + 1} " +
                    "visible=${detectedDeviceName ?: "none"} devices=${inventory.size}",
                MAX_DIAGNOSTIC_EVENTS,
            ),
        )
        transact(binder, TRANSACTION_CONNECT_CAMERA) { data ->
            data.writeInt(clientId)
            data.writeString(serviceDeviceName)
            data.writeStrongBinder(callback)
        }
        registeredDeviceName = serviceDeviceName
        mutableState.value = mutableState.value.copy(
            connected = true,
            callbackRegistered = true,
            deviceName = detectedDeviceName ?: SERVICE_DEFAULT_CAMERA_LABEL,
            status = if (detectedDeviceName == null) {
                "Callback registered; camera resolved by Grabadora HD"
            } else {
                "Capturing camera targets"
            },
            registrationHistory = appendDiagnosticEvent(
                mutableState.value.registrationHistory,
                "${diagnosticEventTime()} registered=${detectedDeviceName ?: "service-default"}",
                MAX_DIAGNOSTIC_EVENTS,
            ),
        )
        Log.i(TAG, "Grabadora HD camera target callback registered for ${detectedDeviceName ?: "service default"}")
        TelemetryLog.event(
            "CAMERA_SERVICE",
            "registered device=${detectedDeviceName ?: "service-default"}",
        )
    }

    private fun findUsbCamera(): UsbDevice? {
        val context = applicationContext ?: return null
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return manager.deviceList.values.firstOrNull(::looksLikeUsbCamera)
    }

    private fun usbInventory(): List<String> {
        val context = applicationContext ?: return emptyList()
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values.map { describeUsbDevice(manager, it) }.sorted()
    }

    private fun looksLikeUsbCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private fun transact(binder: IBinder, transaction: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CAMERA_SERVICE_DESCRIPTOR)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) {
                throw RemoteException("Camera transaction $transaction was rejected")
            }
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun handleServiceLoss() {
        TelemetryLog.event("CAMERA_SERVICE", "service lost; reconnect scheduled")
        GrabadoraHdVisionProbe.stop("Camera service disconnected")
        service = null
        binding = false
        registeredDeviceName = null
        mutableState.value = mutableState.value.copy(
            connected = false,
            callbackRegistered = false,
            cameraId = null,
            status = "Camera service disconnected",
        )
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val reconnectRunnable = Runnable {
        val binder = service
        if (binder == null) {
            bind()
        } else if (!mutableState.value.callbackRegistered || mutableState.value.cameraId == null) {
            runCatching { registerForCameraTargets(binder) }
                .onFailure {
                    Log.w(TAG, "Grabadora HD camera callback retry failed", it)
                    scheduleReconnect()
                }
                .onSuccess {
                    if (mutableState.value.cameraId == null) scheduleReconnect()
                }
        }
    }

    private fun updateConnectionEvent(
        cameraId: Int,
        event: Int,
        value: Int,
        message: String?,
    ) {
        TelemetryLog.event(
            "CAMERA_SERVICE",
            "connection camera=$cameraId event=$event value=$value message=${message.orEmpty()}",
        )
        if (cameraId <= 0) {
            val current = mutableState.value
            mutableState.value = current.copy(
                cameraId = null,
                lastCallbackCode = event,
                connectionHistory = appendDiagnosticEvent(
                    current.connectionHistory,
                    "${diagnosticEventTime()} camera=$cameraId event=$event value=$value message=${message.orEmpty()}",
                    MAX_DIAGNOSTIC_EVENTS,
                ),
                status = "Service connected; waiting for the real camera",
            )
            // camera=0 is the service's no-device placeholder. The logs show that attaching a
            // Surface to it never yields frames, so keep asking the service until the real ID is
            // advertised again.
            scheduleReconnect()
            return
        }
        mainHandler.removeCallbacks(reconnectRunnable)
        ensureVisionProbe(cameraId)
        val current = mutableState.value
        mutableState.value = current.copy(
            cameraId = cameraId,
            lastCallbackCode = event,
            connectionHistory = appendDiagnosticEvent(
                current.connectionHistory,
                "${diagnosticEventTime()} camera=$cameraId event=$event value=$value message=${message.orEmpty()}",
                MAX_DIAGNOSTIC_EVENTS,
            ),
            status = message?.takeIf(String::isNotBlank)
                ?: "Camera callback $event ($value)",
        )
    }

    private fun updateTargets(targets: List<GrabadoraHdCameraTarget>, batch: Boolean) {
        val current = mutableState.value
        if (targets.isEmpty()) {
            mutableState.value = current.copy(
                targetCallbackCount = current.targetCallbackCount + 1,
                emptyTargetCallbackCount = current.emptyTargetCallbackCount + 1,
            )
            return
        }
        ensureVisionProbe(targets.first().cameraId)
        val retained = (targets + current.samples)
            .distinctBy(GrabadoraHdCameraTarget::signature)
            .take(MAX_RETAINED_TARGETS)
        mutableState.value = current.copy(
            connected = true,
            callbackRegistered = true,
            cameraId = targets.first().cameraId,
            targetCallbackCount = current.targetCallbackCount + 1,
            targetEventCount = current.targetEventCount + targets.size,
            targetBatchCount = current.targetBatchCount + if (batch) 1 else 0,
            lastTargetElapsedRealtime = SystemClock.elapsedRealtime(),
            samples = retained,
            status = "${targets.size} target(s) received",
        )
    }

    @Synchronized
    private fun recordCallbackShape(code: Int, availableBytes: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(
            callbackShapes = incrementDiagnosticCount(current.callbackShapes, "$code:$availableBytes"),
        )
    }

    @Synchronized
    private fun recordCallbackError(code: Int, error: Throwable) {
        TelemetryLog.event(
            "CAMERA_SERVICE",
            "callback error code=$code ${error.javaClass.simpleName}:${error.message.orEmpty()}",
        )
        val current = mutableState.value
        mutableState.value = current.copy(
            callbackErrors = appendDiagnosticEvent(
                current.callbackErrors,
                "${diagnosticEventTime()} code=$code ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                MAX_DIAGNOSTIC_EVENTS,
            ),
        )
    }

    private fun ensureVisionProbe(cameraId: Int) {
        if (cameraId < 0 || service == null) return
        if (LocalVisionPipeline.source != LocalVisionSource.DASHCAM) return
        GrabadoraHdVisionProbe.start(cameraId, surfaceBridge)
    }

    /** Releases only our secondary Surface; Grabadora HD keeps owning and recording its camera. */
    fun pauseVisionProbeForDirectUsb() {
        GrabadoraHdVisionProbe.stop("Surface paused: vision from USB camera")
    }

    /** Restores the dashcam acquisition path when the USB vision source stops. */
    fun resumeVisionProbeAfterDirectUsb() {
        mutableState.value.cameraId?.let(::ensureVisionProbe)
    }

    fun restartVisionProbeForCaptureMode() {
        val activeCamera = mutableState.value.cameraId ?: return
        if (LocalVisionPipeline.source != LocalVisionSource.DASHCAM) return
        GrabadoraHdVisionProbe.stop("Capture format changed")
        GrabadoraHdVisionProbe.start(activeCamera, surfaceBridge)
    }

    private fun attachVisionSurface(cameraId: Int, surface: Surface) {
        val binder = service ?: throw RemoteException("Camera service disconnected")
        val surfaceId = grabadoraSurfaceId(clientId, surface.hashCode())
        transact(binder, TRANSACTION_ADD_SURFACE) { data ->
            data.writeInt(clientId)
            data.writeInt(cameraId)
            data.writeInt(LIVE_PREVIEW_SURFACE_MODE)
            data.writeInt(surfaceId)
            data.writeInt(1)
            surface.writeToParcel(data, 0)
        }
        Log.i(TAG, "Vision probe Surface attached: camera=$cameraId id=$surfaceId")
        TelemetryLog.event("CAMERA_SERVICE", "surface attached camera=$cameraId id=$surfaceId")
    }

    private fun detachVisionSurface(cameraId: Int, surface: Surface) {
        val binder = service ?: return
        val surfaceId = grabadoraSurfaceId(clientId, surface.hashCode())
        transact(binder, TRANSACTION_REMOVE_SURFACE) { data ->
            data.writeInt(clientId)
            data.writeInt(cameraId)
            data.writeInt(LIVE_PREVIEW_SURFACE_MODE)
            data.writeInt(surfaceId)
        }
        Log.i(TAG, "Vision probe Surface detached: camera=$cameraId id=$surfaceId")
        TelemetryLog.event("CAMERA_SERVICE", "surface detached camera=$cameraId id=$surfaceId")
    }

    private class CameraCallback : Binder(), IInterface {
        init {
            attachInterface(this, CAMERA_CALLBACK_DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CAMERA_CALLBACK_DESCRIPTOR)
                return true
            }
            if (code in 1..0x00ffffff) data.enforceInterface(CAMERA_CALLBACK_DESCRIPTOR)
            val availableBytes = data.dataAvail()
            recordCallbackShape(code, availableBytes)
            return runCatching { when (code) {
                CONNECTION_CALLBACK_TRANSACTION -> {
                    updateConnectionEvent(
                        cameraId = data.readInt(),
                        event = data.readInt(),
                        value = data.readInt(),
                        message = data.readString(),
                    )
                    true
                }

                TARGET_CALLBACK_TRANSACTION -> {
                    val cameraId = data.readInt()
                    val command = data.readInt()
                    val commandState = data.readInt()
                    val sequence = data.readInt()
                    val target = if (data.readInt() != 0) {
                        data.readCameraTarget(cameraId, command, commandState, sequence)
                    } else {
                        null
                    }
                    target?.let { updateTargets(listOf(it), batch = false) }
                    true
                }

                TARGETS_CALLBACK_TRANSACTION -> {
                    val cameraId = data.readInt()
                    val command = data.readInt()
                    val commandState = data.readInt()
                    val sequence = data.readInt()
                    val count = data.readInt().coerceIn(0, MAX_PARCEL_TARGETS)
                    val targets = buildList {
                        repeat(count) {
                            if (data.readInt() != 0) {
                                add(data.readCameraTarget(cameraId, command, commandState, sequence))
                            }
                        }
                    }
                    updateTargets(targets, batch = true)
                    true
                }

                STATUS_CALLBACK_TRANSACTION -> {
                    val cameraId = data.readInt()
                    val event = data.readInt()
                    val hasStatus = data.readInt() != 0
                    if (hasStatus && data.dataAvail() >= CAMERA_STATUS_POSITION_BYTES) {
                        data.readInt() // way
                        data.readInt() // type
                        data.readInt() // value
                        val position = data.readInt()
                        updateCameraPosition(cameraId, event, position)
                    }
                    true
                }

                in 1..20 -> true
                else -> super.onTransact(code, data, reply, flags)
            } }.getOrElse { error ->
                recordCallbackError(code, error)
                true
            }
        }
    }

    private fun updateCameraPosition(cameraId: Int, event: Int, position: Int) {
        val current = mutableState.value
        if (current.selectedPosition == position && current.cameraId == cameraId) return
        TelemetryLog.event(
            "CAMERA_SERVICE",
            "status camera=$cameraId event=$event position=$position",
        )
        mutableState.value = current.copy(
            cameraId = cameraId,
            selectedPosition = position,
            status = if (position == FRONT_CAMERA_POSITION) {
                "Front camera active"
            } else {
                "Rear camera active; front detection hidden"
            },
        )
    }

    private fun Parcel.readCameraTarget(
        cameraId: Int,
        command: Int,
        commandState: Int,
        sequence: Int,
    ): GrabadoraHdCameraTarget {
        val headerId = readInt()
        val commandId = readInt()
        val commandIndex = readInt()
        val boxIndex = readInt()
        val parameters = List(8) { readInt() }
        val corners = List(8) { readInt() }
        return GrabadoraHdCameraTarget(
            cameraId = cameraId,
            callbackCommand = command,
            callbackState = commandState,
            sequence = sequence,
            headerId = headerId,
            commandId = commandId,
            commandIndex = commandIndex,
            boxIndex = boxIndex,
            parameters = parameters,
            corners = corners,
            sourceTimestamp = readLong(),
            width = readFloat(),
            height = readFloat(),
        )
    }

    private const val TAG = "GrabadoraHdCamera"
    private const val GRABADORA_PACKAGE = "com.ankai.cardvr"
    private const val CAMERA_SERVICE_CLASS = "git.my.camera.core.MyCameraService"
    private const val CAMERA_SERVICE_ACTION = "git.my.camera.core.ICameraService"
    private const val CAMERA_SERVICE_DESCRIPTOR = "git.my.camera.core.ICameraService"
    private const val CAMERA_CALLBACK_DESCRIPTOR = "git.my.camera.core.ICameraServiceCallback"
    private const val EXTRA_SERVICE_PACKAGE = "service_package_name"
    private const val EXTRA_PROXY_PACKAGE = "proxy_package_name"
    private const val EXTRA_PROXY_PID = "proxy_calling_pid"
    private const val TRANSACTION_CONNECT_CAMERA = 3
    private const val TRANSACTION_ADD_SURFACE = 5
    private const val TRANSACTION_REMOVE_SURFACE = 6
    private const val LIVE_PREVIEW_SURFACE_MODE = 0
    private const val CONNECTION_CALLBACK_TRANSACTION = 1
    private const val STATUS_CALLBACK_TRANSACTION = 6
    private const val TARGET_CALLBACK_TRANSACTION = 17
    private const val TARGETS_CALLBACK_TRANSACTION = 18
    private const val MAX_PARCEL_TARGETS = 64
    private const val MAX_RETAINED_TARGETS = 200
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val SERVICE_DEFAULT_CAMERA_LABEL = "Automatica (servicio Grabadora HD)"
    private const val MAX_DIAGNOSTIC_EVENTS = 80
    private const val CAMERA_STATUS_POSITION_BYTES = 16
    private const val FRONT_CAMERA_POSITION = 0
}

internal fun grabadoraCameraDeviceName(visibleUsbDeviceName: String?): String =
    visibleUsbDeviceName.orEmpty()

internal fun describeUsbDevice(manager: UsbManager, device: UsbDevice): String {
    val interfaces = (0 until device.interfaceCount).joinToString(";") { index ->
        val usbInterface = device.getInterface(index)
        val endpoints = (0 until usbInterface.endpointCount).joinToString(",") { endpointIndex ->
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
            "0x${endpoint.address.toString(16)}:$direction/${usbTransferType(endpoint.type)}" +
                "/max=${endpoint.maxPacketSize}/interval=${endpoint.interval}"
        }
        "#${usbInterface.id} alt=${usbInterface.alternateSetting} " +
            "class=${usbInterface.interfaceClass}/${usbInterface.interfaceSubclass}/" +
            "${usbInterface.interfaceProtocol} ep=[$endpoints]"
    }
    val configurations = (0 until device.configurationCount).joinToString(";") { index ->
        val configuration = device.getConfiguration(index)
        "#${configuration.id} maxPower=${configuration.maxPower}mA interfaces=${configuration.interfaceCount}"
    }
    return "${device.deviceName} vid=0x${device.vendorId.toString(16)}(${device.vendorId}) " +
        "pid=0x${device.productId.toString(16)}(${device.productId}) " +
        "class=${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol} " +
        "configs=[$configurations] interfaces=[$interfaces] permission=${manager.hasPermission(device)}"
}

internal fun usbTransferType(type: Int): String = when (type) {
    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
    else -> "UNKNOWN($type)"
}

@Immutable
internal data class GrabadoraHdCameraState(
    val connected: Boolean = false,
    val callbackRegistered: Boolean = false,
    val deviceName: String? = null,
    val cameraId: Int? = null,
    val selectedPosition: Int? = null,
    val lastCallbackCode: Int? = null,
    val registrationAttempts: Long = 0L,
    val targetCallbackCount: Long = 0L,
    val emptyTargetCallbackCount: Long = 0L,
    val targetEventCount: Long = 0,
    val targetBatchCount: Long = 0,
    val lastTargetElapsedRealtime: Long = 0,
    val samples: List<GrabadoraHdCameraTarget> = emptyList(),
    val usbInventory: List<String> = emptyList(),
    val callbackShapes: Map<String, Long> = emptyMap(),
    val callbackErrors: List<String> = emptyList(),
    val registrationHistory: List<String> = emptyList(),
    val connectionHistory: List<String> = emptyList(),
    val status: String = "Target capture not started",
)

@Immutable
internal data class GrabadoraHdCameraTarget(
    val cameraId: Int,
    val callbackCommand: Int,
    val callbackState: Int,
    val sequence: Int,
    val headerId: Int,
    val commandId: Int,
    val commandIndex: Int,
    val boxIndex: Int,
    val parameters: List<Int>,
    val corners: List<Int>,
    val sourceTimestamp: Long,
    val width: Float,
    val height: Float,
) {
    val signature: String
        get() = "$headerId:$commandId:$commandIndex:$boxIndex:" +
            parameters.joinToString(",") + ":" + corners.joinToString(",")

    fun logLine(): String =
        "camera=$cameraId callback=($callbackCommand,$callbackState,$sequence) " +
            "header=0x${headerId.toString(16)} cmd=($commandId,$commandIndex,$boxIndex) " +
            "par=[${parameters.joinToString()}] corners=[${corners.joinToString()}] " +
            "time=$sourceTimestamp size=${width}x$height"
}

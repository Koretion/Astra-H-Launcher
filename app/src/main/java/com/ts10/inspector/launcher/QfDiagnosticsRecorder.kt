package com.ts10.inspector.launcher

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.qf.QfDiagnosticsState
import com.ts10.inspector.vehicle.qf.QfLogEntry
import com.ts10.inspector.vehicle.qf.QfRawPacketSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Optional QF/CAN capture: packet samples, observed broadcasts and the diagnostic log. */
internal object QfDiagnosticsRecorder {
    val mutableDiagnostics = MutableStateFlow(QfDiagnosticsState())
    val diagnostics: StateFlow<QfDiagnosticsState> = mutableDiagnostics.asStateFlow()
    private var diagnosticReceiverRegistered = false
    @Volatile
    var diagnosticsCaptureActive = false
    private val lastPacketByType = mutableMapOf<String, ByteArray>()
    private val diagnosticReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logObservedBroadcast(intent)
        }
    }

    fun clearDiagnosticsLog() {
        lastPacketByType.clear()
        Esp32CanClient.clearCapture()
        mutableDiagnostics.update {
            val cleared = it.copy(
                log = emptyList(),
                lastPacketHex = "--",
                lastPacketDiff = "--",
                packetByteChanges = emptyMap(),
                rawPacketSamples = emptyMap(),
            )
            if (diagnosticsCaptureActive) addLog(cleared, "Log limpiado") else cleared
        }
    }

    fun startDiagnosticsCapture() {
        if (diagnosticsCaptureActive) return
        lastPacketByType.clear()
        val previous = mutableDiagnostics.value
        diagnosticsCaptureActive = true
        Esp32CanClient.startCapture()
        mutableDiagnostics.value = QfDiagnosticsState(
            captureActive = true,
            binderStatus = previous.binderStatus,
            acCallbackStatus = previous.acCallbackStatus,
            doorCallbackStatus = previous.doorCallbackStatus,
            steeringCallbackStatus = previous.steeringCallbackStatus,
            radarCallbackStatus = previous.radarCallbackStatus,
            carCallbackStatus = previous.carCallbackStatus,
            cameraCallbackStatus = previous.cameraCallbackStatus,
            turnCallbackStatus = previous.turnCallbackStatus,
            accStatus = if (LauncherStore.accessoryOn) "ACC_ON" else "ACC_OFF",
            systemNightMode = previous.systemNightMode,
            carBacklightValue = previous.carBacklightValue,
        )
        registerDiagnosticReceiver()
        addDiagnosticLog("Diagnostic capture started")
        LauncherStore.applicationContext?.let {
            updateSystemNightMode(it.resources.configuration, source = "Capture start")
            readCarBacklightProperty(source = "Capture start")
        }
    }

    fun stopDiagnosticsCapture() {
        if (!diagnosticsCaptureActive) return
        diagnosticsCaptureActive = false
        Esp32CanClient.stopCapture()
        unregisterDiagnosticReceiver()
        mutableDiagnostics.update {
            addLog(it, "Diagnostic capture stopped").copy(captureActive = false)
        }
    }

    private fun registerDiagnosticReceiver() {
        val context = LauncherStore.applicationContext ?: return
        if (diagnosticReceiverRegistered) return
        val filter = IntentFilter().apply {
            DIAGNOSTIC_ONLY_BROADCAST_ACTIONS.forEach(::addAction)
        }
        ContextCompat.registerReceiver(
            context,
            diagnosticReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        diagnosticReceiverRegistered = true
    }

    private fun unregisterDiagnosticReceiver() {
        val context = LauncherStore.applicationContext ?: return
        if (!diagnosticReceiverRegistered) return
        runCatching { context.unregisterReceiver(diagnosticReceiver) }
        diagnosticReceiverRegistered = false
    }

    fun recordPacket(packet: ByteArray, source: String) {
        if (!diagnosticsCaptureActive) return
        val hex = packet.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        val type = packetTypeLabel(packet)
        val diff = packetDiff(type, packet)
        val sample = QfRawPacketSample(
            timestamp = LocalTime.now().format(LOG_TIME_FORMAT),
            source = source,
            sizeBytes = packet.size,
            hex = hex,
            diff = diff,
        )
        updateDiagnostics { current ->
            val updated = current.copy(
                packetCount = current.packetCount + 1,
                lastSource = source,
                lastPacketHex = hex,
                lastPacketDiff = diff,
                packetByteChanges = if (diff == NO_PACKET_DIFF) {
                    current.packetByteChanges
                } else {
                    current.packetByteChanges + (type to diff)
                },
                packetTypes = current.packetTypes +
                    (type to ((current.packetTypes[type] ?: 0L) + 1L)),
                rawPacketSamples = retainRawPacketSample(
                    samplesByType = current.rawPacketSamples,
                    type = type,
                    sample = sample,
                ),
            )
            val shouldLog =
                current.lastPacketHex != hex ||
                    shouldAlwaysLogPacket(type)
            if (shouldLog) {
                addLog(updated, "$source [$type] [${packet.size} bytes]: $hex")
            } else {
                updated
            }
        }
    }

    private fun retainRawPacketSample(
        samplesByType: Map<String, List<QfRawPacketSample>>,
        type: String,
        sample: QfRawPacketSample,
    ): Map<String, List<QfRawPacketSample>> {
        val previous = samplesByType[type].orEmpty()
        if (previous.firstOrNull()?.hex == sample.hex) return samplesByType
        if (previous.isEmpty() && samplesByType.size >= MAX_RAW_PACKET_TYPES) return samplesByType
        val recentDistinct = (listOf(sample) + previous.filterNot { it.hex == sample.hex })
            .take(MAX_RAW_PACKET_SAMPLES_PER_TYPE)
        return samplesByType + (type to recentDistinct)
    }

    private fun packetDiff(type: String, packet: ByteArray): String {
        val previous = lastPacketByType[type]
        lastPacketByType[type] = packet.copyOf()
        if (previous == null) return "Primer paquete $type"
        val changes = buildList {
            val max = maxOf(previous.size, packet.size)
            repeat(max) { index ->
                val old = previous.getOrNull(index)?.toInt()?.and(0xff)
                val new = packet.getOrNull(index)?.toInt()?.and(0xff)
                if (old != new) {
                    add("b%02d:%s>%s".format(index, old?.toHexByte() ?: "--", new?.toHexByte() ?: "--"))
                }
            }
        }
        return changes.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: NO_PACKET_DIFF
    }

    private fun packetTypeLabel(packet: ByteArray): String {
        if (packet.size < 2) return "Invalido"
        if (packet[0].toInt() and 0xff == QF_PACKET_HEADER) {
            return when (packet[1].toInt() and 0xff) {
                QF_PACKET_CLIMATE -> "Clima broadcast"
                QF_PACKET_CAR_BODY -> "Carroceria broadcast"
                else -> "Broadcast tipo ${packet[1].toInt() and 0xff}"
            }
        }
        val type = packet[1].toInt() and 0xff
        val length = packet.getOrNull(2)?.toInt()?.and(0xff) ?: 0
        return when {
            type == QF_PACKET_CLIMATE && length == QF_DOOR_PAYLOAD_SIZE -> "Doors (variant)"
            type == QF_PACKET_CLIMATE || type == QF_PACKET_CLIMATE_ALTERNATE -> "Clima Binder"
            type == QF_PACKET_CAR_BODY -> "Carroceria Binder"
            type == QF_PACKET_DOORS -> "Doors"
            type == QF_PACKET_RADAR -> "Radar"
            type == QF_PACKET_STEERING -> "Angulo volante"
            type == QF_PACKET_CAMERA -> "QF camera"
            type == QF_PACKET_TURN_LIGHT -> "Intermitentes"
            type == QF_OPEL_TRIP_COMPUTER_2 -> "Binder tipo 21 (posible ordenador Opel 2)"
            type == QF_OPEL_TRIP_COMPUTER_1 -> "Binder tipo 25 (posible ordenador Opel 1)"
            else -> "Binder tipo $type"
        }
    }

    fun updateAccStatus(status: String) {
        updateDiagnostics { addLog(it.copy(accStatus = status), status) }
    }

    /**
     * Retain reverse events for CAN diagnostics only. Camera presentation is intentionally
     * disabled until a reliable video integration is available on the TS10.
     */
    fun updateReverseStatus(reversing: Boolean) {
        val status = if (reversing) "Marcha atras activa" else "Marcha atras finalizada"
        updateDiagnostics {
            addLog(
                it.copy(
                    reverseEventCount = it.reverseEventCount + 1,
                    reverseStatus = status,
                ),
                if (reversing) "BACKCAR_START" else "BACKCAR_STOP",
            )
        }
    }

    fun updateBinderStatus(status: UiText) {
        // El estado del Binder se muestra traducido; el registro y el informe usan inglés.
        val logText = LauncherStore.applicationContext?.let(status::english) ?: status.toString()
        val callback = (status as? UiText.Res)
            ?.takeIf { it.id == R.string.qf_callback_registered || it.id == R.string.qf_callback_failed }
        mutableDiagnostics.update { current ->
            val updated = if (callback == null) {
                current.copy(binderStatus = status)
            } else {
                val value = if (callback.id == R.string.qf_callback_registered) "Registered" else logText
                when (callback.args.firstOrNull()) {
                    3 -> current.copy(acCallbackStatus = value)
                    5 -> current.copy(doorCallbackStatus = value)
                    7 -> current.copy(steeringCallbackStatus = value)
                    9 -> current.copy(radarCallbackStatus = value)
                    11 -> current.copy(carCallbackStatus = value)
                    17 -> current.copy(cameraCallbackStatus = value)
                    37 -> current.copy(turnCallbackStatus = value)
                    else -> current
                }
            }
            if (diagnosticsCaptureActive) addLog(updated, logText) else updated
        }
    }

    fun logObservedBroadcast(intent: Intent) {
        if (!diagnosticsCaptureActive) return
        val action = intent.action ?: return
        if (action !in RUNTIME_BROADCAST_ACTIONS && action !in DIAGNOSTIC_ONLY_BROADCAST_ACTIONS) return
        val extras = summarizeIntentExtras(intent)
        val message = if (extras.isBlank()) {
            "Broadcast $action"
        } else {
            "Broadcast $action extras: $extras"
        }
        updateDiagnostics { current ->
            val count = (current.observedBroadcasts[action] ?: 0L) + 1L
            addLog(
                current.copy(observedBroadcasts = current.observedBroadcasts + (action to count)),
                message,
            )
        }
    }

    private fun summarizeIntentExtras(intent: Intent): String {
        val extras = intent.extras ?: return ""
        return extras.keySet()
            .sorted()
            .joinToString(", ") { key ->
                val value = extras.get(key)
                when (value) {
                    is ByteArray -> "$key=${value.toHexString(MAX_BROADCAST_EXTRA_BYTES)}"
                    is IntArray -> "$key=${value.joinToString(prefix = "[", postfix = "]")}"
                    is LongArray -> "$key=${value.joinToString(prefix = "[", postfix = "]")}"
                    is BooleanArray -> "$key=${value.joinToString(prefix = "[", postfix = "]")}"
                    is Array<*> -> "$key=${value.joinToString(prefix = "[", postfix = "]")}"
                    else -> "$key=$value"
                }
            }
    }

    private fun ByteArray.toHexString(maxBytes: Int): String {
        val visible = take(maxBytes)
            .joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xff) }
        return if (size <= maxBytes) visible else "$visible ... (${size} bytes)"
    }

    private fun shouldAlwaysLogPacket(type: String): Boolean = when (type) {
        "Intermitentes",
        "Angulo volante",
        "Radar",
        "QF camera",
        -> true
        else -> type.startsWith("Binder tipo ") || type.startsWith("Broadcast tipo ")
    }

    fun updateSystemNightMode(configuration: Configuration, source: String) {
        val mode = when (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> "Oscuro"
            Configuration.UI_MODE_NIGHT_NO -> "Claro"
            else -> "Indeterminado"
        }
        updateDiagnostics { current ->
            val count = if (current.systemNightMode == mode) current.systemNightModeCount else current.systemNightModeCount + 1
            val updated = current.copy(systemNightMode = mode, systemNightModeCount = count)
            if (current.systemNightMode == mode) updated else addLog(updated, "$source UI_MODE_NIGHT: $mode")
        }
    }

    fun readCarBacklightProperty(source: String) {
        val level = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getInt = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            getInt.invoke(null, SYSTEM_PROPERTY_CAN_BACKLIGHT, CAR_BACKLIGHT_UNKNOWN) as Int
        }.getOrDefault(CAR_BACKLIGHT_UNKNOWN)
        updateCarBacklight(level, "$source ${SYSTEM_PROPERTY_CAN_BACKLIGHT}")
    }

    fun updateCarBacklight(level: Int, source: String) {
        val value = if (level == CAR_BACKLIGHT_UNKNOWN) "No data" else level.toString()
        updateDiagnostics { current ->
            val count = if (current.carBacklightValue == value) current.carBacklightCount else current.carBacklightCount + 1
            val updated = current.copy(carBacklightValue = value, carBacklightCount = count)
            if (current.carBacklightValue == value) updated else addLog(updated, "$source: $value")
        }
    }

    fun addDiagnosticLog(message: String) {
        updateDiagnostics { addLog(it, message) }
    }

    internal fun recordPanelWindowEvent(message: String) {
        TelemetryLog.event("PANEL_WINDOW", message)
        addDiagnosticLog("Ventana panel: $message")
    }

    inline fun updateDiagnostics(transform: (QfDiagnosticsState) -> QfDiagnosticsState) {
        if (diagnosticsCaptureActive) mutableDiagnostics.update(transform)
    }

    private fun Int.toHexByte(): String = "%02X".format(this)

    private const val MAX_RAW_PACKET_TYPES = 32
    private const val MAX_RAW_PACKET_SAMPLES_PER_TYPE = 16
    private const val MAX_BROADCAST_EXTRA_BYTES = 64
    private const val NO_PACKET_DIFF = "No changes"
    private const val QF_OPEL_TRIP_COMPUTER_2 = 0x15
    private const val QF_OPEL_TRIP_COMPUTER_1 = 0x19
    private const val SYSTEM_PROPERTY_CAN_BACKLIGHT = "persist.can.backlight"
    private val DIAGNOSTIC_ONLY_BROADCAST_ACTIONS = setOf(
        ACTION_QF_POPUP_ENABLE,
        ACTION_QF_AUX_VISIBILITY,
        "com.qf.action.CAN_DATA",
        "com.qf.action.CAN_INFO",
        "com.qf.action.CAR_INFO",
        "com.qf.action.KEY_EVENT",
        "com.qf.action.MCU_DATA",
        "com.qf.action.MCU_INFO",
        "com.qf.action.STEERING_WHEEL",
        "com.qf.action.TURN_LIGHT",
        "com.qf.action.VEHICLE_INFO",
        "com.qf.mcu.action.DATA",
        "com.qf.mcu.action.KEY_EVENT",
        "com.qf.vehicle.action.CAN_DATA",
        "com.qf.vehicle.action.CAR_BODY",
        "com.qf.vehicle.action.CAR_INFO",
        "com.qf.vehicle.action.LIGHT_STATE",
        "com.qf.vehicle.action.STEERING",
        "com.qf.vehicle.action.TURN_LIGHT",
    )
}

internal fun addLog(state: QfDiagnosticsState, message: String): QfDiagnosticsState {
    val entry = QfLogEntry(LocalTime.now().format(LOG_TIME_FORMAT), message)
    return state.copy(log = (listOf(entry) + state.log).take(MAX_DIAGNOSTIC_LOG_ENTRIES))
}

internal fun boolLabel(value: Boolean) = if (value) "Active" else "Inactive"

private const val MAX_DIAGNOSTIC_LOG_ENTRIES = 200
internal val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

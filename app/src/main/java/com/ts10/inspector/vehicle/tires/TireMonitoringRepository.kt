package com.ts10.inspector.vehicle.tires

import androidx.annotation.StringRes
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.abs

internal enum class TirePosition(@StringRes val text: Int) {
    FRONT_LEFT(R.string.tire_name_front_left),
    FRONT_RIGHT(R.string.tire_name_front_right),
    REAR_LEFT(R.string.tire_name_rear_left),
    REAR_RIGHT(R.string.tire_name_rear_right),
}

internal enum class TireMonitoringMode {
    AUTO,
    USB,
    DDS,
}

internal enum class TireMonitoringSource {
    NONE,
    USB_TPMS,
    DDS_OBD2,
    DDS_HS_CAN,
}

internal enum class DdsCalibrationState {
    NOT_CALIBRATED,
    WAITING_FOR_SPEEDS,
    CALIBRATING,
    READY,
}

internal data class TireSensorReading(
    val pressureBar: Float? = null,
    val temperatureCelsius: Float? = null,
    val batteryPercent: Int? = null,
    val updatedElapsedMs: Long? = null,
)

internal data class WheelSpeeds(
    val frontLeftKmh: Float,
    val frontRightKmh: Float,
    val rearLeftKmh: Float,
    val rearRightKmh: Float,
) {
    fun values(): List<Float> = listOf(frontLeftKmh, frontRightKmh, rearLeftKmh, rearRightKmh)
}

internal data class UsbTpmsCandidate(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val name: String,
) {
    val identifier: String
        get() = "%04X:%04X".format(Locale.US, vendorId, productId)
}

internal data class TireMonitoringState(
    val mode: TireMonitoringMode = TireMonitoringMode.AUTO,
    val source: TireMonitoringSource = TireMonitoringSource.NONE,
    val usbCandidates: List<UsbTpmsCandidate> = emptyList(),
    val selectedUsbDeviceId: Int? = null,
    val usbProtocolReady: Boolean = false,
    val readings: Map<TirePosition, TireSensorReading> = emptyMap(),
    val wheelSpeeds: WheelSpeeds? = null,
    val wheelSpeedSource: String? = null,
    val calibrationState: DdsCalibrationState = DdsCalibrationState.NOT_CALIBRATED,
    val calibrationProgress: Int = 0,
    val ddsDeviationPercent: Map<TirePosition, Float> = emptyMap(),
    val ddsAlerts: Set<TirePosition> = emptySet(),
    val lastSnapshotSource: TireMonitoringSource = TireMonitoringSource.NONE,
    val lastSnapshotEpochMs: Long? = null,
    val snapshotRestored: Boolean = false,
    val status: UiText = UiText.of(R.string.tire_s_no_source),
)

/**
 * Common TPMS/DDS state. USB decoding is deliberately pluggable because the Junsun VID/PID and
 * packet format must be captured from the real receiver. DDS only emits a relative loss warning;
 * it never invents a pressure value in bar.
 */
internal object TireMonitoringRepository {
    private val mutableState = MutableStateFlow(TireMonitoringState())
    val state: StateFlow<TireMonitoringState> = mutableState.asStateFlow()

    private var applicationContext: Context? = null
    private var usbReceiverRegistered = false
    private var calibrationSums = FloatArray(4)
    private var calibrationSamples = 0
    private var baselineRatios: List<Float>? = null
    private val alertStreaks = IntArray(4)
    private var lastSnapshotPersistElapsedMs = Long.MIN_VALUE

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                refreshUsbDevices()
            }
        }
    }

    fun start(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
            readPreferences()
            registerUsbReceiver(context.applicationContext)
        }
        refreshUsbDevices()
    }

    fun setMode(mode: TireMonitoringMode) {
        applicationContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_MODE, mode.name)?.apply()
        mutableState.update { it.copy(mode = mode) }
        resolveSource()
    }

    fun selectUsbCandidate(deviceId: Int?) {
        applicationContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()?.apply {
                if (deviceId == null) remove(KEY_USB_DEVICE_ID) else putInt(KEY_USB_DEVICE_ID, deviceId)
            }?.apply()
        mutableState.update { it.copy(selectedUsbDeviceId = deviceId) }
        resolveSource()
    }

    fun beginDdsCalibration() {
        calibrationSums = FloatArray(4)
        calibrationSamples = 0
        baselineRatios = null
        alertStreaks.fill(0)
        clearStoredBaseline()
        mutableState.update {
            it.copy(
                calibrationState = DdsCalibrationState.WAITING_FOR_SPEEDS,
                calibrationProgress = 0,
                status = UiText.of(R.string.tire_s_calibration_ready, MIN_DDS_SPEED_KMH.toInt()),
            )
        }
    }

    fun resetDdsCalibration() {
        calibrationSums = FloatArray(4)
        calibrationSamples = 0
        baselineRatios = null
        alertStreaks.fill(0)
        clearStoredBaseline()
        mutableState.update {
            it.copy(
                calibrationState = DdsCalibrationState.NOT_CALIBRATED,
                calibrationProgress = 0,
                ddsDeviationPercent = emptyMap(),
                ddsAlerts = emptySet(),
                status = UiText.of(R.string.tire_s_dds_pending),
            )
        }
    }

    /** Entry point shared by a future OBD2 enhanced probe and the ESP32 HS-CAN decoder. */
    fun updateWheelSpeeds(speeds: WheelSpeeds, source: TireMonitoringSource) {
        require(source == TireMonitoringSource.DDS_OBD2 || source == TireMonitoringSource.DDS_HS_CAN)
        val values = speeds.values()
        val ratios = normalizedWheelRatios(values) ?: return
        val current = mutableState.value
        mutableState.update {
            it.copy(
                source = source,
                wheelSpeeds = speeds,
                wheelSpeedSource = if (source == TireMonitoringSource.DDS_OBD2) "OBD2 ampliado" else "GMLAN-USB HS-CAN",
            )
        }
        if (current.calibrationState == DdsCalibrationState.WAITING_FOR_SPEEDS ||
            current.calibrationState == DdsCalibrationState.CALIBRATING
        ) {
            accumulateCalibration(ratios)
            return
        }
        val baseline = baselineRatios ?: return
        val deviations = ddsRelativeDeviations(ratios, baseline)
        val alerts = buildSet {
            deviations.forEachIndexed { index, deviation ->
                alertStreaks[index] = if (deviation >= DDS_ALERT_PERCENT) alertStreaks[index] + 1 else 0
                if (alertStreaks[index] >= DDS_ALERT_CONFIRMATION_SAMPLES) add(TirePosition.entries[index])
            }
        }
        mutableState.update {
            it.copy(
                source = source,
                ddsDeviationPercent = TirePosition.entries.zip(deviations).toMap(),
                ddsAlerts = alerts,
                readings = emptyMap(),
                lastSnapshotSource = source,
                lastSnapshotEpochMs = System.currentTimeMillis(),
                snapshotRestored = false,
                status = if (alerts.isEmpty()) UiText.of(R.string.tire_s_dds_ok) else UiText.of(R.string.tire_s_dds_alert),
            )
        }
        persistSnapshotIfDue()
    }

    /** Called by the future Junsun decoder once its USB packet format has been verified. */
    fun updateUsbReadings(readings: Map<TirePosition, TireSensorReading>) {
        mutableState.update {
            it.copy(
                source = TireMonitoringSource.USB_TPMS,
                usbProtocolReady = true,
                readings = readings,
                ddsDeviationPercent = emptyMap(),
                ddsAlerts = emptySet(),
                lastSnapshotSource = TireMonitoringSource.USB_TPMS,
                lastSnapshotEpochMs = System.currentTimeMillis(),
                snapshotRestored = false,
                status = UiText.of(R.string.tire_s_usb_receiving),
            )
        }
        persistSnapshotIfDue()
    }

    fun refreshUsbDevices() {
        val context = applicationContext ?: return
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        val candidates = manager.deviceList.values
            .filter(::looksLikeTpmsReceiver)
            .map(::describeCandidate)
            .sortedBy(UsbTpmsCandidate::name)
        mutableState.update { current ->
            val selected = current.selectedUsbDeviceId?.takeIf { id -> candidates.any { it.deviceId == id } }
                ?: candidates.singleOrNull()?.deviceId
            current.copy(usbCandidates = candidates, selectedUsbDeviceId = selected)
        }
        resolveSource()
    }

    private fun accumulateCalibration(ratios: List<Float>) {
        ratios.forEachIndexed { index, value -> calibrationSums[index] += value }
        calibrationSamples += 1
        if (calibrationSamples >= DDS_CALIBRATION_SAMPLES) {
            baselineRatios = calibrationSums.map { it / calibrationSamples }
            storeBaseline(baselineRatios.orEmpty())
            mutableState.update {
                it.copy(
                    source = if (it.wheelSpeedSource == "OBD2 ampliado") TireMonitoringSource.DDS_OBD2 else TireMonitoringSource.DDS_HS_CAN,
                    calibrationState = DdsCalibrationState.READY,
                    calibrationProgress = 100,
                    status = UiText.of(R.string.tire_s_dds_calibrated),
                )
            }
        } else {
            mutableState.update {
                it.copy(
                    calibrationState = DdsCalibrationState.CALIBRATING,
                    calibrationProgress = calibrationSamples * 100 / DDS_CALIBRATION_SAMPLES,
                    status = UiText.of(R.string.tire_s_calibrating, calibrationSamples * 100 / DDS_CALIBRATION_SAMPLES),
                )
            }
        }
    }

    private fun resolveSource() {
        mutableState.update { current ->
            val usbPresent = current.selectedUsbDeviceId != null
            when {
                current.mode == TireMonitoringMode.USB && !usbPresent -> current.copy(
                    source = TireMonitoringSource.NONE,
                    status = UiText.of(R.string.tire_s_usb_not_connected),
                )
                current.mode == TireMonitoringMode.USB && usbPresent -> current.copy(
                    source = if (current.usbProtocolReady) TireMonitoringSource.USB_TPMS else TireMonitoringSource.NONE,
                    status = when {
                        current.usbProtocolReady -> UiText.of(R.string.tire_s_usb_receiving)
                        current.snapshotRestored -> UiText.of(R.string.tire_s_saved_usb_pending)
                        else -> UiText.of(R.string.tire_s_usb_protocol_pending)
                    },
                )
                current.mode == TireMonitoringMode.AUTO && usbPresent && current.usbProtocolReady -> current.copy(
                    source = TireMonitoringSource.USB_TPMS,
                    status = UiText.of(R.string.tire_s_usb_receiving),
                )
                baselineRatios != null -> current.copy(
                    source = current.source.takeIf { it == TireMonitoringSource.DDS_OBD2 || it == TireMonitoringSource.DDS_HS_CAN }
                        ?: TireMonitoringSource.NONE,
                    status = when {
                        current.wheelSpeeds == null && current.snapshotRestored ->
                            UiText.of(R.string.tire_s_dds_saved_waiting)
                        current.wheelSpeeds == null && usbPresent -> UiText.of(R.string.tire_s_usb_pending_dds_waiting)
                        current.wheelSpeeds == null -> UiText.of(R.string.tire_s_dds_waiting)
                        else -> current.status
                    },
                )
                else -> current.copy(
                    source = TireMonitoringSource.NONE,
                    status = if (current.calibrationState == DdsCalibrationState.WAITING_FOR_SPEEDS) current.status else UiText.of(R.string.tire_s_dds_pending),
                )
            }
        }
    }

    private fun readPreferences() {
        val preferences = applicationContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) ?: return
        val mode = runCatching {
            TireMonitoringMode.valueOf(preferences.getString(KEY_MODE, TireMonitoringMode.AUTO.name).orEmpty())
        }.getOrDefault(TireMonitoringMode.AUTO)
        val storedBaseline = (0..3).map { preferences.getFloat("$KEY_BASELINE$it", Float.NaN) }
            .takeIf { values -> values.all(Float::isFinite) }
        baselineRatios = storedBaseline
        val snapshotSource = runCatching {
            TireMonitoringSource.valueOf(
                preferences.getString(KEY_SNAPSHOT_SOURCE, TireMonitoringSource.NONE.name).orEmpty(),
            )
        }.getOrDefault(TireMonitoringSource.NONE)
        val savedReadings = TirePosition.entries.mapNotNull { position ->
            val pressureKey = snapshotKey(position, "pressure")
            val temperatureKey = snapshotKey(position, "temperature")
            val batteryKey = snapshotKey(position, "battery")
            if (!preferences.contains(pressureKey) && !preferences.contains(temperatureKey) &&
                !preferences.contains(batteryKey)
            ) return@mapNotNull null
            position to TireSensorReading(
                pressureBar = preferences.getFloat(pressureKey, Float.NaN).takeIf(Float::isFinite),
                temperatureCelsius = preferences.getFloat(temperatureKey, Float.NaN).takeIf(Float::isFinite),
                batteryPercent = preferences.getInt(batteryKey, -1).takeIf { it >= 0 },
            )
        }.toMap()
        val savedDeviations = TirePosition.entries.mapNotNull { position ->
            val key = snapshotKey(position, "deviation")
            preferences.getFloat(key, Float.NaN).takeIf(Float::isFinite)?.let { position to it }
        }.toMap()
        val savedAlerts = TirePosition.entries.filterTo(mutableSetOf()) { position ->
            preferences.getBoolean(snapshotKey(position, "alert"), false)
        }
        val snapshotEpochMs = preferences.getLong(KEY_SNAPSHOT_EPOCH_MS, 0L).takeIf { it > 0L }
        val hasSnapshot = snapshotEpochMs != null && (savedReadings.isNotEmpty() || savedDeviations.isNotEmpty())
        mutableState.value = mutableState.value.copy(
            mode = mode,
            selectedUsbDeviceId = preferences.getInt(KEY_USB_DEVICE_ID, -1).takeIf { it >= 0 },
            calibrationState = if (storedBaseline == null) DdsCalibrationState.NOT_CALIBRATED else DdsCalibrationState.READY,
            calibrationProgress = if (storedBaseline == null) 0 else 100,
            readings = savedReadings,
            ddsDeviationPercent = savedDeviations,
            ddsAlerts = savedAlerts,
            lastSnapshotSource = snapshotSource,
            lastSnapshotEpochMs = snapshotEpochMs,
            snapshotRestored = hasSnapshot,
            status = if (hasSnapshot) UiText.of(R.string.tire_s_restored) else mutableState.value.status,
        )
    }

    private fun persistSnapshotIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (lastSnapshotPersistElapsedMs != Long.MIN_VALUE &&
            now - lastSnapshotPersistElapsedMs < SNAPSHOT_PERSIST_INTERVAL_MS
        ) return
        val snapshot = mutableState.value
        if (snapshot.readings.isEmpty() && snapshot.ddsDeviationPercent.isEmpty()) return
        lastSnapshotPersistElapsedMs = now
        applicationContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)?.edit()?.apply {
            putString(KEY_SNAPSHOT_SOURCE, snapshot.lastSnapshotSource.name)
            putLong(KEY_SNAPSHOT_EPOCH_MS, snapshot.lastSnapshotEpochMs ?: System.currentTimeMillis())
            TirePosition.entries.forEach { position ->
                val reading = snapshot.readings[position]
                val deviation = snapshot.ddsDeviationPercent[position]
                putOptionalFloat(snapshotKey(position, "pressure"), reading?.pressureBar)
                putOptionalFloat(snapshotKey(position, "temperature"), reading?.temperatureCelsius)
                if (reading?.batteryPercent == null) remove(snapshotKey(position, "battery"))
                else putInt(snapshotKey(position, "battery"), reading.batteryPercent)
                putOptionalFloat(snapshotKey(position, "deviation"), deviation)
                putBoolean(snapshotKey(position, "alert"), position in snapshot.ddsAlerts)
            }
        }?.apply()
    }

    private fun android.content.SharedPreferences.Editor.putOptionalFloat(key: String, value: Float?) {
        if (value == null || !value.isFinite()) remove(key) else putFloat(key, value)
    }

    private fun storeBaseline(values: List<Float>) {
        applicationContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)?.edit()?.apply {
            values.forEachIndexed { index, value -> putFloat("$KEY_BASELINE$index", value) }
        }?.apply()
    }

    private fun clearStoredBaseline() {
        applicationContext?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)?.edit()?.apply {
            (0..3).forEach { remove("$KEY_BASELINE$it") }
        }?.apply()
    }

    private fun registerUsbReceiver(context: Context) {
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    @Suppress("MissingPermission")
    private fun describeCandidate(device: UsbDevice): UsbTpmsCandidate {
        val label = runCatching { device.productName }.getOrNull()
            ?: runCatching { device.manufacturerName }.getOrNull()
            ?: "Dispositivo USB ${device.deviceId}"
        return UsbTpmsCandidate(device.deviceId, device.vendorId, device.productId, label)
    }

    @Suppress("MissingPermission")
    private fun looksLikeTpmsReceiver(device: UsbDevice): Boolean {
        val identity = buildString {
            append(runCatching { device.productName }.getOrNull().orEmpty())
            append(' ')
            append(runCatching { device.manufacturerName }.getOrNull().orEmpty())
        }.lowercase(Locale.ROOT)
        if (identity.contains("tpms") || identity.contains("junsun") || identity.contains("tire")) return true
        val classes = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }.toSet()
        if (UsbConstants.USB_CLASS_VIDEO in classes || UsbConstants.USB_CLASS_MASS_STORAGE in classes || UsbConstants.USB_CLASS_AUDIO in classes) return false
        return classes.any {
            it == UsbConstants.USB_CLASS_HID ||
                it == UsbConstants.USB_CLASS_COMM ||
                it == UsbConstants.USB_CLASS_CDC_DATA ||
                it == UsbConstants.USB_CLASS_VENDOR_SPEC
        }
    }

    private const val PREFERENCES = "tire_monitoring"
    private const val KEY_MODE = "mode"
    private const val KEY_USB_DEVICE_ID = "usb_device_id"
    private const val KEY_BASELINE = "baseline."
    private const val KEY_SNAPSHOT_SOURCE = "snapshot.source"
    private const val KEY_SNAPSHOT_EPOCH_MS = "snapshot.epoch_ms"
    private const val MIN_DDS_SPEED_KMH = 25f
    private const val DDS_CALIBRATION_SAMPLES = 120
    private const val DDS_ALERT_PERCENT = 2.5f
    private const val DDS_ALERT_CONFIRMATION_SAMPLES = 20
    private const val SNAPSHOT_PERSIST_INTERVAL_MS = 15_000L

    private fun snapshotKey(position: TirePosition, field: String) =
        "snapshot.${position.name.lowercase(Locale.ROOT)}.$field"
}

internal fun normalizedWheelRatios(values: List<Float>): List<Float>? {
    if (values.size != 4 || values.any { !it.isFinite() || it < 25f }) return null
    val average = values.average().toFloat()
    if (average <= 0f || values.any { abs(it - average) / average > 0.08f }) return null
    return values.map { it / average }
}

internal fun ddsRelativeDeviations(currentRatios: List<Float>, baselineRatios: List<Float>): List<Float> {
    require(currentRatios.size == 4 && baselineRatios.size == 4)
    return currentRatios.zip(baselineRatios) { current, baseline ->
        if (baseline == 0f) 0f else (current / baseline - 1f) * 100f
    }
}

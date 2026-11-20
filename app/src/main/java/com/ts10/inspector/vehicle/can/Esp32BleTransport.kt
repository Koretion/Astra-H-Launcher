package com.ts10.inspector.vehicle.can

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

internal data class Esp32BleCandidate(val name: String, val address: String, val rssi: Int)

internal data class Esp32BleDiscoveryState(
    val searching: Boolean = false,
    val devices: List<Esp32BleCandidate> = emptyList(),
    val savedAddress: String? = null,
    val savedName: String? = null,
)

/**
 * BLE fallback link to the ESP32 GATT service. USB is the preferred transport: every callback
 * yields as soon as the client reports a USB connection.
 *
 * The firmware exposes no BLE security, so "pairing" means remembering one ESP32 by address.
 * With a saved device the automatic scan ignores any other ESP32; without one it keeps the
 * historical behaviour of connecting to the first board advertising the CAN service.
 */
internal object Esp32BleTransport {
    interface Listener {
        /** A USB cable is attached, even if its stream is not active yet. */
        val usbConnected: Boolean
        /** USB is attached or is the transport currently in use. */
        val usbActive: Boolean
        val bleActive: Boolean
        fun onBleStatus(status: UiText)
        fun onBleDeviceFound(name: String, address: String?)
        fun onBleConnected()
        fun onBleSubscribed()
        fun onBleDisconnected(status: Int)
        fun onBleLinkLost(message: String)
        fun onBleStreamPacket(value: ByteArray)
        fun onBleStats(value: ByteArray)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var listener: Listener? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var streamCharacteristic: BluetoothGattCharacteristic? = null
    private var statsCharacteristic: BluetoothGattCharacteristic? = null
    private var discovering = false
    private var reconnectRequested = false
    private val mutableDiscovery = MutableStateFlow(Esp32BleDiscoveryState())
    val discovery: StateFlow<Esp32BleDiscoveryState> = mutableDiscovery.asStateFlow()

    fun attach(context: Context, listener: Listener) {
        this.context = context.applicationContext
        this.listener = listener
        val saved = preferences(context)
        mutableDiscovery.update {
            it.copy(
                savedAddress = saved.getString(KEY_ADDRESS, null),
                savedName = saved.getString(KEY_NAME, null),
            )
        }
    }

    /** Lists every ESP32 advertising the CAN service for a few seconds, without connecting. */
    fun startDiscovery() {
        mainHandler.post {
            val appContext = context ?: return@post
            val client = listener ?: return@post
            if (discovering) return@post
            if (!hasRequiredPermissions(appContext)) {
                client.onBleStatus(UiText.of(R.string.can_ble_missing_permissions))
                return@post
            }
            val scanner = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?.takeIf { it.isEnabled }?.bluetoothLeScanner
            if (scanner == null) {
                client.onBleStatus(UiText.of(R.string.can_ble_off_or_no_scanner))
                return@post
            }
            mainHandler.removeCallbacks(scanRunnable)
            stopScan()
            runCatching {
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(listOf(filter), settings, discoveryCallback)
                discovering = true
                mutableDiscovery.update { it.copy(searching = true, devices = emptyList()) }
                mainHandler.postDelayed(::stopDiscovery, DISCOVERY_DURATION_MS)
            }.onFailure { error ->
                client.onBleStatus(UiText.of(R.string.can_ble_search_error, error.javaClass.simpleName))
                scheduleScan(0)
            }
        }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        runCatching {
            context?.getSystemService(BluetoothManager::class.java)
                ?.adapter?.bluetoothLeScanner?.stopScan(discoveryCallback)
        }
        mutableDiscovery.update { it.copy(searching = false) }
        scheduleScan(0)
    }

    /** Remembers [candidate] and moves the BLE link to it if another board is connected. */
    fun selectDevice(candidate: Esp32BleCandidate) {
        mainHandler.post {
            val appContext = context ?: return@post
            preferences(appContext).edit()
                .putString(KEY_ADDRESS, candidate.address)
                .putString(KEY_NAME, candidate.name)
                .apply()
            mutableDiscovery.update { it.copy(savedAddress = candidate.address, savedName = candidate.name) }
            val current = gatt
            val currentAddress = runCatching { current?.device?.address }.getOrNull()
            if (current != null && currentAddress != candidate.address) {
                reconnectRequested = true
                runCatching { current.disconnect() }
            } else if (current == null) {
                stopScan()
                scheduleScan(0)
            }
        }
    }

    fun forgetDevice() {
        mainHandler.post {
            val appContext = context ?: return@post
            preferences(appContext).edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
            mutableDiscovery.update { it.copy(savedAddress = null, savedName = null) }
        }
    }

    private val discoveryCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = runCatching { device.address }.getOrNull() ?: return
            val name = DEVICE_NAME
            mainHandler.post {
                if (!discovering) return@post
                mutableDiscovery.update { state ->
                    val candidate = Esp32BleCandidate(name, address, result.rssi)
                    state.copy(
                        devices = (state.devices.filterNot { it.address == address } + candidate)
                            .sortedByDescending(Esp32BleCandidate::rssi),
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                listener?.onBleStatus(UiText.of(R.string.can_ble_search_failed, errorCode))
                stopDiscovery()
            }
        }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun scheduleScan(delayMs: Long) {
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, delayMs)
    }

    private val scanRunnable = Runnable { startScan() }
    private val scanTimeoutRunnable = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        listener?.onBleStatus(UiText.of(R.string.can_not_found_retrying))
        scheduleScan(RECONNECT_DELAY_MS)
    }

    private fun startScan() {
        val appContext = context ?: return
        val client = listener ?: return
        if (client.usbActive || discovering) return
        if (!hasRequiredPermissions(appContext)) {
            client.onBleStatus(UiText.of(R.string.can_ble_missing_permissions))
            return
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            client.onBleStatus(UiText.of(R.string.can_ble_unavailable))
            return
        }
        if (!adapter.isEnabled) {
            client.onBleStatus(UiText.of(R.string.can_bluetooth_off))
            scheduleScan(RECONNECT_DELAY_MS)
            return
        }
        if (scanning || client.bleActive) return
        val scanner = adapter.bluetoothLeScanner ?: run {
            client.onBleStatus(UiText.of(R.string.can_ble_scanner_unavailable))
            scheduleScan(RECONNECT_DELAY_MS)
            return
        }
        runCatching {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            // Continuous low-latency BLE scans can starve the TS10's classic Bluetooth/HFP
            // stack. USB remains the preferred transport; BLE fallback uses balanced bursts.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            client.onBleStatus(UiText.of(R.string.can_searching))
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        }.onFailure { error ->
            client.onBleStatus(UiText.of(R.string.can_scan_error, error.javaClass.simpleName))
            scheduleScan(RECONNECT_DELAY_MS)
        }
    }

    fun stopScan() {
        val appContext = context ?: return
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (!scanning) return
        scanning = false
        runCatching {
            appContext.getSystemService(BluetoothManager::class.java)
                ?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    /** Drops the BLE link without scheduling a reconnection: USB has taken over. */
    fun disconnectForUsb() {
        val previousGatt = gatt
        gatt = null
        streamCharacteristic = null
        statsCharacteristic = null
        runCatching { previousGatt?.disconnect() }
        runCatching { previousGatt?.close() }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            mainHandler.post {
                val client = listener ?: return@post
                if (!scanning || client.usbConnected) return@post
                val savedAddress = mutableDiscovery.value.savedAddress
                if (savedAddress != null && runCatching { result.device.address }.getOrNull() != savedAddress) {
                    return@post
                }
                stopScan()
                val device = result.device
                val name = DEVICE_NAME
                client.onBleDeviceFound(name, runCatching { device.address }.getOrNull())
                val appContext = context ?: return@post
                runCatching {
                    gatt?.close()
                    gatt = device.connectGatt(
                        appContext,
                        false,
                        gattCallback,
                        android.bluetooth.BluetoothDevice.TRANSPORT_LE,
                    )
                }.onFailure { onGattFailure("Could not connect", it) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                scanning = false
                if (listener?.usbActive != false) return@post
                listener?.onBleStatus(UiText.of(R.string.can_scan_failed, errorCode))
                scheduleScan(RECONNECT_DELAY_MS)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                val client = listener ?: return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (client.usbConnected) {
                        runCatching { gatt.disconnect() }
                        runCatching { gatt.close() }
                        return@post
                    }
                    this@Esp32BleTransport.gatt = gatt
                    client.onBleConnected()
                    if (!gatt.discoverServices()) onGattFailure("Could not discover GATT")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasCurrent = this@Esp32BleTransport.gatt === gatt
                    if (wasCurrent) this@Esp32BleTransport.gatt = null
                    runCatching { gatt.close() }
                    if (!wasCurrent || client.usbActive) return@post
                    streamCharacteristic = null
                    statsCharacteristic = null
                    client.onBleDisconnected(status)
                    scheduleScan(if (reconnectRequested) 0 else RECONNECT_DELAY_MS)
                    reconnectRequested = false
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (listener?.usbConnected != false || this@Esp32BleTransport.gatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onGattFailure("GATT discovery error: $status")
                    return@post
                }
                val service = gatt.getService(SERVICE_UUID)
                streamCharacteristic = service?.getCharacteristic(CAN_STREAM_UUID)
                statsCharacteristic = service?.getCharacteristic(CAPTURE_STATS_UUID)
                if (streamCharacteristic == null || statsCharacteristic == null) {
                    onGattFailure("Incomplete BLE CAN service")
                    return@post
                }
                enableNotifications(gatt, streamCharacteristic!!)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                if (listener?.usbConnected != false || this@Esp32BleTransport.gatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onGattFailure("Could not enable Notify: $status")
                    return@post
                }
                when (descriptor.characteristic.uuid) {
                    CAN_STREAM_UUID -> enableNotifications(gatt, statsCharacteristic!!)
                    CAPTURE_STATS_UUID -> listener?.onBleSubscribed()
                }
            }
        }

        @Deprecated("Framework callback retained for Android 10-12")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic.uuid, characteristic.value?.copyOf() ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristic(characteristic.uuid, value.copyOf())
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            onGattFailure("Android rejected notifications ${characteristic.uuid}")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            onGattFailure("Characteristic without CCCD ${characteristic.uuid}")
            return
        }
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (!gatt.writeDescriptor(descriptor)) {
            onGattFailure("Could not write CCCD ${characteristic.uuid}")
        }
    }

    private fun handleCharacteristic(uuid: UUID, value: ByteArray) {
        val client = listener ?: return
        if (client.usbConnected || !client.bleActive) return
        when (uuid) {
            CAN_STREAM_UUID -> client.onBleStreamPacket(value)
            CAPTURE_STATS_UUID -> client.onBleStats(value)
        }
    }

    private fun onGattFailure(message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        val failedGatt = gatt
        gatt = null
        runCatching { failedGatt?.disconnect() }
        runCatching { failedGatt?.close() }
        val client = listener ?: return
        if (client.usbActive) return
        client.onBleLinkLost(message)
        scheduleScan(RECONNECT_DELAY_MS)
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private const val TAG = "Esp32CanBle"
    private const val DEVICE_NAME = "GMLAN-USB"
    private const val SCAN_TIMEOUT_MS = 6_000L
    private const val RECONNECT_DELAY_MS = 15_000L
    private const val DISCOVERY_DURATION_MS = 8_000L
    private const val PREFERENCES_FILE = "esp32_ble_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private val SERVICE_UUID = UUID.fromString("8c7d1000-6a4d-4b32-9f2a-7d4a6e5c1000")
    private val CAN_STREAM_UUID = UUID.fromString("8c7d1001-6a4d-4b32-9f2a-7d4a6e5c1000")
    private val CAPTURE_STATS_UUID = UUID.fromString("8c7d1002-6a4d-4b32-9f2a-7d4a6e5c1000")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

package com.ts10.inspector.vehicle.can

import com.ts10.inspector.ui.fail
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ts10.inspector.vehicle.can.firmware.Esp32FirmwareUpdater
import com.ts10.inspector.vehicle.can.firmware.Esp32RomBootloader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** USB CDC reader for the native ESP32-C3 USB Serial/JTAG interface. */
internal object Esp32CanUsbTransport {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readerExecutor = Executors.newSingleThreadExecutor()
    private val reading = AtomicBoolean(false)
    private val readerActive = AtomicBoolean(false)
    private val romExclusive = AtomicBoolean(false)
    private val parser = Esp32CanUsbStreamParser()
    private var context: Context? = null
    private var manager: UsbManager? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    @Volatile private var outputEndpoint: UsbEndpoint? = null
    private val writeLock = Any()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (!romExclusive.get()) open(device)
                    }
                    else scheduleScan(UiText.of(R.string.can_usb_permission_denied))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (!romExclusive.get()) scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice() ?: return
                    if (!romExclusive.get() && connection?.fileDescriptor != null && looksLikeEsp32(detached)) {
                        close()
                        scheduleScan(UiText.of(R.string.can_usb_disconnected_retrying))
                    }
                }
            }
        }
    }

    fun start(context: Context) {
        if (this.context == null) {
            this.context = context.applicationContext
            manager = context.getSystemService(UsbManager::class.java)
            registerReceiver(context.applicationContext)
        }
        scan()
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun scan() {
        if (reading.get() || romExclusive.get()) return
        val usbManager = manager ?: return
        val device = usbManager.deviceList.values.firstOrNull(::looksLikeEsp32)
        if (device == null) {
            scheduleScan(UiText.of(R.string.can_not_found_retrying))
            return
        }
        if (!usbManager.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val appContext = context ?: return
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags),
            )
            Esp32CanClient.onUsbDisconnected(UiText.of(R.string.can_usb_waiting_permission))
            // Some TS10 boots lose the permission broadcast while USB is still enumerating.
            // Keep polling so a missed callback cannot leave the transport stopped forever.
            mainHandler.removeCallbacks(scanRunnable)
            mainHandler.postDelayed(scanRunnable, USB_PERMISSION_WATCHDOG_MS)
            return
        }
        open(device)
    }

    private fun open(device: UsbDevice) {
        if (reading.getAndSet(true)) return
        val usbManager = manager ?: return reading.set(false)
        val dataInterface = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { candidate ->
                candidate.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA &&
                    candidate.bulkInEndpoint() != null
            }
            ?: return failOpen(UiText.of(R.string.can_usb_no_cdc))
        val input = dataInterface.bulkInEndpoint()
            ?: return failOpen(UiText.of(R.string.can_usb_no_endpoint))
        val opened = usbManager.openDevice(device)
            ?: return failOpen(UiText.of(R.string.can_usb_open_failed))
        if (!opened.claimInterface(dataInterface, true)) {
            opened.close()
            return failOpen(UiText.of(R.string.can_usb_claim_failed))
        }
        connection = opened
        claimedInterface = dataInterface
        mainHandler.removeCallbacks(scanRunnable)
        configureCdc(opened, device)
        parser.reset()
        val output = (0 until dataInterface.endpointCount).map(dataInterface::getEndpoint).firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
        }
        outputEndpoint = output
        Esp32CanClient.onUsbConnected(device.productName ?: "GMLAN-USB")
        Esp32FirmwareUpdater.onUsbConnected()
        readerExecutor.execute { readLoop(opened, input, output) }
    }

    private fun configureCdc(connection: UsbDeviceConnection, device: UsbDevice) {
        val controlInterface = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        val index = controlInterface?.id ?: 0
        val lineCoding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08) // 115200 8N1
        connection.controlTransfer(0x21, 0x20, 0, index, lineCoding, lineCoding.size, USB_TIMEOUT_MS)
        connection.controlTransfer(0x21, 0x22, 3, index, null, 0, USB_TIMEOUT_MS)
    }

    private fun readLoop(opened: UsbDeviceConnection, endpoint: UsbEndpoint, output: UsbEndpoint?) {
        readerActive.set(true)
        val buffer = ByteArray(maxOf(512, endpoint.maxPacketSize * 4))
        var lastBytesAt = SystemClock.elapsedRealtime()
        var stalled = false
        var stateRequests = 0
        var lastStateRequestMs = -STATE_REQUEST_INTERVAL_MS
        try {
            while (reading.get() && connection === opened) {
                val now = SystemClock.elapsedRealtime()
                if (!Esp32CanClient.initialStateReceived && stateRequests < 3 &&
                    now - lastStateRequestMs >= STATE_REQUEST_INTERVAL_MS) {
                    if (output != null) {
                        val request = "TS10_STATE\n".toByteArray(Charsets.US_ASCII)
                        synchronized(writeLock) {
                            opened.bulkTransfer(output, request, request.size, USB_TIMEOUT_MS)
                        }
                    }
                    stateRequests++
                    lastStateRequestMs = now
                }
                if (stateRequests == 3 && !Esp32CanClient.initialStateReceived &&
                    now - lastStateRequestMs >= STATE_REQUEST_INTERVAL_MS) {
                    Esp32CanClient.onInitialStateUnavailable()
                    stateRequests++
                }
                val count = opened.bulkTransfer(endpoint, buffer, buffer.size, USB_READ_TIMEOUT_MS)
                if (count > 0) {
                    lastBytesAt = SystemClock.elapsedRealtime()
                    parser.accept(buffer.copyOf(count)).forEach { record ->
                        if (!Esp32FirmwareUpdater.acceptUsbRecord(record)) {
                            Esp32CanClient.acceptUsbRecord(record)
                        }
                    }
                } else if (SystemClock.elapsedRealtime() - lastBytesAt >= USB_STREAM_STALL_MS) {
                    stalled = true
                    break
                }
            }
        } finally {
            readerActive.set(false)
            if (connection === opened && !romExclusive.get()) {
                close()
                scheduleScan(
                    message = if (stalled) UiText.of(R.string.can_usb_stalled)
                    else UiText.of(R.string.can_usb_read_ended),
                    delayMs = if (stalled) USB_STALL_RECONNECT_DELAY_MS else RECONNECT_DELAY_MS,
                )
            }
        }
    }

    private fun failOpen(message: UiText) {
        reading.set(false)
        scheduleScan(message)
    }

    private fun scheduleScan(message: UiText, delayMs: Long = RECONNECT_DELAY_MS) {
        Esp32CanClient.onUsbDisconnected(message)
        if (romExclusive.get()) return
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, delayMs)
    }

    private val scanRunnable = Runnable(::scan)

    private fun close() {
        reading.set(false)
        Esp32FirmwareUpdater.onUsbDisconnected()
        closePhysicalConnection()
        parser.reset()
    }

    private fun closePhysicalConnection() {
        claimedInterface?.let { runCatching { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        claimedInterface = null
        outputEndpoint = null
        connection = null
    }

    fun reconnectAfterFirmwareRestart() {
        mainHandler.post {
            if (romExclusive.get()) return@post
            if (connection != null) close()
            scheduleScan(UiText.of(R.string.efw_restarting), FIRMWARE_RESTART_RECONNECT_MS)
        }
    }

    fun sendUpdaterRecord(type: Int, payload: ByteArray): Boolean {
        if (romExclusive.get()) return false
        val packet = runCatching { encodeEsp32UsbRecord(type, payload) }.getOrNull() ?: return false
        synchronized(writeLock) {
            val opened = connection ?: return false
            val endpoint = outputEndpoint ?: return false
            return opened.bulkTransfer(endpoint, packet, packet.size, USB_WRITE_TIMEOUT_MS) == packet.size
        }
    }

    /** Takes exclusive ownership of native USB while the ROM loader replaces the old application. */
    fun installApplicationWithRom(image: ByteArray, onProgress: (Int, UiText) -> Unit) {
        if (!romExclusive.compareAndSet(false, true)) fail(R.string.rom_session_busy)
        mainHandler.removeCallbacks(scanRunnable)
        reading.set(false)
        try {
            val waitUntil = SystemClock.elapsedRealtime() + READER_STOP_TIMEOUT_MS
            while (readerActive.get() && SystemClock.elapsedRealtime() < waitUntil) Thread.sleep(25)
            if (readerActive.get()) fail(R.string.rom_reader_busy)
            closePhysicalConnection()
            AndroidRomIo().use { rom ->
                try {
                    Esp32RomBootloader(rom).installApplication(image, onProgress)
                } catch (error: Throwable) {
                    // A failure before FLASH_END used to leave GPIO9's ROM boot active until the
                    // entire TS10 was power-cycled. Restore the existing application immediately.
                    runCatching { rom.resetToApplication() }
                    throw error
                }
            }
        } finally {
            romExclusive.set(false)
            mainHandler.postDelayed(scanRunnable, ROM_RECONNECT_DELAY_MS)
        }
    }

    private class AndroidRomIo : Esp32RomBootloader.Io, AutoCloseable {
        private var port: RomPort? = null

        override fun enterBootloader() {
            port = acquirePort(ROM_PORT_OPEN_TIMEOUT_MS)
            val active = port ?: error("Could not open the GMLAN-USB native USB")
            var dtr = false
            var rts = false
            fun applyLines() {
                val value = (if (dtr) CDC_DTR else 0) or (if (rts) CDC_RTS else 0)
                check(active.connection.controlTransfer(0x21, 0x22, value, active.controlInterfaceId, null, 0, USB_TIMEOUT_MS) >= 0) {
                    "The USB port does not support the DTR/RTS control needed for the bootloader"
                }
            }
            fun setDtr(value: Boolean) { dtr = value; applyLines() }
            fun setRts(value: Boolean) { rts = value; applyLines() }

            // Espressif's USB-Serial/JTAG sequence: IO0 low, reset pulse, then release reset/IO0.
            setRts(false); setDtr(false); Thread.sleep(100)
            setDtr(true); setRts(false); Thread.sleep(100)
            setRts(true); setDtr(false); setRts(true); Thread.sleep(100)
            setDtr(false); setRts(false); Thread.sleep(150)

            closePort()
            port = acquirePort(ROM_REENUMERATION_TIMEOUT_MS)
            drainInput()
        }

        override fun resetToApplication() {
            fun reset(active: RomPort) {
                fun applyLines(dtr: Boolean, rts: Boolean) {
                    val value = (if (dtr) CDC_DTR else 0) or (if (rts) CDC_RTS else 0)
                    check(active.connection.controlTransfer(0x21, 0x22, value, active.controlInterfaceId, null, 0, USB_TIMEOUT_MS) >= 0) {
                        "Could not restart the GMLAN-USB after the failure"
                    }
                }
                applyLines(dtr = false, rts = true)
                Thread.sleep(100)
                applyLines(dtr = false, rts = false)
                Thread.sleep(150)
            }

            val current = port
            if (current != null && runCatching { reset(current) }.isSuccess) return
            // FLASH_END can re-enumerate native USB before its reply reaches Android. The old
            // connection is then stale, so reacquire the ROM port and pulse reset there.
            closePort()
            val reopened = acquirePort(ROM_REENUMERATION_TIMEOUT_MS).also { port = it }
            reset(reopened)
        }

        override fun write(packet: ByteArray) {
            val active = port ?: error("ROM port closed")
            var offset = 0
            while (offset < packet.size) {
                val sent = active.connection.bulkTransfer(
                    active.output,
                    packet,
                    offset,
                    packet.size - offset,
                    ROM_WRITE_TIMEOUT_MS,
                )
                check(sent > 0) { "USB connection lost while writing to the bootloader" }
                offset += sent
            }
        }

        override fun read(timeoutMs: Int): ByteArray? {
            val active = port ?: error("ROM port closed")
            val buffer = ByteArray(maxOf(2048, active.input.maxPacketSize * 8))
            val count = active.connection.bulkTransfer(active.input, buffer, buffer.size, timeoutMs.coerceAtLeast(1))
            return if (count > 0) buffer.copyOf(count) else null
        }

        override fun drainInput() {
            val active = port ?: return
            val buffer = ByteArray(maxOf(512, active.input.maxPacketSize * 4))
            while (active.connection.bulkTransfer(active.input, buffer, buffer.size, ROM_DRAIN_TIMEOUT_MS) > 0) Unit
        }

        override fun close() = closePort()

        private fun closePort() {
            val active = port ?: return
            runCatching { active.connection.releaseInterface(active.dataInterface) }
            runCatching { active.connection.close() }
            port = null
        }

        private fun acquirePort(timeoutMs: Long): RomPort {
            val usbManager = manager ?: error("USB service unavailable")
            val appContext = context ?: error("Android context unavailable")
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var permissionRequestedFor: Int? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val device = usbManager.deviceList.values.firstOrNull {
                    it.vendorId == ESPRESSIF_VENDOR_ID && it.productId == USB_SERIAL_JTAG_PRODUCT_ID
                }
                if (device != null) {
                    if (!usbManager.hasPermission(device)) {
                        if (permissionRequestedFor != device.deviceId) {
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                            usbManager.requestPermission(
                                device,
                                PendingIntent.getBroadcast(
                                    appContext,
                                    ROM_PERMISSION_REQUEST_CODE,
                                    Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                                    flags,
                                ),
                            )
                            permissionRequestedFor = device.deviceId
                        }
                    } else {
                        openRomPort(usbManager, device)?.let { return it }
                    }
                }
                Thread.sleep(100)
            }
            error("The GMLAN-USB USB-Serial/JTAG port (303A:1001) did not come back")
        }

        private fun openRomPort(usbManager: UsbManager, device: UsbDevice): RomPort? {
            val dataInterface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.bulkInEndpoint() != null && (0 until it.endpointCount).map(it::getEndpoint).any { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT
                }
            } ?: return null
            val input = dataInterface.bulkInEndpoint() ?: return null
            val output = (0 until dataInterface.endpointCount).map(dataInterface::getEndpoint).firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
            } ?: return null
            val opened = usbManager.openDevice(device) ?: return null
            if (!opened.claimInterface(dataInterface, true)) {
                opened.close()
                return null
            }
            val controlId = (0 until device.interfaceCount).map(device::getInterface)
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }?.id ?: 0
            val lineCoding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
            opened.controlTransfer(0x21, 0x20, 0, controlId, lineCoding, lineCoding.size, USB_TIMEOUT_MS)
            return RomPort(opened, dataInterface, input, output, controlId)
        }
    }

    private data class RomPort(
        val connection: UsbDeviceConnection,
        val dataInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
        val controlInterfaceId: Int,
    )

    private fun UsbInterface.bulkInEndpoint(): UsbEndpoint? =
        (0 until endpointCount).map(::getEndpoint).firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
        }

    private fun looksLikeEsp32(device: UsbDevice): Boolean {
        if (device.vendorId == ESPRESSIF_VENDOR_ID) return true
        val label = "${device.manufacturerName.orEmpty()} ${device.productName.orEmpty()}".lowercase()
        return ("espressif" in label || "esp32" in label) &&
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
            }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private const val ESPRESSIF_VENDOR_ID = 0x303A
    private const val USB_SERIAL_JTAG_PRODUCT_ID = 0x1001
    private const val ACTION_USB_PERMISSION = "com.ts10.inspector.ESP32_CAN_USB_PERMISSION"
    private const val USB_TIMEOUT_MS = 1_000
    private const val USB_WRITE_TIMEOUT_MS = 3_000
    private const val STATE_REQUEST_INTERVAL_MS = 2_000L
    private const val USB_READ_TIMEOUT_MS = 1_000
    private const val RECONNECT_DELAY_MS = 2_000L
    private const val USB_PERMISSION_WATCHDOG_MS = 5_000L
    private const val READER_STOP_TIMEOUT_MS = 3_000L
    private const val ROM_PORT_OPEN_TIMEOUT_MS = 8_000L
    private const val ROM_REENUMERATION_TIMEOUT_MS = 12_000L
    private const val ROM_RECONNECT_DELAY_MS = 1_500L
    private const val FIRMWARE_RESTART_RECONNECT_MS = 3_000L
    private const val ROM_WRITE_TIMEOUT_MS = 5_000
    private const val ROM_DRAIN_TIMEOUT_MS = 30
    private const val ROM_PERMISSION_REQUEST_CODE = 32
    private const val CDC_DTR = 1
    private const val CDC_RTS = 2
    // Firmware publishes a stats record every second even with both CAN buses idle.
    private const val USB_STREAM_STALL_MS = 10_000L
    // BLE needs enough time to scan and connect before USB is allowed to pre-empt it again.
    private const val USB_STALL_RECONNECT_DELAY_MS = 20_000L
}

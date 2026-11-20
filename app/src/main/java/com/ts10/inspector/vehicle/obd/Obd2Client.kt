package com.ts10.inspector.vehicle.obd

import com.ts10.inspector.ui.uiText
import androidx.annotation.StringRes
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

internal enum class ObdConnectionState {
    DISABLED,
    PERMISSION_REQUIRED,
    NO_ADAPTER,
    NO_DEVICE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

internal data class ObdDevice(
    val name: String,
    val address: String,
)

internal data class ObdCapabilityResult(
    val command: String,
    @StringRes val title: Int,
    val summary: UiText,
    val rawResponse: String,
    val available: Boolean,
)

internal data class Obd2State(
    val connection: ObdConnectionState = ObdConnectionState.DISABLED,
    val status: UiText = UiText.of(R.string.obd_s_disabled),
    val pairedDevices: List<ObdDevice> = emptyList(),
    val selectedAddress: String? = null,
    val selectedName: String? = null,
    val coolantCelsius: Int? = null,
    val oilCelsius: Int? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val fuelPercent: Int? = null,
    val massAirFlowGps: Float? = null,
    val fuelRateLitersPerHour: Float? = null,
    val estimatedFuelRateLitersPerHour: Float? = null,
    val consumptionLitersPer100Km: Float? = null,
    val supportedPids: Set<Int>? = null,
    val optimizedRequests: Boolean? = null,
    val pollCycleDurationMs: Long? = null,
    val lastCommand: String? = null,
    val lastResponse: String? = null,
    val troubleCodes: List<String> = emptyList(),
    val troubleCodesRead: Boolean = false,
    val capabilityResults: List<ObdCapabilityResult> = emptyList(),
    val capabilityTestCompleted: Boolean = false,
    val wheelSpeedTestStatus: UiText? = null,
    val wheelSpeedTestCompleted: Boolean = false,
    val busy: Boolean = false,
    val lastUpdateElapsedMs: Long? = null,
)

internal fun effectiveFuelRateLitersPerHour(state: Obd2State): Float? =
    state.fuelRateLitersPerHour ?: state.estimatedFuelRateLitersPerHour

private const val MIN_CONSUMPTION_SPEED_KMH = 2

internal fun calculateConsumptionLitersPer100Km(
    fuelRateLitersPerHour: Float?,
    speedKmh: Int?,
): Float? = if (
    fuelRateLitersPerHour != null && fuelRateLitersPerHour.isFinite() && fuelRateLitersPerHour >= 0f &&
    speedKmh != null && speedKmh > MIN_CONSUMPTION_SPEED_KMH
) {
    (fuelRateLitersPerHour * 100f / speedKmh).takeIf { it.isFinite() && it in 0f..99.9f }
} else {
    null
}

/**
 * Bluetooth Classic ELM327 provider. It deliberately owns a separate RFCOMM connection from the
 * factory QF hands-free client, so an OBD adapter and a phone can coexist.
 */
internal object Obd2Client {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val mutableState = MutableStateFlow(Obd2State())
    val state: StateFlow<Obd2State> = mutableState.asStateFlow()

    private var context: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var connectionJob: Job? = null
    private var automaticDtcJob: Job? = null
    private var supportedPids: Set<Int>? = null
    private var optimizedRequests: Boolean? = null
    private var lastCommand: String? = null
    private var lastResponse: String? = null

    fun start(context: Context) {
        if (this.context != null) {
            refreshPairedDevices()
            if (preferences()?.getBoolean(KEY_ENABLED, false) == true &&
                mutableState.value.selectedAddress != null &&
                connectionJob?.isActive != true
            ) {
                connect()
            }
            return
        }
        this.context = context.applicationContext
        adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        refreshPairedDevices()
        val preferences = preferences()
        val enabled = preferences?.getBoolean(KEY_ENABLED, false) == true
        val selected = preferences?.getString(KEY_DEVICE_ADDRESS, null)
        mutableState.update {
            it.copy(
                selectedAddress = selected,
                selectedName = it.pairedDevices.firstOrNull { device -> device.address == selected }?.name,
            )
        }
        if (enabled && selected != null) connect() else updateIdleState(enabled)
    }

    fun refreshPairedDevices() {
        val appContext = context ?: return
        if (!hasConnectPermission(appContext)) {
            mutableState.update {
                it.copy(
                    connection = ObdConnectionState.PERMISSION_REQUIRED,
                    status = UiText.of(R.string.obd_s_no_permission),
                    pairedDevices = emptyList(),
                )
            }
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            mutableState.update {
                it.copy(
                    connection = ObdConnectionState.NO_ADAPTER,
                    status = UiText.of(R.string.bt_no_adapter),
                    pairedDevices = emptyList(),
                )
            }
            return
        }
        val devices = runCatching {
            bluetoothAdapter.bondedDevices
                .map { ObdDevice(it.name?.ifBlank { "Dispositivo Bluetooth" } ?: "Dispositivo Bluetooth", it.address) }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to enumerate paired devices", error)
            emptyList()
        }
        mutableState.update { current ->
            current.copy(
                pairedDevices = devices,
                selectedName = devices.firstOrNull { it.address == current.selectedAddress }?.name,
            )
        }
    }

    fun selectDevice(device: ObdDevice) {
        preferences()?.edit()
            ?.putBoolean(KEY_ENABLED, true)
            ?.putString(KEY_DEVICE_ADDRESS, device.address)
            ?.apply()
        mutableState.update {
            it.copy(
                selectedAddress = device.address,
                selectedName = device.name,
                status = UiText.of(R.string.obd_s_preparing, device.name),
            )
        }
        connect()
    }

    fun connect() {
        val appContext = context ?: return
        if (!hasConnectPermission(appContext)) {
            mutableState.update {
                it.copy(
                    connection = ObdConnectionState.PERMISSION_REQUIRED,
                    status = UiText.of(R.string.obd_s_grant_permission),
                )
            }
            return
        }
        val address = mutableState.value.selectedAddress
        if (address == null) {
            mutableState.update {
                it.copy(connection = ObdConnectionState.NO_DEVICE, status = UiText.of(R.string.obd_s_select_adapter))
            }
            return
        }
        preferences()?.edit()?.putBoolean(KEY_ENABLED, true)?.apply()
        connectionJob?.cancel()
        closeSocket()
        connectionJob = scope.launch { connectionLoop(address) }
    }

    fun disconnect() {
        preferences()?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
        connectionJob?.cancel()
        connectionJob = null
        automaticDtcJob?.cancel()
        automaticDtcJob = null
        closeSocket()
        mutableState.update {
            it.copy(
                connection = ObdConnectionState.DISABLED,
                status = UiText.of(R.string.obd_s_disconnected),
                busy = false,
                coolantCelsius = null,
                oilCelsius = null,
                rpm = null,
                speedKmh = null,
                fuelPercent = null,
                massAirFlowGps = null,
                fuelRateLitersPerHour = null,
                estimatedFuelRateLitersPerHour = null,
                consumptionLitersPer100Km = null,
                supportedPids = null,
                optimizedRequests = null,
                pollCycleDurationMs = null,
                lastCommand = null,
                lastResponse = null,
                capabilityResults = emptyList(),
                capabilityTestCompleted = false,
            )
        }
    }

    fun readTroubleCodes() {
        scope.launch {
            if (mutableState.value.connection != ObdConnectionState.CONNECTED) {
                mutableState.update { it.copy(status = UiText.of(R.string.obd_s_connect_before_faults)) }
                return@launch
            }
            if (mutableState.value.busy) return@launch
            mutableState.update { it.copy(busy = true, status = UiText.of(R.string.obd_s_reading_faults)) }
            val result = runCatching {
                commandMutex.withLock { parseTroubleCodes(sendCommand("03", COMMAND_TIMEOUT_MS)) }
            }
            result.onSuccess { codes ->
                mutableState.update {
                    it.copy(
                        troubleCodes = codes,
                        troubleCodesRead = true,
                        busy = false,
                        status = if (codes.isEmpty()) UiText.of(R.string.obd_s_no_faults) else UiText.of(R.string.obd_s_faults_found, codes.size),
                    )
                }
            }.onFailure { error ->
                handleCommandError(UiText.of(R.string.obd_s_faults_failed), error)
            }
        }
    }

    fun ensureTroubleCodesRead() {
        val current = mutableState.value
        if (current.connection == ObdConnectionState.CONNECTED &&
            !current.troubleCodesRead &&
            !current.busy
        ) {
            readTroubleCodes()
        }
    }

    fun runCapabilityTest() {
        scope.launch {
            if (mutableState.value.connection != ObdConnectionState.CONNECTED) {
                mutableState.update { it.copy(status = UiText.of(R.string.obd_s_connect_before_test)) }
                return@launch
            }
            if (mutableState.value.busy) return@launch
            mutableState.update {
                it.copy(
                    busy = true,
                    status = UiText.of(R.string.obd_s_testing),
                    capabilityResults = emptyList(),
                    capabilityTestCompleted = false,
                )
            }
            val results = runCatching {
                commandMutex.withLock {
                    CAPABILITY_COMMANDS.mapIndexed { index, specification ->
                        mutableState.update {
                            it.copy(status = UiText.of(R.string.obd_s_test_step, index + 1, CAPABILITY_COMMANDS.size, specification.command))
                        }
                        val response = runCatching {
                            sendCommand(specification.command, specification.timeoutMs)
                        }.getOrElse { error ->
                            Log.w(TAG, "Capability command ${specification.command} failed", error)
                            "ERROR: ${error.message ?: "sin respuesta"}"
                        }
                        summarizeObdCapability(specification.command, specification.title, response)
                            .also { delay(CAPABILITY_COMMAND_DELAY_MS) }
                    }
                }
            }
            results.onSuccess { capabilityResults ->
                val storedDtcResult = capabilityResults
                    .firstOrNull { it.command == "03" && it.available }
                // Los códigos van como argumento del resumen, no hace falta analizar el texto.
                val storedCodes = (storedDtcResult?.summary as? UiText.Res)
                    ?.takeIf { it.id == R.string.obdcap_codes }
                    ?.args?.getOrNull(1)?.toString()
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter { it.matches(Regex("[PCBU][0-3][0-9A-F]{3}")) }
                    .orEmpty()
                mutableState.update {
                    it.copy(
                        capabilityResults = capabilityResults,
                        capabilityTestCompleted = true,
                        troubleCodes = if (storedDtcResult != null) storedCodes else it.troubleCodes,
                        troubleCodesRead = it.troubleCodesRead || storedDtcResult != null,
                        busy = false,
                        status = UiText.of(R.string.obd_s_test_done),
                    )
                }
            }.onFailure { error ->
                handleCommandError(UiText.of(R.string.obd_s_test_failed), error)
            }
        }
    }

    /**
     * Safe DDS preflight. SAE OBD-II only defines vehicle speed (010D), not four individual wheel
     * speeds. This verifies the live global PID and records why proprietary ABS guesses are not
     * sent. A later validated Opel address/PID can feed TireMonitoringRepository from here.
     */
    fun runWheelSpeedTest() {
        scope.launch {
            if (mutableState.value.connection != ObdConnectionState.CONNECTED) {
                mutableState.update { it.copy(status = UiText.of(R.string.obd_s_connect_before_dds)) }
                return@launch
            }
            if (mutableState.value.busy) return@launch
            mutableState.update {
                it.copy(
                    busy = true,
                    status = UiText.of(R.string.obd_s_dds_checking),
                    wheelSpeedTestStatus = null,
                    wheelSpeedTestCompleted = false,
                )
            }
            val result = runCatching {
                commandMutex.withLock {
                    val response = sendCommand("010D", COMMAND_TIMEOUT_MS)
                    val speed = parsePid(response, "0D")?.firstOrNull()
                    if (speed == null) {
                        UiText.of(R.string.obd_dds_no_reply)
                    } else {
                        UiText.of(R.string.obd_dds_global_only, speed)
                    }
                }
            }
            result.onSuccess { summary ->
                mutableState.update {
                    it.copy(
                        busy = false,
                        status = UiText.of(R.string.obd_s_dds_done),
                        wheelSpeedTestStatus = summary,
                        wheelSpeedTestCompleted = true,
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        busy = false,
                        status = UiText.of(R.string.obd_s_dds_failed),
                        wheelSpeedTestStatus = error.uiText(),
                        wheelSpeedTestCompleted = true,
                    )
                }
            }
        }
    }

    fun clearTroubleCodes() {
        scope.launch {
            if (mutableState.value.connection != ObdConnectionState.CONNECTED) return@launch
            mutableState.update { it.copy(busy = true, status = UiText.of(R.string.obd_s_clearing)) }
            val result = runCatching {
                commandMutex.withLock { sendCommand("04", COMMAND_TIMEOUT_MS) }
            }
            result.onSuccess {
                mutableState.update {
                    it.copy(
                        troubleCodes = emptyList(),
                        troubleCodesRead = true,
                        busy = false,
                        status = UiText.of(R.string.obd_s_cleared),
                    )
                }
            }.onFailure { error ->
                handleCommandError(UiText.of(R.string.obd_s_clear_failed), error)
            }
        }
    }

    private suspend fun connectionLoop(address: String) {
        while (scope.isActive && preferences()?.getBoolean(KEY_ENABLED, false) == true) {
            try {
                ensureBluetoothEnabled()
                mutableState.update {
                    it.copy(
                        connection = ObdConnectionState.CONNECTING,
                        status = UiText.of(R.string.obd_s_connecting, it.selectedName ?: address),
                    )
                }
                val device = adapter?.getRemoteDevice(address)
                    ?: error("Bluetooth adapter unavailable")
                val newSocket = createSocket(device)
                socket = newSocket
                withTimeout(CONNECT_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) { newSocket.connect() }
                }
                initializeElm327()
                mutableState.update {
                    it.copy(connection = ObdConnectionState.CONNECTED, status = UiText.of(R.string.obd_s_connected))
                }
                scheduleAutomaticTroubleCodeRead(newSocket)
                pollLiveData()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "OBD2 connection failed", error)
                closeSocket()
                mutableState.update {
                    it.copy(
                        connection = ObdConnectionState.ERROR,
                        status = UiText.of(R.string.obd_s_retrying),
                        busy = false,
                    )
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun ensureBluetoothEnabled() {
        val bluetoothAdapter = adapter ?: error("Bluetooth adapter unavailable")
        if (bluetoothAdapter.isEnabled) return
        mutableState.update {
            it.copy(
                connection = ObdConnectionState.CONNECTING,
                status = UiText.of(R.string.obd_s_bluetooth_off),
            )
        }
        // The production TS10 runs Android 10, where BLUETOOTH_ADMIN still permits this recovery.
        // Android 13+ deliberately forbids apps from enabling the radio, so the loop simply keeps
        // retrying there and will reconnect as soon as the user or firmware enables it.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) bluetoothAdapter.enable()
        val deadline = SystemClock.elapsedRealtime() + BLUETOOTH_ENABLE_TIMEOUT_MS
        while (!bluetoothAdapter.isEnabled && SystemClock.elapsedRealtime() < deadline) {
            delay(250L)
        }
        check(bluetoothAdapter.isEnabled) { "Bluetooth is still off" }
    }

    @Suppress("MissingPermission")
    private fun createSocket(device: BluetoothDevice): BluetoothSocket =
        device.createRfcommSocketToServiceRecord(SPP_UUID)

    private suspend fun initializeElm327() {
        for (command in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT1")) {
            commandMutex.withLock { sendCommand(command, if (command == "ATZ") RESET_TIMEOUT_MS else COMMAND_TIMEOUT_MS) }
            delay(80)
        }
        optimizedRequests = null
        supportedPids = querySupportedPids()
        mutableState.update {
            it.copy(
                coolantCelsius = null,
                oilCelsius = null,
                rpm = null,
                speedKmh = null,
                fuelPercent = null,
                massAirFlowGps = null,
                fuelRateLitersPerHour = null,
                estimatedFuelRateLitersPerHour = null,
                consumptionLitersPer100Km = null,
                supportedPids = supportedPids,
                optimizedRequests = null,
                pollCycleDurationMs = null,
                capabilityResults = emptyList(),
                capabilityTestCompleted = false,
                troubleCodes = emptyList(),
                troubleCodesRead = false,
            )
        }
    }

    private fun scheduleAutomaticTroubleCodeRead(connectedSocket: BluetoothSocket) {
        automaticDtcJob?.cancel()
        automaticDtcJob = scope.launch {
            delay(AUTOMATIC_DTC_DELAY_MS)
            if (socket === connectedSocket &&
                connectedSocket.isConnected &&
                mutableState.value.connection == ObdConnectionState.CONNECTED
            ) {
                ensureTroubleCodesRead()
            }
        }
    }

    private suspend fun querySupportedPids(): Set<Int>? {
        val result = linkedSetOf<Int>()
        var base = 0x00
        var receivedAnyPage = false
        while (base <= 0x40) {
            val pid = base.toString(16).padStart(2, '0').uppercase(Locale.US)
            val bytes = commandMutex.withLock {
                parsePid(sendCommand("01$pid", COMMAND_TIMEOUT_MS), pid)
            }
            if (bytes == null || bytes.size < 4) break
            receivedAnyPage = true
            for (offset in 1..32) {
                val byteIndex = (offset - 1) / 8
                val bitIndex = 7 - ((offset - 1) % 8)
                if ((bytes[byteIndex] and (1 shl bitIndex)) != 0) result += base + offset
            }
            val nextBase = base + 0x20
            if (nextBase !in result) break
            base = nextBase
        }
        return result.takeIf { receivedAnyPage }
    }

    private suspend fun pollLiveData() {
        var cycle = 0L
        while (scope.isActive && socket?.isConnected == true) {
            if (mutableState.value.busy) {
                delay(POLL_DELAY_MS)
                continue
            }
            val cycleStarted = SystemClock.elapsedRealtime()
            val requestedPids = buildList {
                addSupported(0x0C)
                addSupported(0x0D)
                if (isPidSupported(0x5E)) add(0x5E) else addSupported(0x10)
                if (cycle % SLOW_PID_CYCLE_DIVISOR == 0L) {
                    addSupported(0x05)
                    addSupported(0x2F)
                    addSupported(0x5C)
                    if (isPidSupported(0x5E)) addSupported(0x10)
                }
            }.distinct()
            val payloads = readPidPayloads(requestedPids)
            val coolant = payloads[0x05]?.firstOrNull()?.minus(40)
            val rpm = payloads[0x0C]?.let { bytes ->
                if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 4 else null
            }
            val speed = payloads[0x0D]?.firstOrNull()
            val maf = payloads[0x10]?.let { bytes ->
                if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 100f else null
            }
            val fuel = payloads[0x2F]?.firstOrNull()?.let { (it * 100f / 255f).roundToInt() }
            val oil = payloads[0x5C]?.firstOrNull()?.minus(40)
            val fuelRate = payloads[0x5E]?.let { bytes ->
                if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 20f else null
            }
            var publishedSpeed: Int? = null
            var publishedConsumption: Float? = null
            mutableState.update {
                // A clone ELM327 can occasionally return NO DATA for one command while all
                // surrounding PIDs are valid. Preserve the last good sample instead of making
                // individual rows flicker between a value and "No disponible".
                val stableCoolant = coolant ?: it.coolantCelsius
                val stableRpm = rpm ?: it.rpm
                val stableSpeed = speed ?: it.speedKmh
                val stableMaf = maf ?: it.massAirFlowGps
                val stableFuel = fuel ?: it.fuelPercent
                val stableOil = oil ?: it.oilCelsius
                val stableFuelRate = fuelRate ?: it.fuelRateLitersPerHour
                val estimatedFuelRate = stableMaf
                    ?.takeIf { stableRpm != null && stableRpm > 0 }
                    ?.let(::fuelRateFromMaf)
                val effectiveFuelRate = stableFuelRate ?: estimatedFuelRate
                val consumption = calculateConsumptionLitersPer100Km(effectiveFuelRate, stableSpeed)
                publishedSpeed = stableSpeed
                publishedConsumption = consumption
                it.copy(
                    connection = ObdConnectionState.CONNECTED,
                    status = UiText.of(R.string.obd_s_data_updated),
                    coolantCelsius = stableCoolant,
                    rpm = stableRpm,
                    speedKmh = stableSpeed,
                    massAirFlowGps = stableMaf,
                    fuelPercent = stableFuel,
                    oilCelsius = stableOil,
                    fuelRateLitersPerHour = stableFuelRate,
                    estimatedFuelRateLitersPerHour = estimatedFuelRate,
                    consumptionLitersPer100Km = consumption,
                    optimizedRequests = optimizedRequests,
                    pollCycleDurationMs = SystemClock.elapsedRealtime() - cycleStarted,
                    lastCommand = lastCommand,
                    lastResponse = lastResponse,
                    lastUpdateElapsedMs = SystemClock.elapsedRealtime(),
                )
            }
            fuel?.let(FuelRangeEstimator::updateObdFuelPercent)
            FuelRangeEstimator.addConsumptionSample(publishedSpeed, publishedConsumption)
            cycle++
            delay(POLL_DELAY_MS)
        }
        error("Bluetooth connection closed")
    }

    private fun MutableList<Int>.addSupported(pid: Int) {
        if (isPidSupported(pid)) add(pid)
    }

    private fun fuelRateFromMaf(massAirFlowGps: Float): Float =
        massAirFlowGps * 3600f /
            (GASOLINE_STOICHIOMETRIC_RATIO * GASOLINE_GRAMS_PER_LITER)

    private fun isPidSupported(pid: Int): Boolean = supportedPids?.let { pid in it } != false

    private suspend fun readPidPayloads(pids: List<Int>): Map<Int, List<Int>> {
        if (pids.isEmpty()) return emptyMap()
        if (pids.size > MAX_MULTI_PID_COUNT) {
            return pids.chunked(MAX_MULTI_PID_COUNT)
                .flatMap { readPidPayloads(it).entries }
                .associate { it.toPair() }
        }
        if (pids.size > 1 && optimizedRequests != false) {
            val optimizedResult = runCatching {
                commandMutex.withLock {
                    val command = "01" + pids.joinToString("") { "%02X".format(Locale.US, it) }
                    val response = sendCommand(command, COMMAND_TIMEOUT_MS)
                    pids.mapNotNull { pid ->
                        parsePid(response, "%02X".format(Locale.US, pid))?.let { pid to it }
                    }.toMap()
                }
            }.getOrNull()
            if (optimizedResult != null && optimizedResult.size >= minOf(2, pids.size)) {
                optimizedRequests = true
                if (optimizedResult.size == pids.size) return optimizedResult
                return optimizedResult + readPidsIndividually(pids.filterNot(optimizedResult::containsKey))
            }
            // Multi-PID requests are normally available on CAN. Older K-line ECUs and some
            // ELM327 clones reject them, so remember the failure for this connection.
            optimizedRequests = false
        }

        return readPidsIndividually(pids)
    }

    private suspend fun readPidsIndividually(pids: List<Int>): Map<Int, List<Int>> =
        pids.mapNotNull { pid ->
            runCatching {
                commandMutex.withLock {
                    val pidHex = "%02X".format(Locale.US, pid)
                    parsePid(sendCommand("01$pidHex", COMMAND_TIMEOUT_MS), pidHex)
                }
            }.getOrNull()?.let { pid to it }
        }.toMap()

    private suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val activeSocket = socket ?: error("OBD2 socket not connected")
        val output = activeSocket.outputStream
        val input = activeSocket.inputStream
        drain(input)
        lastCommand = command
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val response = StringBuilder()
        while (SystemClock.elapsedRealtime() < deadline) {
            while (input.available() > 0) {
                val value = input.read()
                if (value < 0) error("OBD2 connection closed")
                val character = value.toChar()
                if (character == '>') {
                    lastResponse = response.toString().take(MAX_EXPORTED_RESPONSE_CHARS)
                    return response.toString()
                }
                response.append(character)
            }
            delay(20)
        }
        error("ELM327 no respondio a $command")
    }

    private fun drain(input: InputStream) {
        while (input.available() > 0) input.read()
    }

    private fun parsePid(response: String, pid: String): List<Int>? {
        if (response.contains("NO DATA", ignoreCase = true) ||
            response.contains("UNABLE TO CONNECT", ignoreCase = true)
        ) return null
        val normalized = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val marker = "41$pid"
        val markerIndex = normalized.indexOf(marker)
        if (markerIndex < 0) return null
        val payload = normalized.substring(markerIndex + marker.length)
        return payload.chunked(2).mapNotNull { it.takeIf { pair -> pair.length == 2 }?.toIntOrNull(16) }
    }

    private fun parseTroubleCodes(response: String): List<String> {
        if (response.contains("NO DATA", ignoreCase = true)) return emptyList()
        return response
            .uppercase(Locale.US)
            .lineSequence()
            .map { it.replace(Regex("[^0-9A-F]"), "") }
            .flatMap { line ->
                val start = line.indexOf("43")
                if (start < 0) emptySequence() else line.substring(start + 2).chunkedSequence(4)
            }
            .mapNotNull(::decodeTroubleCode)
            .distinct()
            .toList()
    }

    private fun decodeTroubleCode(raw: String): String? {
        if (raw.length != 4 || raw == "0000") return null
        val first = raw.substring(0, 2).toIntOrNull(16) ?: return null
        val family = "PCBU"[(first shr 6) and 0x03]
        val digit = (first shr 4) and 0x03
        return "$family$digit${raw.substring(1)}"
    }

    private fun handleCommandError(message: UiText, error: Throwable) {
        Log.w(TAG, message.toString(), error)
        mutableState.update { it.copy(busy = false, status = message) }
    }

    private fun updateIdleState(enabled: Boolean) {
        if (enabled) return
        mutableState.update {
            it.copy(
                connection = if (it.selectedAddress == null) ObdConnectionState.NO_DEVICE else ObdConnectionState.DISABLED,
                status = if (it.selectedAddress == null) UiText.of(R.string.obd_s_select_adapter) else UiText.of(R.string.obd_s_disconnected),
            )
        }
    }

    private fun closeSocket() {
        automaticDtcJob?.cancel()
        automaticDtcJob = null
        runCatching { socket?.close() }
        socket = null
        supportedPids = null
        optimizedRequests = null
        lastCommand = null
        lastResponse = null
    }

    private fun preferences() =
        context?.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "Obd2Client"
    private const val PREFERENCES_FILE = "obd2_preferences"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val POLL_DELAY_MS = 100L
    private const val SLOW_PID_CYCLE_DIVISOR = 5L
    private const val MAX_MULTI_PID_COUNT = 6
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val BLUETOOTH_ENABLE_TIMEOUT_MS = 8_000L
    private const val COMMAND_TIMEOUT_MS = 2_500L
    private const val RESET_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 12_000L
    private const val GASOLINE_STOICHIOMETRIC_RATIO = 14.7f
    private const val GASOLINE_GRAMS_PER_LITER = 745f
    private const val MAX_EXPORTED_RESPONSE_CHARS = 500
    private const val CAPABILITY_COMMAND_DELAY_MS = 80L
    private const val AUTOMATIC_DTC_DELAY_MS = 2_500L
    private val CAPABILITY_COMMANDS = listOf(
        CapabilityCommand("ATI", R.string.obdcap_version),
        CapabilityCommand("AT@1", R.string.obdcap_description),
        CapabilityCommand("ATDP", R.string.obdcap_protocol),
        CapabilityCommand("ATDPN", R.string.obdcap_protocol_number),
        CapabilityCommand("ATRV", R.string.obdcap_voltage),
        CapabilityCommand("0100", R.string.obdcap_pids_01),
        CapabilityCommand("0120", R.string.obdcap_pids_21),
        CapabilityCommand("0140", R.string.obdcap_pids_41),
        CapabilityCommand("0101", R.string.obdcap_monitors),
        CapabilityCommand("0902", R.string.obdcap_vin, timeoutMs = 4_000L),
        CapabilityCommand("03", R.string.obdcap_dtc_stored),
        CapabilityCommand("07", R.string.obdcap_dtc_pending),
        CapabilityCommand("0A", R.string.obdcap_dtc_permanent),
    )
    private data class CapabilityCommand(
        val command: String,
        @StringRes val title: Int,
        val timeoutMs: Long = COMMAND_TIMEOUT_MS,
    )
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}

internal fun summarizeObdCapability(command: String, @StringRes title: Int, response: String): ObdCapabilityResult {
    val compact = response
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it.equals(command, ignoreCase = true) || it.equals("SEARCHING...", ignoreCase = true) }
        .joinToString(" ")
        .take(240)
    val unavailable = response.contains("NO DATA", ignoreCase = true) ||
        response.contains("UNABLE TO CONNECT", ignoreCase = true) ||
        response.contains("ERROR", ignoreCase = true) ||
        response.contains("?", ignoreCase = true)
    if (unavailable) {
        return ObdCapabilityResult(command, title, UiText.of(R.string.obdcap_unavailable, compact.ifBlank { null } ?: UiText.of(R.string.obdcap_no_reply)), compact, false)
    }
    val summary = when (command) {
        "ATI", "AT@1", "ATDP", "ATDPN", "ATRV" -> if (compact.isBlank()) UiText.of(R.string.obdcap_empty) else UiText.Raw(compact)
        "0100", "0120", "0140" -> summarizePidMap(command, response)
        "0101" -> summarizeMonitorStatus(response)
        "0902" -> parseVin(response)?.let { UiText.Raw("VIN: $it") } ?: UiText.of(R.string.obdcap_vin_unreadable)
        "03" -> summarizeDtcMode(response, "43")
        "07" -> summarizeDtcMode(response, "47")
        "0A" -> summarizeDtcMode(response, "4A")
        else -> UiText.Raw(compact)
    }
    return ObdCapabilityResult(command, title, summary, compact, true)
}

private fun summarizePidMap(command: String, response: String): UiText {
    val base = command.takeLast(2).toInt(16)
    val payload = parseModePidPayload(response, "41${command.takeLast(2)}")
        ?.take(4)
        ?: return UiText.of(R.string.obdcap_map_unreadable)
    val pids = buildList {
        for (offset in 1..32) {
            val byteIndex = (offset - 1) / 8
            val bitIndex = 7 - ((offset - 1) % 8)
            if ((payload[byteIndex] and (1 shl bitIndex)) != 0) add("01%02X".format(Locale.US, base + offset))
        }
    }
    return if (pids.isEmpty()) UiText.of(R.string.obdcap_no_pids) else UiText.of(R.string.obdcap_pids, pids.size, pids.joinToString(" "))
}

private fun summarizeMonitorStatus(response: String): UiText {
    val bytes = parseModePidPayload(response, "4101") ?: return UiText.of(R.string.obdcap_status_unreadable)
    val status = bytes.firstOrNull() ?: return UiText.of(R.string.obdcap_status_unreadable)
    val mil = if ((status and 0x80) != 0) UiText.of(R.string.obdcap_mil_on) else UiText.of(R.string.obdcap_mil_off)
    val dtcCount = status and 0x7F
    val monitors = bytes.drop(1).take(3).joinToString(" ") { "%02X".format(Locale.US, it) }
    return UiText.of(R.string.obdcap_monitor_summary, mil, dtcCount, monitors.ifBlank { "--" })
}

private fun parseVin(response: String): String? {
    val characters = response.lineSequence().flatMap { line ->
        val normalized = line.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val marker = normalized.indexOf("4902")
        if (marker < 0) return@flatMap emptySequence()
        normalized.substring(marker + 4)
            .chunkedSequence(2)
            .drop(1)
            .mapNotNull { it.toIntOrNull(16)?.toChar() }
    }.filter { it.code in 0x20..0x7E }.joinToString("")
    return characters.takeIf { it.length in 11..20 }
}

private fun summarizeDtcMode(response: String, marker: String): UiText {
    val codes = response.lineSequence()
        .map { it.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "") }
        .flatMap { line ->
            val start = line.indexOf(marker)
            if (start < 0) emptySequence() else line.substring(start + marker.length).chunkedSequence(4)
        }
        .mapNotNull(::decodeCapabilityTroubleCode)
        .distinct()
        .toList()
    return if (codes.isEmpty()) UiText.of(R.string.obdcap_no_codes) else UiText.of(R.string.obdcap_codes, codes.size, codes.joinToString())
}

private fun decodeCapabilityTroubleCode(raw: String): String? {
    if (raw.length != 4 || raw == "0000") return null
    val first = raw.substring(0, 2).toIntOrNull(16) ?: return null
    return "${"PCBU"[(first shr 6) and 0x03]}${(first shr 4) and 0x03}${raw.substring(1)}"
}

private fun parseModePidPayload(response: String, marker: String): List<Int>? {
    val normalized = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
    val start = normalized.indexOf(marker)
    if (start < 0) return null
    return normalized.substring(start + marker.length)
        .chunked(2)
        .mapNotNull { it.takeIf { pair -> pair.length == 2 }?.toIntOrNull(16) }
}

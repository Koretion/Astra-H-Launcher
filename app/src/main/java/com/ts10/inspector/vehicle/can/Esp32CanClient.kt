package com.ts10.inspector.vehicle.can

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.vehicle.TurnSignalFlash
import com.ts10.inspector.vehicle.SteeringAngleMemory
import com.ts10.inspector.vehicle.VehicleMileageTracker
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfiles
import java.io.Writer
import java.util.Locale

internal object Esp32CanClient {
    private val mutableState = MutableStateFlow(Esp32CanState())
    val state: StateFlow<Esp32CanState> = mutableState.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val assembler = Esp32CanChunkAssembler()
    private var profile = VehicleCanProfiles.active
    private val hsVehicleDecoder = HsCanVehicleDecoder(profile)
    private val steeringMemory = SteeringAngleMemory()
    private val swVehicleDecoder = SwCanVehicleDecoder(profile)
    private val msLightingDecoder = MsCanLightingDecoder(profile)
    private val msVehicleDecoder = MsCanVehicleDecoder(profile)
    private val faultDiscoveryTracker = CanFaultDiscoveryTracker()
    private val observedIds = linkedSetOf<Long>()
    private val captureLog = Esp32CanCaptureLog()
    private val stateSnapshotAssembler = Esp32CanStateSnapshotAssembler()
    private val latestLiveFrameUs = mutableMapOf<Pair<Esp32CanBus, Long>, Long>()
    private val initialStateFloorUs = mutableMapOf<Pair<Esp32CanBus, Long>, Long>()
    @Volatile internal var initialStateReceived = false
        private set
    private var context: Context? = null
    private var started = false
    @Volatile private var usbConnected = false
    private var activeTransport = ActiveTransport.NONE
    private var expectedFrameSequence: Long? = null
    private var frameCount = 0L
    private var frameLosses = 0L
    private var chunkLosses = 0L
    private var malformedPackets = 0L
    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0
    private var lastUiUpdateMs = 0L
    private var wheelSpeedUpdatedElapsedMs = 0L
    private var vehicleSpeedUpdatedElapsedMs = 0L
    private var bodyDynamicsUpdatedElapsedMs = 0L
    private var handbrakeUpdatedElapsedMs = 0L
    private var engineUpdatedElapsedMs = 0L
    private var coolantUpdatedElapsedMs = 0L
    private var hsFuelUpdatedElapsedMs = 0L
    private var hsOdometerUpdatedElapsedMs = 0L
    private var swBodyUpdatedElapsedMs = 0L
    private var swTurnUpdatedElapsedMs = 0L
    private var swHandbrakeUpdatedElapsedMs = 0L
    private var lightingUpdatedElapsedMs = 0L
    private var msMotionUpdatedElapsedMs = 0L
    private var msClimateUpdatedElapsedMs = 0L

    fun start(context: Context) {
        this.context = context.applicationContext
        steeringMemory.initialize(context.applicationContext)
        if (mutableState.value.hsSteeringAngleDegrees == null) {
            mutableState.value = mutableState.value.copy(hsSteeringAngleDegrees = steeringMemory.resolve(null, 0L))
        }
        captureLog.initialize(context.applicationContext)
        Esp32BleTransport.attach(context.applicationContext, bleListener)
        if (started) {
            Esp32CanUsbTransport.start(context.applicationContext)
            if (!usbConnected && activeTransport == ActiveTransport.NONE) Esp32BleTransport.scheduleScan(0)
            return
        }
        started = true
        updateStatus(UiText.of(R.string.can_searching_usb))
        Esp32CanUsbTransport.start(context.applicationContext)
        Esp32BleTransport.scheduleScan(USB_DISCOVERY_GRACE_MS)
    }

    internal fun onUsbConnected(name: String) {
        usbConnected = true
        // USB open runs on the main thread; finish initialization before starting its reader.
        run {
            Esp32BleTransport.stopScan()
            Esp32BleTransport.disconnectForUsb()
            resetStreamDecoders()
            stateSnapshotAssembler.reset()
            latestLiveFrameUs.clear()
            initialStateFloorUs.clear()
            initialStateReceived = false
            activeTransport = ActiveTransport.USB
            mutableState.value = state.value.copy(
                status = UiText.of(R.string.can_usb_checking),
                deviceName = name,
                deviceAddress = "USB",
                connected = true,
                subscribed = true,
                streamMode = "USB RAW",
                initialStateStatus = "Requesting last state from GMLAN-USB",
                initialStateResolved = false,
            )
            captureLog.event("USB connected: $name; transport=USB RAW")
        }
    }

    internal fun onUsbDisconnected(status: UiText) {
        usbConnected = false
        mainHandler.post {
            if (activeTransport != ActiveTransport.USB) {
                if (activeTransport == ActiveTransport.NONE) Esp32BleTransport.scheduleScan(0)
                return@post
            }
            activeTransport = ActiveTransport.NONE
            resetVehicleSignals(status)
            captureLog.event("${context?.let { status.english(it) } ?: status}; enabling BLE fallback")
            Esp32BleTransport.scheduleScan(0)
        }
    }

    internal fun acceptUsbRecord(record: Esp32UsbRecord) {
        when (record.type) {
            Esp32CanBleProtocol.RECORD_CAN_FRAME -> {
                val frame = Esp32CanBleProtocol.decodeFrame(record.payload)
                if (frame == null) {
                    malformedPackets++
                    publishUiIfDue()
                } else {
                    handleDecodedFrame(frame)
                }
            }
            2 -> handleUsbStats(record.payload)
            3, 4 -> stateSnapshotAssembler.accept(record)?.let(::restoreInitialState)
            else -> {
                malformedPackets++
                publishUiIfDue()
            }
        }
    }

    /** Se llama en el hilo que decodifica, así el cambio de perfil no compite con una trama a medias. */
    private fun syncProfile() {
        val selected = VehicleCanProfiles.active
        if (selected === profile) return
        profile = selected
        hsVehicleDecoder.profile = selected
        swVehicleDecoder.profile = selected
        msLightingDecoder.profile = selected
        msVehicleDecoder.profile = selected
        TelemetryLog.event("CAN_PROFILE", "active profile ${selected.id}")
    }

    private fun restoreInitialState(snapshot: Esp32CanStateSnapshot) {
        syncProfile()
        val now = SystemClock.elapsedRealtime()
        var restored = 0
        snapshot.frames.forEach { frame ->
            val live = latestLiveFrameUs[frame.bus to frame.identifier]
            // Do not splice a saved ISO-TP message into an in-progress live message.
            if (profile.isCodeDecoderFrame(frame, CanSignals.CLIMATE_ISOTP_DECODER) && live != null) return@forEach
            if (!canRestoreInitialState(frame, snapshot.timestampUs, live, profile)) return@forEach
            initialStateFloorUs[frame.bus to frame.identifier] = frame.timestampUs
            hsVehicleDecoder.accept(frame)
            swVehicleDecoder.accept(frame)
            msLightingDecoder.accept(frame)
            val msUpdate = msVehicleDecoder.accept(frame)
            val measuredAt = (now - (snapshot.timestampUs - frame.timestampUs) / 1_000).coerceAtLeast(0)
            markSourceUpdated(frame, measuredAt, now)
            when (msUpdate) {
                MsCanVehicleUpdate.MOTION -> msMotionUpdatedElapsedMs = measuredAt
                MsCanVehicleUpdate.CLIMATE -> msClimateUpdatedElapsedMs = now
                null -> Unit
            }
            restored++
        }
        initialStateReceived = true
        val message = "Initial states: $restored restored of ${snapshot.frames.size}; ESP=${snapshot.bootId}"
        mutableState.update {
            it.copy(
                initialStateStatus = message,
                initialStateResolved = true,
            )
        }
        captureLog.saveInitialState(snapshot)
        TelemetryLog.event("CAN_INITIAL_STATE", message)
        // No fuel/refuel learning, mileage updates, live sequence accounting or button actions.
        publishUiIfDue(force = true)
    }

    internal fun onInitialStateUnavailable() {
        if (!initialStateReceived) mutableState.update {
            it.copy(
                initialStateStatus = "Snapshot not received; keeping live data",
                initialStateResolved = true,
            )
        }
    }

    fun startCapture() {
        val path = captureLog.start()
        mutableState.value = state.value.copy(
            captureActive = true,
            loggedFrames = 0,
            localLogDrops = 0,
            capturePath = path,
        )
    }

    fun stopCapture() {
        captureLog.stop()
        mutableState.value = state.value.copy(captureActive = false)
    }

    fun clearCapture() {
        captureLog.clear()
        mutableState.value = state.value.copy(loggedFrames = 0, localLogDrops = 0)
    }

    fun appendCaptureTo(destination: Writer) {
        captureLog.appendTo(destination)
    }

    private val bleListener = object : Esp32BleTransport.Listener {
        override val usbConnected: Boolean get() = this@Esp32CanClient.usbConnected
        override val usbActive: Boolean
            get() = this@Esp32CanClient.usbConnected || activeTransport == ActiveTransport.USB
        override val bleActive: Boolean get() = activeTransport == ActiveTransport.BLE

        override fun onBleStatus(status: UiText) = updateStatus(status)

        override fun onBleDeviceFound(name: String, address: String?) {
            mutableState.value = state.value.copy(status = UiText.of(R.string.can_connecting), deviceName = name, deviceAddress = address)
        }

        override fun onBleConnected() {
            activeTransport = ActiveTransport.BLE
            resetStreamDecoders()
            mutableState.value = state.value.copy(
                status = UiText.of(R.string.can_discovering),
                connected = true,
                subscribed = false,
            )
            captureLog.event("BLE connected")
        }

        override fun onBleSubscribed() {
            mutableState.value = state.value.copy(
                status = UiText.of(R.string.can_receiving_ble),
                subscribed = true,
                streamMode = "BLE RAW",
            )
            captureLog.event("CAN notifications enabled")
        }

        override fun onBleDisconnected(status: Int) {
            activeTransport = ActiveTransport.NONE
            assembler.resetStream()
            hsVehicleDecoder.reset()
            msLightingDecoder.reset()
            mutableState.value = state.value.copy(
                status = UiText.of(R.string.can_ble_retrying),
                connected = false,
                subscribed = false,
                hsWheelSpeeds = null,
                hsVehicleSpeedKmh = null,
                hsEngineRpm = null,
                hsCoolantCelsius = null,
                msExteriorLightsOn = null,
            )
            captureLog.event("BLE disconnected status=$status")
        }

        override fun onBleLinkLost(message: String) {
            updateStatus(UiText.Raw(message))
            activeTransport = ActiveTransport.NONE
            mutableState.value = state.value.copy(connected = false, subscribed = false)
        }

        override fun onBleStreamPacket(value: ByteArray) = handleStreamPacket(value)

        override fun onBleStats(value: ByteArray) = handleStats(value)
    }

    private fun handleStreamPacket(value: ByteArray) {
        val result = assembler.accept(value)
        chunkLosses += result.lostChunks
        if (result.malformed) malformedPackets++
        val record = result.record ?: run {
            publishUiIfDue()
            return
        }
        if (result.recordType != Esp32CanBleProtocol.RECORD_CAN_FRAME) {
            malformedPackets++
            publishUiIfDue()
            return
        }
        val frame = Esp32CanBleProtocol.decodeFrame(record)
        if (frame == null) {
            malformedPackets++
            publishUiIfDue()
            return
        }

        handleDecodedFrame(frame)
    }

    private fun handleDecodedFrame(frame: Esp32CanFrame) {
        syncProfile()
        val frameKey = frame.bus to frame.identifier
        latestLiveFrameUs[frameKey] = maxOf(latestLiveFrameUs[frameKey] ?: 0L, frame.timestampUs)
        expectedFrameSequence?.let { expected ->
            val gap = (frame.sequence - expected) and UINT_MASK
            if (gap in 1..MAX_REASONABLE_SEQUENCE_GAP) frameLosses += gap
        }
        expectedFrameSequence = (frame.sequence + 1) and UINT_MASK
        frameCount++
        fpsWindowFrames++
        observedIds += frame.identifier
        faultDiscoveryTracker.accept(frame)
        // USB can contain live frames queued before the state snapshot was taken.
        if (initialStateFloorUs[frameKey]?.let { frame.timestampUs < it } == true) {
            captureLog.append(frame)
            publishUiIfDue()
            return
        }
        hsVehicleDecoder.accept(frame)
        swVehicleDecoder.accept(frame)
        val now = SystemClock.elapsedRealtime()
        // The BCM sends its turn-lamp frame once per flash: it is the real lamps' edge.
        if (frame.bus == Esp32CanBus.SW_CAN && profile.carries(frame, CanSignals.TURN_LAMPS) &&
            swVehicleDecoder.signals.let { it.leftIndicator || it.rightIndicator }
        ) {
            TurnSignalFlash.onFlashFrame(frame.timestampUs, now)
        }
        if (frame.bus == Esp32CanBus.HS_CAN && profile.carries(frame, CanSignals.VEHICLE_SPEED_KMH)) {
            hsVehicleDecoder.signals.vehicleSpeedKmh?.toInt()?.let(FuelRangeEstimator::updateCanSpeed)
        }
        if (frame.bus == Esp32CanBus.HS_CAN && profile.carries(frame, CanSignals.FUEL_SENDER_RAW)) {
            hsFuelUpdatedElapsedMs = now
            hsVehicleDecoder.signals.fuelRawByte?.let(FuelRangeEstimator::updateHsFuelRawByte)
        }
        if (frame.bus == Esp32CanBus.SW_CAN && profile.carries(frame, CanSignals.FUEL_SENDER_RAW) &&
            now - hsFuelUpdatedElapsedMs > HS_FUEL_FALLBACK_TIMEOUT_MS
        ) {
            swVehicleDecoder.signals.fuelRawByte?.let(FuelRangeEstimator::updateHsFuelRawByte)
        }
        if (frame.bus == Esp32CanBus.HS_CAN && profile.carries(frame, CanSignals.ODOMETER_KM)) {
            hsOdometerUpdatedElapsedMs = now
            hsVehicleDecoder.signals.odometerKm?.let {
                if (it != mutableState.value.hsOdometerKm) {
                    VehicleMileageTracker.updateVehicleOdometer(it, "GMLAN-USB HS-CAN 0x4A0")
                }
            }
        }
        if (frame.bus == Esp32CanBus.SW_CAN && profile.carries(frame, CanSignals.ODOMETER_KM) &&
            now - hsOdometerUpdatedElapsedMs > HS_ODOMETER_FALLBACK_TIMEOUT_MS
        ) {
            swVehicleDecoder.signals.odometerKm?.let {
                if (it != mutableState.value.swOdometerKm) {
                    VehicleMileageTracker.updateVehicleOdometer(it, "GMLAN-USB SW-CAN 0x190")
                }
            }
        }
        msLightingDecoder.accept(frame)
        val msUpdate = msVehicleDecoder.accept(frame)
        markSourceUpdated(frame, now, now)
        when (msUpdate) {
            MsCanVehicleUpdate.MOTION -> msMotionUpdatedElapsedMs = now
            MsCanVehicleUpdate.CLIMATE -> msClimateUpdatedElapsedMs = now
            null -> Unit
        }
        if (!captureLog.append(frame)) {
            mutableState.value = state.value.copy(localLogDrops = captureLog.droppedFrames)
        }
        publishUiIfDue(frame)
    }

    private fun handleUsbStats(value: ByteArray) {
        val stats = Esp32CanBleProtocol.decodeUsbStats(value)
        if (stats == null) {
            malformedPackets++
            publishUiIfDue(force = true)
            return
        }
        val received = stats.hsReceived + stats.msReceived + stats.swReceived
        val errors = stats.hsErrors + stats.msErrors + stats.swErrors
        mutableState.value = state.value.copy(
            status = usbBusStatus(stats),
            remoteReceived = received,
            remoteQueueDrops = stats.queueDrops,
            remoteNoClientDrops = stats.noClientDrops,
            remoteFilteredFrames = stats.filteredFrames,
            remoteTwaiErrors = errors,
            remoteHsStarted = stats.hsStarted,
            remoteMsStarted = stats.msStarted,
            remoteSwStarted = stats.swStarted,
            remoteHsReceived = stats.hsReceived,
            remoteMsReceived = stats.msReceived,
            remoteSwReceived = stats.swReceived,
            remoteHsErrors = stats.hsErrors,
            remoteMsErrors = stats.msErrors,
            remoteSwErrors = stats.swErrors,
            remoteSwBusErrorAlerts = stats.swBusErrorAlerts,
            remoteSwRxQueueFullAlerts = stats.swRxQueueFullAlerts,
            remoteSwErrorPassiveAlerts = stats.swErrorPassiveAlerts,
            remoteSwBusOffAlerts = stats.swBusOffAlerts,
            remoteSwAboveErrorWarningAlerts = stats.swAboveErrorWarningAlerts,
            remoteSwBelowErrorWarningAlerts = stats.swBelowErrorWarningAlerts,
            remoteSwControllerState = stats.swControllerState,
            remoteSwMessagesPending = stats.swMessagesPending,
            remoteSwTxErrorCounter = stats.swTxErrorCounter,
            remoteSwRxErrorCounter = stats.swRxErrorCounter,
            remoteSwRxMissedCount = stats.swRxMissedCount,
            remoteSwRxOverrunCount = stats.swRxOverrunCount,
            remoteSwBusErrorCount = stats.swBusErrorCount,
            remoteSwRxEdges = stats.swRxEdges,
            remoteSwRxLevelHigh = stats.swRxLevelHigh,
            bitrateKbps = 500,
            streamMode = "USB RAW",
            loggedFrames = captureLog.loggedFrames,
            localLogDrops = captureLog.droppedFrames,
        )
        captureLog.usbStats(stats)
        publishUiIfDue(force = true)
    }

    private fun usbBusStatus(stats: Esp32CanUsbStats): UiText = UiText.of(
        R.string.can_usb_buses,
        busReceptionStatus("HS", stats.hsStarted, stats.hsReceived),
        busReceptionStatus("MS", stats.msStarted, stats.msReceived),
        busReceptionStatus("LS", stats.swStarted, stats.swReceived),
    )

    private fun busReceptionStatus(label: String, started: Boolean, received: Long): UiText = when {
        !started -> UiText.of(R.string.can_bus_failed, label)
        received > 0 -> UiText.of(R.string.can_bus_receiving, label)
        else -> UiText.of(R.string.can_bus_no_frames, label)
    }

    private fun handleStats(value: ByteArray) {
        val stats = Esp32CanBleProtocol.decodeStats(value)
        if (stats == null) {
            malformedPackets++
            publishUiIfDue(force = true)
            return
        }
        captureLog.stats(stats)
        mutableState.value = state.value.copy(
            remoteReceived = stats.receivedFrames,
            remoteQueueDrops = stats.queueDrops,
            remoteNoClientDrops = stats.noClientDrops,
            remoteFilteredFrames = stats.filteredFrames,
            remoteTwaiErrors = stats.twaiErrors,
            bitrateKbps = stats.bitrateKbps,
            streamMode = stats.streamMode,
            loggedFrames = captureLog.loggedFrames,
            localLogDrops = captureLog.droppedFrames,
        )
        publishUiIfDue(force = true)
    }

    /** Marca la frescura de cada fuente según la trama que la transporta en el perfil. */
    private fun markSourceUpdated(frame: Esp32CanFrame, measuredAt: Long, now: Long) {
        when (frame.bus) {
            Esp32CanBus.HS_CAN -> {
                if (profile.carries(frame, CanSignals.WHEEL_SPEED_FL_KMH)) wheelSpeedUpdatedElapsedMs = measuredAt
                if (profile.carries(frame, CanSignals.VEHICLE_SPEED_KMH)) vehicleSpeedUpdatedElapsedMs = measuredAt
                if (profile.carries(frame, CanSignals.ENGINE_RPM)) engineUpdatedElapsedMs = measuredAt
                if (profile.carries(frame, CanSignals.TURN_STATE)) bodyDynamicsUpdatedElapsedMs = now
                if (profile.carries(frame, CanSignals.HANDBRAKE)) handbrakeUpdatedElapsedMs = now
                if (profile.carries(frame, CanSignals.COOLANT_C)) coolantUpdatedElapsedMs = measuredAt
            }
            Esp32CanBus.SW_CAN -> {
                if (profile.carries(frame, CanSignals.DRIVER_DOOR_OPEN)) swBodyUpdatedElapsedMs = now
                if (profile.carries(frame, CanSignals.TURN_LAMPS)) swTurnUpdatedElapsedMs = now
                if (profile.carries(frame, CanSignals.HANDBRAKE)) swHandbrakeUpdatedElapsedMs = now
            }
            Esp32CanBus.MS_CAN -> if (profile.carries(frame, CanSignals.EXTERIOR_LIGHTS_ON)) lightingUpdatedElapsedMs = now
            Esp32CanBus.UNKNOWN -> Unit
        }
    }

    private fun publishUiIfDue(frame: Esp32CanFrame? = null, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        val elapsed = now - fpsWindowStartMs
        var fps = state.value.framesPerSecond
        if (elapsed >= 1000) {
            fps = ((fpsWindowFrames * 1000L) / elapsed.coerceAtLeast(1)).toInt()
            fpsWindowStartMs = now
            fpsWindowFrames = 0
        }
        if (!force && now - lastUiUpdateMs < UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateMs = now
        val previous = state.value
        val hsSignals = hsVehicleDecoder.signals
        val swSignals = swVehicleDecoder.signals
        val msSignals = msVehicleDecoder.signals
        mutableState.value = previous.copy(
            frames = frameCount,
            framesPerSecond = fps,
            distinctIds = observedIds.size,
            chunkLosses = chunkLosses,
            frameLosses = frameLosses,
            malformedPackets = malformedPackets,
            loggedFrames = captureLog.loggedFrames,
            localLogDrops = captureLog.droppedFrames,
            faultCandidates = faultDiscoveryTracker.snapshot(),
            lastFrame = frame?.displayLine() ?: previous.lastFrame,
            // Until the sensor initialises (first wheel movement) the last known angle is shown.
            hsSteeringAngleDegrees = steeringMemory.resolve(hsSignals.steeringAngleDegrees, now),
            hsLeftIndicator = hsSignals.leftIndicator,
            hsRightIndicator = hsSignals.rightIndicator,
            hsExteriorLightsCandidate = hsSignals.exteriorLightsCandidate,
            hsHighBeam = hsSignals.highBeam,
            hsHandbrake = hsSignals.handbrake,
            hsWheelSpeeds = hsSignals.wheelSpeeds,
            hsVehicleSpeedKmh = hsSignals.vehicleSpeedKmh,
            hsEngineRpm = hsSignals.engineRpm,
            hsCoolantCelsius = hsSignals.coolantCelsius,
            hsOdometerKm = hsSignals.odometerKm,
            hsFuelRawByte = hsSignals.fuelRawByte,
            swDriverDoorOpen = swSignals.driverDoorOpen,
            swPassengerDoorOpen = swSignals.passengerDoorOpen,
            swTrunkOpen = swSignals.trunkOpen,
            swCentralLocked = swSignals.centralLocked,
            swCentralLockAction = swSignals.centralLockAction,
            swLeftIndicator = swSignals.leftIndicator,
            swRightIndicator = swSignals.rightIndicator,
            swHazard = swSignals.hazard,
            swLampFault = swSignals.lampFault,
            swHandbrake = swSignals.handbrake,
            swServiceBrakePressed = swSignals.serviceBrakePressed,
            swClutchPressed = swSignals.clutchPressed,
            swPedalSwitchRaw = swSignals.pedalSwitchRaw,
            swHighBeamRequest = swSignals.highBeamRequest,
            swWiperMode = swSignals.wiperMode,
            swWindowAction = swSignals.windowAction,
            swCentralControlAction = swSignals.centralControlAction,
            swOdometerKm = swSignals.odometerKm,
            swFuelRawByte = swSignals.fuelRawByte,
            swOutdoorTemperatureCelsius = swSignals.outdoorTemperatureCelsius,
            swBatteryVoltage = swSignals.batteryVoltage,
            swIgnitionKeyIndex = swSignals.ignitionKeyIndex,
            swFrontFogOn = swSignals.frontFogOn,
            swRearFogOn = swSignals.rearFogOn,
            hsWheelSpeedUpdatedElapsedMs = wheelSpeedUpdatedElapsedMs,
            hsVehicleSpeedUpdatedElapsedMs = vehicleSpeedUpdatedElapsedMs,
            hsBodyDynamicsUpdatedElapsedMs = bodyDynamicsUpdatedElapsedMs,
            hsHandbrakeUpdatedElapsedMs = handbrakeUpdatedElapsedMs,
            hsEngineUpdatedElapsedMs = engineUpdatedElapsedMs,
            hsCoolantUpdatedElapsedMs = coolantUpdatedElapsedMs,
            swBodyUpdatedElapsedMs = swBodyUpdatedElapsedMs,
            swTurnUpdatedElapsedMs = swTurnUpdatedElapsedMs,
            swHandbrakeUpdatedElapsedMs = swHandbrakeUpdatedElapsedMs,
            msExteriorLightsOn = msLightingDecoder.exteriorLightsOn,
            msLightingUpdatedElapsedMs = lightingUpdatedElapsedMs,
            msVehicleSpeedKmh = msSignals.vehicleSpeedKmh,
            msEngineRpm = msSignals.engineRpm,
            msReverse = msSignals.reverse,
            msMotionState = msSignals.motionState,
            msClimate = msSignals.climate,
            msMotionUpdatedElapsedMs = msMotionUpdatedElapsedMs,
            msClimateUpdatedElapsedMs = msClimateUpdatedElapsedMs,
        )
    }

    private fun resetStreamDecoders() {
        latestLiveFrameUs.clear()
        initialStateFloorUs.clear()
        stateSnapshotAssembler.reset()
        assembler.resetStream()
        hsVehicleDecoder.reset()
        swVehicleDecoder.reset()
        FuelRangeEstimator.resetHsFuelSamples()
        msLightingDecoder.reset()
        msVehicleDecoder.reset()
        faultDiscoveryTracker.reset()
        expectedFrameSequence = null
    }

    private fun resetVehicleSignals(status: UiText) {
        hsVehicleDecoder.reset()
        swVehicleDecoder.reset()
        FuelRangeEstimator.resetHsFuelSamples()
        msLightingDecoder.reset()
        msVehicleDecoder.reset()
        mutableState.value = state.value.copy(
            status = status,
            connected = false,
            subscribed = false,
            streamMode = "--",
            hsWheelSpeeds = null,
            hsVehicleSpeedKmh = null,
            hsEngineRpm = null,
            hsCoolantCelsius = null,
            hsOdometerKm = null,
            hsFuelRawByte = null,
            swDriverDoorOpen = null,
            swPassengerDoorOpen = null,
            swTrunkOpen = null,
            swLeftIndicator = false,
            swRightIndicator = false,
            swHazard = false,
            swLampFault = null,
            swHandbrake = null,
            swServiceBrakePressed = null,
            swClutchPressed = null,
            swPedalSwitchRaw = null,
            swHighBeamRequest = false,
            swWiperMode = null,
            swWindowAction = null,
            swCentralControlAction = null,
            swOdometerKm = null,
            swFuelRawByte = null,
            swOutdoorTemperatureCelsius = null,
            swBatteryVoltage = null,
            swIgnitionKeyIndex = null,
            swFrontFogOn = null,
            swRearFogOn = null,
            msExteriorLightsOn = null,
            msVehicleSpeedKmh = null,
            msEngineRpm = null,
            msReverse = null,
            msMotionState = null,
            msClimate = null,
        )
    }

    private fun updateStatus(status: UiText) {
        mutableState.value = state.value.copy(status = status)
    }

    private fun Esp32CanFrame.displayLine(): String {
        val idWidth = if (extended) 8 else 3
        val payload = data.joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }
        return "%s %0${idWidth}X#%s seq=%d".format(Locale.US, bus.label, identifier, payload, sequence)
    }

    private const val USB_DISCOVERY_GRACE_MS = 4_000L
    private const val UI_UPDATE_INTERVAL_MS = 250L
    private const val HS_FUEL_FALLBACK_TIMEOUT_MS = 2_000L
    private const val HS_ODOMETER_FALLBACK_TIMEOUT_MS = 2_000L
    private const val UINT_MASK = 0xffffffffL
    private const val MAX_REASONABLE_SEQUENCE_GAP = 1_000_000L

    private enum class ActiveTransport { NONE, USB, BLE }
}

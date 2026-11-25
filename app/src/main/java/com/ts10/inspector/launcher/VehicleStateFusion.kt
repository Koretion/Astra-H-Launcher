package com.ts10.inspector.launcher

import android.os.SystemClock
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.DriverProfilePreferences
import com.ts10.inspector.vehicle.SpeedWarningController
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.can.Esp32CanState
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.obd.ObdConnectionState
import com.ts10.inspector.vehicle.resolveVehicleSpeed
import com.ts10.inspector.vehicle.tires.TireMonitoringRepository
import com.ts10.inspector.vehicle.tires.TireMonitoringSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Resolves displayed speed and merges ESP32 body signals into the launcher state. */
internal data class DirectCanBodyFreshness(
    val turn: Boolean,
    val exteriorLights: Boolean,
    val highBeam: Boolean,
    val handbrake: Boolean,
)

internal object VehicleStateFusion {
    @Volatile private var gpsSpeedKmh = 0
    @Volatile private var canSpeedSupported = false
    @Volatile private var lastCanSpeedKmh = 0
    @Volatile private var lastCanSpeedUpdate = 0L
    @Volatile private var obdSpeedKmh: Int? = null
    @Volatile private var obdSpeedUpdate = 0L
    @Volatile private var obdSpeedConnected = false
    private var obdSpeedObserverStarted = false
    private var esp32VehicleObserverStarted = false
    @Volatile private var directCanSpeedKmh: Int? = null
    @Volatile private var directCanSpeedUpdate = 0L

    fun onGpsSpeed(speedKmh: Int) {
        gpsSpeedKmh = speedKmh
        updateDisplayedSpeed()
    }

    /**
     * Body signals the GMLAN-USB currently supplies. The QF CAN box must not overwrite them: on
     * boxes without body support it sends empty packets (handbrake off, lights off), which made the
     * dashboard flicker between both sources.
     */
    fun directCanBodyFreshness(
        can: Esp32CanState = Esp32CanClient.state.value,
        now: Long = SystemClock.elapsedRealtime(),
    ): DirectCanBodyFreshness {
        fun fresh(updatedAt: Long) = can.connected && now - updatedAt <= DIRECT_CAN_TIMEOUT_MS
        val dynamics = fresh(can.hsBodyDynamicsUpdatedElapsedMs)
        return DirectCanBodyFreshness(
            turn = fresh(can.swTurnUpdatedElapsedMs) || dynamics,
            exteriorLights = fresh(can.msLightingUpdatedElapsedMs),
            highBeam = dynamics,
            handbrake = (can.swHandbrake != null && fresh(can.swHandbrakeUpdatedElapsedMs)) ||
                (can.hsHandbrake != null && fresh(can.hsHandbrakeUpdatedElapsedMs)),
        )
    }

    /** QF reports 0 km/h on CAN boxes without speed support, so zero only counts once seen moving. */
    fun onQfSpeed(speedKmh: Int, now: Long) {
        if (speedKmh in 0..MAX_CAN_SPEED_KMH && (speedKmh > 0 || canSpeedSupported)) {
            canSpeedSupported = true
            lastCanSpeedKmh = speedKmh
            lastCanSpeedUpdate = now
        }
        updateDisplayedSpeed(now)
    }

    fun onAccessoryOff() {
        gpsSpeedKmh = 0
        canSpeedSupported = false
        lastCanSpeedKmh = 0
        lastCanSpeedUpdate = 0L
        updateDisplayedSpeed()
    }

    fun isMovingByDirectCan(now: Long, minimumSpeedKmh: Int): Boolean = directCanSpeedKmh?.let { speed ->
        speed >= minimumSpeedKmh && now - directCanSpeedUpdate <= DIRECT_CAN_TIMEOUT_MS
    } == true

    fun observeEsp32VehicleSignals() {
        if (esp32VehicleObserverStarted) return
        esp32VehicleObserverStarted = true
        LauncherStore.scope.launch {
            Esp32CanClient.state.collect { can ->
                val now = SystemClock.elapsedRealtime()
                can.swIgnitionKeyIndex?.takeIf { LauncherStore.accessoryOn }?.let { keyIndex ->
                    LauncherStore.applicationContext?.let { context ->
                        val newlyRecognized = DriverProfilePreferences.get(context).selectProfileForIgnitionKey(
                            keyIndex,
                            DashboardPreferences.get(context),
                        )
                        if (newlyRecognized) {
                            HybridOverlayService.hideDriverProfilesIfVisible(context)
                        }
                    }
                }
                val wheelSpeeds = can.hsWheelSpeeds.takeIf {
                    can.connected && now - can.hsWheelSpeedUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                }
                val vehicleSpeed = can.hsVehicleSpeedKmh.takeIf {
                    can.connected && now - can.hsVehicleSpeedUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                }
                if (vehicleSpeed != null || wheelSpeeds != null) {
                    val preferredSpeedKmh: Double = vehicleSpeed?.toDouble()
                        ?: wheelSpeeds!!.values().map(Float::toDouble).average()
                    directCanSpeedKmh = preferredSpeedKmh.roundToInt().coerceAtLeast(0)
                    directCanSpeedUpdate = if (vehicleSpeed != null) {
                        can.hsVehicleSpeedUpdatedElapsedMs
                    } else {
                        can.hsWheelSpeedUpdatedElapsedMs
                    }
                } else {
                    directCanSpeedKmh = null
                    directCanSpeedUpdate = 0L
                }
                if (wheelSpeeds != null) {
                    TireMonitoringRepository.updateWheelSpeeds(
                        wheelSpeeds,
                        TireMonitoringSource.DDS_HS_CAN,
                    )
                }

                val dynamicsFresh = can.connected &&
                    now - can.hsBodyDynamicsUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                val swBodyFresh = can.connected && can.swDriverDoorOpen != null &&
                    now - can.swBodyUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                val swTurnFresh = can.connected &&
                    now - can.swTurnUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                val handbrakeFresh = can.connected && can.hsHandbrake != null &&
                    now - can.hsHandbrakeUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                val swHandbrakeFresh = can.connected && can.swHandbrake != null &&
                    now - can.swHandbrakeUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                val lightingFresh = can.connected &&
                    now - can.msLightingUpdatedElapsedMs <= DIRECT_CAN_TIMEOUT_MS
                LauncherStore.state.update { current ->
                    current.copy(
                        leftBlinker = when {
                            swTurnFresh -> can.swLeftIndicator
                            dynamicsFresh -> can.hsLeftIndicator
                            else -> current.leftBlinker
                        },
                        rightBlinker = when {
                            swTurnFresh -> can.swRightIndicator
                            dynamicsFresh -> can.hsRightIndicator
                            else -> current.rightBlinker
                        },
                        hazard = if (swTurnFresh) can.swHazard else current.hazard,
                        headlights = if (lightingFresh) can.msExteriorLightsOn == true else current.headlights,
                        positionLights = if (lightingFresh) can.msExteriorLightsOn == true else current.positionLights,
                        highBeam = if (dynamicsFresh) can.hsHighBeam else current.highBeam,
                        handbrake = when {
                            swHandbrakeFresh -> can.swHandbrake == true
                            handbrakeFresh -> can.hsHandbrake == true
                            else -> current.handbrake
                        },
                        frontLeftDoorOpen = if (swBodyFresh) can.swDriverDoorOpen == true else current.frontLeftDoorOpen,
                        frontRightDoorOpen = if (swBodyFresh) can.swPassengerDoorOpen == true else current.frontRightDoorOpen,
                        trunkOpen = if (swBodyFresh) can.swTrunkOpen == true else current.trunkOpen,
                        outdoorTemperature = if (!LauncherStore.qfOutsideTemperatureReceived &&
                            can.connected && can.swOutdoorTemperatureCelsius != null
                        ) {
                            String.format(
                                Locale.getDefault(),
                                "%.1f\u00b0",
                                can.swOutdoorTemperatureCelsius,
                            )
                        } else {
                            current.outdoorTemperature
                        },
                    )
                }
                updateDisplayedSpeed(now)
            }
        }
    }

    fun observeObdSpeed() {
        if (obdSpeedObserverStarted) return
        obdSpeedObserverStarted = true
        LauncherStore.scope.launch {
            Obd2Client.state
                .map { state ->
                    Triple(
                        state.connection == ObdConnectionState.CONNECTED,
                        state.speedKmh,
                        state.lastUpdateElapsedMs ?: 0L,
                    )
                }
                .distinctUntilChanged()
                .collect { (connected, speed, updateTime) ->
                    obdSpeedConnected = connected
                    obdSpeedKmh = speed
                    obdSpeedUpdate = updateTime
                    updateDisplayedSpeed()
                }
        }
    }

    private fun updateDisplayedSpeed(now: Long = SystemClock.elapsedRealtime()) {
        val directCanAvailable = directCanSpeedKmh != null &&
            now - directCanSpeedUpdate <= DIRECT_CAN_TIMEOUT_MS
        val qfAvailable = canSpeedSupported &&
            now - lastCanSpeedUpdate <= CAN_SPEED_TIMEOUT_MS
        val obdAvailable = obdSpeedConnected &&
            obdSpeedKmh != null &&
            obdSpeedUpdate > 0L &&
            now - obdSpeedUpdate <= OBD_SPEED_TIMEOUT_MS
        val resolved = resolveVehicleSpeed(
            directCanSpeedKmh = directCanSpeedKmh,
            directCanAvailable = directCanAvailable,
            qfSpeedKmh = lastCanSpeedKmh,
            qfAvailable = qfAvailable,
            obdSpeedKmh = obdSpeedKmh,
            obdAvailable = obdAvailable,
            gpsSpeedKmh = gpsSpeedKmh,
        )
        LauncherStore.state.update { current ->
            if (current.speedKmh == resolved.speedKmh && current.speedSource == resolved.source) {
                current
            } else {
                current.copy(speedKmh = resolved.speedKmh, speedSource = resolved.source)
            }
        }
        LauncherStore.state.value.let { current ->
            SpeedWarningController.evaluate(current.speedKmh, current.speedLimitKmh, now)
        }
    }

    private const val CAN_SPEED_TIMEOUT_MS = 3_000L
    private const val OBD_SPEED_TIMEOUT_MS = 4_000L
    private const val MAX_CAN_SPEED_KMH = 320
}

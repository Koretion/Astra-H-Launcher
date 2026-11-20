package com.ts10.inspector.diagnostics

import com.ts10.inspector.ui.UiText
import android.content.Context
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.vehicle.SpeedWarningPreferences
import com.ts10.inspector.vehicle.buildCanObdParityReport
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.can.Esp32CanBus
import com.ts10.inspector.vehicle.can.busDiagnostic
import com.ts10.inspector.vehicle.can.twaiStateLabel
import com.ts10.inspector.vehicle.fuel.FUEL_TANK_CAPACITY_LITERS
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import com.ts10.inspector.vehicle.fuel.FuelTrackingRepository
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.obd.calculateConsumptionLitersPer100Km
import com.ts10.inspector.vehicle.obd.effectiveFuelRateLitersPerHour
import com.ts10.inspector.vehicle.tires.TireMonitoringRepository
import com.ts10.inspector.vehicle.tires.TirePosition
import java.util.Locale

internal fun StringBuilder.appendEsp32CanRawUsbSection(context: Context) {
    val dashboardSettings = DashboardPreferences.current(context)
    val esp32Can = Esp32CanClient.state.value
    section("ESP32 CAN RAW / USB")
    field(
        "Manual odometer reference",
        dashboardSettings.manualOdometerKm?.let { "%.3f km".format(Locale.US, it) } ?: "--",
    )
    field("Status", esp32Can.status.english(context))
    field("Device", esp32Can.deviceName ?: "--")
    field("Address", esp32Can.deviceAddress ?: "--")
    field("Connected", esp32Can.connected)
    field("Subscribed", esp32Can.subscribed)
    field("Bitrate", "${esp32Can.bitrateKbps} kbit/s")
    field("Frames", esp32Can.frames)
    field("Frames/s", esp32Can.framesPerSecond)
    field("Distinct IDs", esp32Can.distinctIds)
    field("Malformed USB records", esp32Can.malformedPackets)
    field("Lost frame sequences", esp32Can.frameLosses)
    field("Malformed packets", esp32Can.malformedPackets)
    field("ESP received", esp32Can.remoteReceived)
    field("ESP queue drops", esp32Can.remoteQueueDrops)
    field("ESP no-client drops", esp32Can.remoteNoClientDrops)
    field("ESP initial state", esp32Can.initialStateStatus)
    field("ESP filtered frames", esp32Can.remoteFilteredFrames)
    field("ESP stream mode", esp32Can.streamMode)
    field("ESP TWAI errors", esp32Can.remoteTwaiErrors)
    field("ESP HS started / received / errors", esp32Can.busDiagnostic(Esp32CanBus.HS_CAN))
    field("ESP MS started / received / errors", esp32Can.busDiagnostic(Esp32CanBus.MS_CAN))
    field("ESP LS started / received / errors", esp32Can.busDiagnostic(Esp32CanBus.SW_CAN))
    field("ESP LS RX edges / level", "${esp32Can.remoteSwRxEdges} / ${esp32Can.remoteSwRxLevelHigh?.let { if (it) "HIGH" else "LOW" } ?: "--"}")
    field("ESP LS TWAI state", esp32Can.remoteSwControllerState?.let(::twaiStateLabel) ?: "--")
    field("ESP LS bus-error alerts / total", "${esp32Can.remoteSwBusErrorAlerts} / ${esp32Can.remoteSwBusErrorCount}")
    field("ESP LS RX/TX error counters", "${esp32Can.remoteSwRxErrorCounter} / ${esp32Can.remoteSwTxErrorCounter}")
    field("ESP LS queue-full / pending", "${esp32Can.remoteSwRxQueueFullAlerts} / ${esp32Can.remoteSwMessagesPending}")
    field("ESP LS missed / overrun", "${esp32Can.remoteSwRxMissedCount} / ${esp32Can.remoteSwRxOverrunCount}")
    field("ESP LS passive / bus-off", "${esp32Can.remoteSwErrorPassiveAlerts} / ${esp32Can.remoteSwBusOffAlerts}")
    field("ESP LS above/below warning", "${esp32Can.remoteSwAboveErrorWarningAlerts} / ${esp32Can.remoteSwBelowErrorWarningAlerts}")
    field("Logged frames", esp32Can.loggedFrames)
    field("TS10 log drops", esp32Can.localLogDrops)
    field("HS steering angle", esp32Can.hsSteeringAngleDegrees?.let { "%.2f deg".format(it) } ?: "--")
    field("HS left indicator", esp32Can.hsLeftIndicator)
    field("HS right indicator", esp32Can.hsRightIndicator)
    field("HS exterior lights candidate", esp32Can.hsExteriorLightsCandidate)
    field("HS high beam", esp32Can.hsHighBeam)
    field("HS handbrake", esp32Can.hsHandbrake ?: "--")
    field("HS wheel speeds", esp32Can.hsWheelSpeeds?.values()?.joinToString(prefix = "[", postfix = "]") { "%.2f km/h".format(Locale.US, it) } ?: "--")
    field("HS vehicle speed", esp32Can.hsVehicleSpeedKmh?.let { "%.2f km/h".format(Locale.US, it) } ?: "--")
    field("HS engine RPM", esp32Can.hsEngineRpm ?: "--")
    field("HS coolant", esp32Can.hsCoolantCelsius?.let { "$it C" } ?: "--")
    field("HS odometer", esp32Can.hsOdometerKm?.let { "%.3f km".format(Locale.US, it) } ?: "--")
    field("HS fuel raw 0x381 byte 5", esp32Can.hsFuelRawByte?.let { "0x%02X".format(it) } ?: "--")
    field("SW driver door 0x230", esp32Can.swDriverDoorOpen ?: "--")
    field("SW passenger door 0x230", esp32Can.swPassengerDoorOpen ?: "--")
    field("SW trunk 0x230", esp32Can.swTrunkOpen ?: "--")
    field("SW central lock state 0x230", esp32Can.swCentralLocked ?: "--")
    field("SW central lock pulse 0x135", esp32Can.swCentralLockAction?.label ?: "--")
    field("SW left / right / hazard 0x260", "${esp32Can.swLeftIndicator} / ${esp32Can.swRightIndicator} / ${esp32Can.swHazard}")
    field("SW lamp fault 0x260", esp32Can.swLampFault?.label ?: "--")
    field("SW handbrake 0x370", esp32Can.swHandbrake ?: "--")
    field("SW service brake 0x360", esp32Can.swServiceBrakePressed ?: "--")
    field("SW clutch 0x115", esp32Can.swClutchPressed ?: "--")
    field("SW pedal switches raw 0x115", esp32Can.swPedalSwitchRaw?.let { "0x%02X".format(it) } ?: "--")
    field("SW wipers 0x175", esp32Can.swWiperMode?.label ?: "--")
    field("SW last window action 0x160", esp32Can.swWindowAction?.label ?: "--")
    field("SW central controls 0x305", esp32Can.swCentralControlAction?.label ?: "--")
    field("SW odometer 0x190", esp32Can.swOdometerKm?.let { "%.3f km".format(Locale.US, it) } ?: "--")
    field("SW fuel raw 0x375 byte 1", esp32Can.swFuelRawByte?.let { "0x%02X".format(it) } ?: "--")
    field("SW outdoor temperature 0x445", esp32Can.swOutdoorTemperatureCelsius?.let { "%.1f C".format(Locale.US, it) } ?: "--")
    field("SW battery voltage 0x500", esp32Can.swBatteryVoltage?.let { "%.2f V".format(Locale.US, it) } ?: "--")
    field("SW ignition key index 0x425", esp32Can.swIgnitionKeyIndex ?: "--")
    field("SW front fog 0x350", esp32Can.swFrontFogOn ?: "--")
    field("SW rear fog 0x370", esp32Can.swRearFogOn ?: "--")
    field("MS exterior lights", esp32Can.msExteriorLightsOn ?: "--")
    field("MS 0x4E8 speed", esp32Can.msVehicleSpeedKmh?.let { "%.2f km/h".format(Locale.US, it) } ?: "--")
    field("MS 0x4E8 RPM", esp32Can.msEngineRpm ?: "--")
    field("MS 0x4E8 reverse", esp32Can.msReverse ?: "--")
    field("MS 0x4E8 motion state", esp32Can.msMotionState?.let { "0x%02X".format(it) } ?: "--")
    field("MS 0x6C8 temperature", esp32Can.msClimate?.temperature ?: "--")
    field("MS 0x6C8 automatic", esp32Can.msClimate?.automatic ?: "--")
    field("MS 0x6C8 cooling", esp32Can.msClimate?.cooling ?: "--")
    field("MS 0x6C8 fan", esp32Can.msClimate?.fanLevel ?: "AUTO/--")
    field("MS 0x6C8 direction", esp32Can.msClimate?.airDirection ?: "--")
    field("Capture file", esp32Can.capturePath ?: "--")
    field("Last frame", esp32Can.lastFrame)
}

internal fun StringBuilder.appendCanFaultDiscoverySection(context: Context) {
    val esp32Can = Esp32CanClient.state.value
    section("CAN FAULT DISCOVERY")
    field("Candidates", esp32Can.faultCandidates.size)
    field(
        "Confirmed decoder",
        "SW-CAN 0x260#3A217C = right rear indicator/bulb fault with hyperflash",
    )
    field(
        "Unresolved targets",
        "bulb warnings; washer level; coolant level; oil level/status",
    )
    if (esp32Can.faultCandidates.isEmpty()) {
        appendLine("No repeated warning candidates observed in this session")
    } else {
        esp32Can.faultCandidates.forEach { candidate ->
            val periodMs = candidate.averagePeriodMs
                ?.let { "%.1f".format(Locale.US, it) }
                ?: "--"
            appendLine(
                "${candidate.frameLabel} count=${candidate.occurrences} " +
                    "transitions=${candidate.transitionsInto} " +
                    "period_ms=$periodMs " +
                    "confirmed=${candidate.confirmed} meaning=${candidate.meaning.english(context)}",
            )
        }
    }
}

internal fun StringBuilder.appendObd2Elm327Section(context: Context) {
    val obd = Obd2Client.state.value
    val launcher = LauncherRuntime.state.value
    section("OBD2 / ELM327")
    field("Connection", obd.connection)
    field("Status", obd.status.english(context))
    field("Adapter", obd.selectedName ?: "--")
    field(
        "Optimized multi-PID requests",
        when (obd.optimizedRequests) {
            true -> "active"
            false -> "not supported; individual fallback active"
            null -> "not tested yet"
        },
    )
    field("Last poll cycle", obd.pollCycleDurationMs?.let { "$it ms" } ?: "--")
    field(
        "Supported PIDs",
        obd.supportedPids?.sorted()?.joinToString(" ") { "01%02X".format(Locale.US, it) }
            ?: "not reported",
    )
    KNOWN_OBD_SENSORS.forEach { (pid, name) ->
        val support = when {
            obd.supportedPids == null -> "unknown"
            obd.supportedPids.contains(pid) -> "supported"
            else -> "not supported"
        }
        field("01%02X $name".format(Locale.US, pid), "$support; ${obdSensorValue(obd, pid)}")
    }
    val effectiveFuelRate = effectiveFuelRateLitersPerHour(obd)
    field(
        "Effective fuel rate",
        effectiveFuelRate?.let { "%.2f L/h".format(Locale.US, it) } ?: "--",
    )
    field(
        "Instant consumption at displayed speed",
        calculateConsumptionLitersPer100Km(effectiveFuelRate, launcher.speedKmh)
            ?.let { "%.1f L/100 km".format(Locale.US, it) }
            ?: if (effectiveFuelRate != null) "%.2f L/h at standstill".format(Locale.US, effectiveFuelRate) else "--",
    )
    field("Last ELM command", obd.lastCommand ?: "--")
    field("Last ELM response", obd.lastResponse?.replace("\r", "\\r")?.replace("\n", "\\n") ?: "--")
    field("Capability test completed", obd.capabilityTestCompleted)
    field("DDS wheel-speed test completed", obd.wheelSpeedTestCompleted)
    field("DDS wheel-speed test", obd.wheelSpeedTestStatus?.english(context) ?: "--")
    obd.capabilityResults.forEach { result ->
        field(
            "Capability ${result.command} ${UiText.of(result.title).english(context)}",
            "${if (result.available) "available" else "unavailable"}; ${result.summary.english(context)}; raw=${result.rawResponse}",
        )
    }
}

internal fun StringBuilder.appendDirectCanElm327ParitySection() {
    val obd = Obd2Client.state.value
    val tires = TireMonitoringRepository.state.value
    val fuelRange = FuelRangeEstimator.state.value
    val esp32Can = Esp32CanClient.state.value
    section("DIRECT CAN / ELM327 PARITY")
    val canObdParity = buildCanObdParityReport(obd, tires, esp32Can, fuelRange)
    field(
        "ELM removal readiness",
        "${canObdParity.validatedDirectCanCount}/${canObdParity.totalRequired}; " +
            if (canObdParity.elmCanBeRemoved) "ready" else "keep ELM fallback",
    )
    canObdParity.items.forEach { item ->
        field(item.label, "ELM=${item.obdValue}; CAN=${item.canValue}; ${item.status}")
    }
}

internal fun StringBuilder.appendTpmsDdsSection(context: Context) {
    val tires = TireMonitoringRepository.state.value
    section("TPMS / DDS")
    field("Mode", tires.mode)
    field("Source", tires.source)
    field("Status", tires.status.english(context))
    field("USB candidates", tires.usbCandidates.joinToString { "${it.name} ${it.identifier}" }.ifBlank { "--" })
    field("USB protocol ready", tires.usbProtocolReady)
    field("DDS calibration", "${tires.calibrationState}; ${tires.calibrationProgress}%")
    field("Wheel-speed source", tires.wheelSpeedSource ?: "--")
    TirePosition.entries.forEach { position ->
        val reading = tires.readings[position]
        field(
            "Tire ${position.name}",
            "pressure=${reading?.pressureBar ?: "--"} bar; temperature=${reading?.temperatureCelsius ?: "--"} C; " +
                "ddsDeviation=${tires.ddsDeviationPercent[position] ?: "--"}%; alert=${position in tires.ddsAlerts}",
        )
    }
}

internal fun StringBuilder.appendDisplayedSpeedSection(context: Context) {
    val launcher = LauncherRuntime.state.value
    val dashboardSettings = DashboardPreferences.current(context)
    val speedWarning = SpeedWarningPreferences.get(context).state.value
    section("DISPLAYED SPEED")
    field("Value", "${launcher.speedKmh} km/h")
    field("Source", launcher.speedSource)
    field("Warning enabled", speedWarning.enabled)
    field("Warning minimum speed", "${speedWarning.minimumSpeedKmh} km/h")
    field(
        "Warning repeat",
        if (speedWarning.repeatEnabled) "${speedWarning.repeatIntervalSeconds} s" else "disabled",
    )
    field("Driving zoom mode", dashboardSettings.drivingZoomMode.label)
    field("Vehicle scene mode", dashboardSettings.vehicleSceneMode.name)
    field(
        "Driving zoom target",
        "${dashboardSettings.drivingZoomMode.sceneScaleForSpeed(launcher.speedKmh)} scene scale",
    )
}

internal fun StringBuilder.appendFuelRangeSection(context: Context) {
    val obd = Obd2Client.state.value
    val fuelRange = FuelRangeEstimator.state.value
    val fuelTracking = FuelTrackingRepository.state.value
    section("FUEL RANGE")
    field("Tank capacity", "$FUEL_TANK_CAPACITY_LITERS L")
    field("Estimated range", fuelRange.rangeKm?.let { "$it km" } ?: "not available")
    field("Fuel", fuelRange.fuelLiters?.let { "%.1f L".format(Locale.US, it) } ?: "not available")
    field("Fuel source", fuelRange.fuelSource.english(context))
    field("Fuel percent", fuelRange.fuelPercent?.let { "$it %" } ?: "not available")
    field("CAN fuel filtered raw", fuelRange.hsFuelRawByte?.let { "0x%02X".format(it) } ?: "--")
    field("CAN fuel sender 8-bit", fuelRange.hsFuelSenderLevel ?: "--")
    field("Estimated reserve", fuelRange.hsLowFuel ?: "--")
    field("Fuel sender fault suspected", fuelRange.senderFaultSuspected)
    field("Fuel calibration points", fuelRange.calibrationPointCount)
    field("Configured fuel", fuelTracking.fuel.label)
    field(
        "Pending refuel",
        fuelTracking.pendingRefuel?.let {
            "%.1f L; lookup=%s; station=%s; message=%s".format(
                Locale.US,
                it.estimatedLiters,
                it.lookingUpStation,
                it.station?.name ?: "--",
                it.lookupMessage ?: "--",
            )
        } ?: "--",
    )
    field(
        "Last recorded refuel",
        fuelRange.lastRefuelLiters?.let {
            "%.1f L; full=%s".format(Locale.US, it, fuelRange.lastRefuelWasFull)
        } ?: "--",
    )
    field(
        "Estimated fuel rate from MAF",
        obd.estimatedFuelRateLitersPerHour?.let { "%.2f L/h".format(Locale.US, it) }
            ?: "not available",
    )
    field("Learned average", "%.2f L/100 km".format(Locale.US, fuelRange.averageLitersPer100Km))
    field("Learned distance", "%.2f km".format(Locale.US, fuelRange.learnedDistanceKm))
    field("Low range warning", fuelRange.lowRange)
    field("Critical range warning", fuelRange.criticalRange)
}

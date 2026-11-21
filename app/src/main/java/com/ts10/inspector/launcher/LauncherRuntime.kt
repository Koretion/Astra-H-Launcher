package com.ts10.inspector.launcher

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.session.MediaController
import androidx.core.content.ContextCompat
import com.ts10.inspector.camera.GrabadoraHdAdasClient
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.diagnostics.AndroidRootChecker
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.environment.WeatherProvider
import com.ts10.inspector.panel.launchApplicationInContentArea
import com.ts10.inspector.panel.launchConfiguredCameraFullScreen
import com.ts10.inspector.panel.launchConfiguredVoiceAssistant
import com.ts10.inspector.panel.recoverLauncherAfterCarStartup
import com.ts10.inspector.phone.AndroidBluetoothPhoneClient
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.DriverProfilePreferences
import com.ts10.inspector.trips.VehicleTripTracker
import com.ts10.inspector.vehicle.BatteryMaintenancePreferences
import com.ts10.inspector.vehicle.SpeedWarningController
import com.ts10.inspector.vehicle.VehicleMileageTracker
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import com.ts10.inspector.vehicle.fuel.FuelTrackingRepository
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.qf.QfDataShareBridge
import com.ts10.inspector.vehicle.qf.QfDiagnosticsState
import com.ts10.inspector.vehicle.qf.QfVehicleClient
import com.ts10.inspector.vehicle.tires.TireMonitoringRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Shared runtime state used by both the launcher task and its hybrid overlay windows. */
object LauncherRuntime {
    val state: StateFlow<LauncherState> = LauncherStore.state.asStateFlow()
    private val initialState = LauncherStore.state.value
    val speedState: StateFlow<SpeedUiState> = state
        .map(LauncherState::speedUiState)
        .distinctUntilChanged()
        .stateIn(LauncherStore.scope, SharingStarted.Eagerly, initialState.speedUiState())
    val weatherState: StateFlow<WeatherUiState> = state
        .map(LauncherState::weatherUiState)
        .distinctUntilChanged()
        .stateIn(LauncherStore.scope, SharingStarted.Eagerly, initialState.weatherUiState())
    val climateState: StateFlow<ClimateUiState> = state
        .map(LauncherState::climateUiState)
        .distinctUntilChanged()
        .stateIn(LauncherStore.scope, SharingStarted.Eagerly, initialState.climateUiState())
    val mediaState: StateFlow<MediaUiState> = state
        .map(LauncherState::mediaUiState)
        .distinctUntilChanged()
        .stateIn(LauncherStore.scope, SharingStarted.Eagerly, initialState.mediaUiState())
    val vehicleVisualState: StateFlow<VehicleVisualUiState> = state
        .map(LauncherState::vehicleVisualUiState)
        .distinctUntilChanged()
        .stateIn(LauncherStore.scope, SharingStarted.Eagerly, initialState.vehicleVisualUiState())
    private var receiversRegistered = false
    private var qfVehicleClient: QfVehicleClient? = null
    private var componentCallbacksRegistered = false
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            QfDiagnosticsRecorder.updateSystemNightMode(newConfig, source = "Configuration")
            LauncherStore.applicationContext?.let(QfPopupPreferences::reapplyQfPopupPreferences)
        }

        override fun onLowMemory() = Unit
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            QfDiagnosticsRecorder.logObservedBroadcast(intent)
            when (intent.action) {
                ACTION_ACC_ON -> {
                    LauncherStore.accessoryOn = true
                    DriverProfilePreferences.get(context.applicationContext).clearRecognizedIgnitionKey()
                    recoverLauncherAfterCarStartup(context.applicationContext)
                    VehicleTripTracker.onAccessoryOn()
                    VehicleMileageTracker.onAccessoryOn()
                    QfDiagnosticsRecorder.updateAccStatus("ACC_ON")
                    QfPopupPreferences.reapplyQfPopupPreferences(context.applicationContext)
                    LocationRuntime.startLocationUpdates()
                    DriverProfilePreferences.get(context.applicationContext).requestAccSelector()
                }

                ACTION_ACC_OFF -> {
                    LauncherStore.accessoryOn = false
                    DriverProfilePreferences.get(context.applicationContext).clearRecognizedIgnitionKey()
                    QfDiagnosticsRecorder.updateAccStatus("ACC_OFF")
                    LocationRuntime.stopLocationUpdates()
                    VehicleTripTracker.onAccessoryOff()
                    VehicleMileageTracker.onAccessoryOff()
                    OsmRoadContextProvider.clear()
                    LauncherStore.state.update { it.copy(speedLimitKmh = null) }
                    VehicleStateFusion.onAccessoryOff()
                }

                ACTION_BACKCAR_START -> QfDiagnosticsRecorder.updateReverseStatus(reversing = true)

                ACTION_BACKCAR_STOP -> QfDiagnosticsRecorder.updateReverseStatus(reversing = false)

                ACTION_QF_DATA_SHARE -> {
                    intent.getByteArrayExtra(EXTRA_QF_DATA_SHARE)?.let {
                        QfCanBoxDecoder.decodeQfPacket(it, "Broadcast DATA_SHARE")
                    }
                }

                ACTION_QF_SWA -> Unit

                ACTION_QF_OUTSIDE_TEMPERATURE -> {
                    QfCanBoxDecoder.extractTemperatureValue(intent)?.let { value ->
                        LauncherStore.qfOutsideTemperatureReceived = true
                        LauncherStore.state.update { it.copy(outdoorTemperature = value) }
                        QfDiagnosticsRecorder.updateDiagnostics {
                            addLog(
                                it.copy(
                                    outsideTemperature = value,
                                    outsideTemperatureCount = it.outsideTemperatureCount + 1,
                                    lastSource = "Broadcast TEMP_STRING",
                                ),
                                "Temperatura exterior: $value",
                            )
                        }
                    }
                }

                ACTION_QF_RADIO_UPDATE -> MediaRuntime.updateQfRadio(intent)

                ACTION_QF_BLUETOOTH_MUSIC_INFO -> MediaRuntime.updateQfBluetoothMusic(intent)

                ACTION_QF_CAR_BACKLIGHT_CONTROL -> {
                    val level = intent.getIntExtra(EXTRA_QF_CAR_BACKLIGHT, CAR_BACKLIGHT_UNKNOWN)
                    QfDiagnosticsRecorder.updateCarBacklight(level, "Broadcast car_backlight_control")
                    QfPopupPreferences.reapplyQfPopupPreferences(context.applicationContext)
                }

                ACTION_QF_POPUP_ENABLE, ACTION_QF_AUX_VISIBILITY -> Unit
            }
        }
    }
    fun start(context: Context) {
        LauncherStore.applicationContext = context.applicationContext
        FuelTrackingRepository.start(context.applicationContext)
        FuelRangeEstimator.start(context.applicationContext)
        SpeedWarningController.start(context.applicationContext)
        VehicleMileageTracker.start(context.applicationContext)
        BatteryMaintenancePreferences.get(context.applicationContext).refresh()
        VehicleTripTracker.start(context.applicationContext)
        TireMonitoringRepository.start(context.applicationContext)
        OsmRoadContextProvider.start(context.applicationContext) { road ->
            LauncherStore.state.update { current ->
                if (current.speedLimitKmh == road?.maxSpeedKmh) current
                else current.copy(speedLimitKmh = road?.maxSpeedKmh)
            }
            LauncherStore.state.value.let { current ->
                SpeedWarningController.evaluate(current.speedKmh, current.speedLimitKmh)
            }
            road?.let {
                QfDiagnosticsRecorder.addDiagnosticLog(
                    "OSM via=${it.wayId} maxspeed=${it.maxSpeedKmh ?: "?"} " +
                        "lanes=${it.lanesInTravelDirection ?: "?"}${if (it.lanesEstimated) "~" else ""} " +
                        "curve=${"%.3f".format(Locale.US, it.curvature)}" +
                        if (it.isRoundabout) " roundabout" else "",
                )
            }
        }
        LocationRuntime.startNavigationWatchdog()
        LocationRuntime.observeLocationSettings()
        AndroidBluetoothPhoneClient.connect(context.applicationContext)
        // Antes del cliente CAN: así la primera trama ya se decodifica con el perfil elegido.
        com.ts10.inspector.vehicle.can.profile.VehicleProfileManager.start(context.applicationContext)
        Esp32CanClient.start(context.applicationContext)
        VehicleStateFusion.observeEsp32VehicleSignals()
        QfDataShareBridge.start(context.applicationContext, LauncherStore.scope)
        AndroidRootChecker.start(context.applicationContext, LauncherStore.scope)
        Obd2Client.start(context.applicationContext)
        VehicleStateFusion.observeObdSpeed()
        GrabadoraHdAdasClient.start(context.applicationContext)
        GrabadoraHdCameraClient.start(context.applicationContext)
        com.ts10.inspector.update.LauncherUpdateManager.start(context.applicationContext)
        com.ts10.inspector.update.GmlanFirmwareUpdateManager.start(context.applicationContext)
        QfPopupPreferences.reapplyQfPopupPreferences(context.applicationContext)
        if (LauncherStore.weatherProvider == null) {
            LauncherStore.weatherProvider = WeatherProvider(
                context = context.applicationContext,
                onWeather = { weather ->
                    LauncherStore.state.update {
                        it.copy(
                            weatherCode = weather.code,
                            weatherDetails = weather,
                            outdoorTemperature = if (LauncherStore.qfOutsideTemperatureReceived) {
                                it.outdoorTemperature
                            } else {
                                String.format(Locale.getDefault(), "%.0f\u00b0", weather.temperatureCelsius)
                            },
                        )
                    }
                    QfDiagnosticsRecorder.addDiagnosticLog(
                        "Online weather: %.1f C, code %d".format(
                            Locale.US,
                            weather.temperatureCelsius,
                            weather.code,
                        ),
                    )
                },
                onError = { error -> QfDiagnosticsRecorder.addDiagnosticLog("Error meteorologia online: $error") },
            )
        }
        if (!receiversRegistered) {
            val filter = IntentFilter().apply {
                RUNTIME_BROADCAST_ACTIONS.forEach(::addAction)
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                systemReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiversRegistered = true
            QfDiagnosticsRecorder.addDiagnosticLog("QF receivers registered")
        }
        if (!componentCallbacksRegistered) {
            context.applicationContext.registerComponentCallbacks(configurationCallbacks)
            componentCallbacksRegistered = true
            QfDiagnosticsRecorder.addDiagnosticLog("Configuration callbacks registered")
        }
        QfDiagnosticsRecorder.updateSystemNightMode(context.resources.configuration, source = "Startup")
        QfDiagnosticsRecorder.readCarBacklightProperty(source = "Startup")
        QfCanBoxDecoder.restoreCachedClimate(context.applicationContext)
        LocationRuntime.startLocationUpdates()
        if (qfVehicleClient == null) {
            qfVehicleClient = QfVehicleClient(
                context.applicationContext,
                onPacket = { QfCanBoxDecoder.decodeQfPacket(it, "Binder QF") },
                onStatus = QfDiagnosticsRecorder::updateBinderStatus,
            ).also {
                it.connect()
            }
        }
    }

    fun actions(context: Context) = LauncherActions(
        onVoiceAssistant = { launchConfiguredVoiceAssistant(context) },
        onNavigation = {
            val navigationPackage = DashboardPreferences.current(context).navigationPackage
            MediaRuntime.preserveActiveMediaPlayback(context, navigationPackage)
            launchApplicationInContentArea(
                context,
                navigationPackage,
            )
        },
        onOpenCameraFullScreen = { launchConfiguredCameraFullScreen(context) },
        onOpenMedia = {
            val packageName = LauncherStore.state.value.mediaPackage
                ?: MediaRuntime.mediaController?.packageName
                ?: DashboardPreferences.current(context).mediaPackage
            packageName?.let { MediaRuntime.launchMediaApplication(context, it) }
        },
        onRepeat = { MediaRuntime.runMediaCustomAction("repeat") },
        onPrevious = { MediaRuntime.previousMedia(context) },
        onStop = { MediaRuntime.stopMedia(context) },
        onPlayPause = { MediaRuntime.toggleMedia(context) },
        onNext = { MediaRuntime.nextMedia(context) },
        onMore = {
            val packageName = LauncherStore.state.value.mediaPackage
                ?: MediaRuntime.mediaController?.packageName
                ?: DashboardPreferences.current(context).mediaPackage
            packageName?.let { MediaRuntime.launchMediaApplication(context, it) }
        },
        onShuffle = { MediaRuntime.runMediaCustomAction("shuffle") },
        onSelectMediaSource = { /* handled by UI/modal layer */ },
    )

    private const val EXTRA_QF_DATA_SHARE = "extra_DATA_SHARE"
    private const val EXTRA_QF_CAR_BACKLIGHT = "extra_car_backlight"

    val diagnostics: StateFlow<QfDiagnosticsState> = QfDiagnosticsRecorder.diagnostics
    internal val gpsAcquisition: StateFlow<GpsAcquisitionState> = LocationRuntime.gpsAcquisition

    fun clearDiagnosticsLog() = QfDiagnosticsRecorder.clearDiagnosticsLog()

    fun startDiagnosticsCapture() = QfDiagnosticsRecorder.startDiagnosticsCapture()

    fun stopDiagnosticsCapture() = QfDiagnosticsRecorder.stopDiagnosticsCapture()

    internal fun recordPanelWindowEvent(message: String) = QfDiagnosticsRecorder.recordPanelWindowEvent(message)

    fun setQfDoorPopupEnabled(context: Context, enabled: Boolean) =
        QfPopupPreferences.setQfDoorPopupEnabled(context, enabled)

    fun setQfClimatePopupEnabled(context: Context, enabled: Boolean) =
        QfPopupPreferences.setQfClimatePopupEnabled(context, enabled)

    fun reapplyQfPopupPreferences(context: Context) = QfPopupPreferences.reapplyQfPopupPreferences(context)

    fun setMediaControllers(controllers: List<MediaController>, selected: MediaController?) =
        MediaRuntime.setMediaControllers(controllers, selected)

    fun updateMediaState(controller: MediaController? = MediaRuntime.mediaController) =
        MediaRuntime.updateMediaState(controller)

    fun updateMediaNotification(packageName: String, title: String, artist: String?, playing: Boolean) =
        MediaRuntime.updateMediaNotification(packageName, title, artist, playing)

    fun clearMediaNotification(packageName: String) = MediaRuntime.clearMediaNotification(packageName)

    fun selectMediaSource(context: Context, packageName: String) =
        MediaRuntime.selectMediaSource(context, packageName)

    internal fun preserveActiveMediaPlayback(context: Context, targetPackage: String) =
        MediaRuntime.preserveActiveMediaPlayback(context, targetPackage)
}

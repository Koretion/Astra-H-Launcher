package com.ts10.inspector.launcher

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.panel.PanelApplicationSession
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.trips.VehicleTripTracker
import com.ts10.inspector.vehicle.VehicleMileageTracker
import com.ts10.inspector.vehicle.fuel.FuelTrackingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

internal data class GpsAcquisitionState(
    val status: UiText = UiText.of(R.string.gps_not_started),
    val registeredProviders: List<String> = emptyList(),
    val receivedFixes: Long = 0L,
    val lastFixUtc: String? = null,
    val lastProvider: String? = null,
    val lastAccuracyMeters: Float? = null,
    val lastFixElapsedMs: Long = 0L,
    val registrationAttempts: Long = 0L,
    val movingWithoutGps: Boolean = false,
    val lastError: String? = null,
)

/** GPS/fused location acquisition, its watchdog and the navigation panel watchdog. */
internal object LocationRuntime {
    private val mutableGpsAcquisition = MutableStateFlow(GpsAcquisitionState())
    internal val gpsAcquisition: StateFlow<GpsAcquisitionState> = mutableGpsAcquisition.asStateFlow()

    private var locationManager: LocationManager? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var fusedLocationListener: com.google.android.gms.location.LocationListener? = null
    private var locationActive = false
    private var locationWatchdogJob: Job? = null
    private var navigationWatchdogJob: Job? = null
    private var locationSettingsObserverStarted = false
    @Volatile private var lastLocationElapsedMs = 0L
    @Volatile private var locationRegistrationElapsedMs = 0L
    private val locationHandler = Handler(Looper.getMainLooper())
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            acceptLocation(location)
        }

        @Deprecated("Deprecated in the Android framework")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun acceptLocation(location: Location) {
            lastLocationElapsedMs = SystemClock.elapsedRealtime()
            FuelTrackingRepository.onLocation(location)
            mutableGpsAcquisition.update {
                it.copy(
                    status = UiText.of(R.string.gps_receiving),
                    receivedFixes = it.receivedFixes + 1,
                    lastFixUtc = location.time.takeIf { time -> time > 0L }
                        ?.let { time -> Instant.ofEpochMilli(time).toString() },
                    lastProvider = location.provider,
                    lastAccuracyMeters = location.accuracy.takeIf { _ -> location.hasAccuracy() },
                    lastFixElapsedMs = lastLocationElapsedMs,
                    movingWithoutGps = false,
                    lastError = null,
                )
            }
            val rawGpsSpeedKmh = if (location.hasSpeed()) {
                (location.speed * METERS_PER_SECOND_TO_KMH).toInt().coerceAtLeast(0)
            } else {
                0
            }
            // Match the factory launcher's CustomSpeedWidget: suppress GPS drift up to 3 km/h.
            VehicleStateFusion.onGpsSpeed(rawGpsSpeedKmh.takeIf { it > GPS_STATIONARY_THRESHOLD_KMH } ?: 0)
            val resolvedSpeed = LauncherStore.state.value
            VehicleMileageTracker.onLocation(location, resolvedSpeed.speedKmh)
            VehicleTripTracker.onLocation(
                location = location,
                vehicleSpeedKmh = resolvedSpeed.speedKmh,
                vehicleSpeedSource = resolvedSpeed.speedSource,
            )
            OsmRoadContextProvider.onLocation(location)
            LauncherStore.weatherProvider?.refresh(location.latitude, location.longitude)
    }

    fun startLocationUpdates() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            locationHandler.post(::startLocationUpdates)
            return
        }
        val context = LauncherStore.applicationContext ?: return
        if (!LauncherStore.accessoryOn || locationActive) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            mutableGpsAcquisition.update { it.copy(status = UiText.of(R.string.gps_permission_needed)) }
            return
        }

        val manager = context.getSystemService(LocationManager::class.java)
        val enabledProviders = manager.getProviders(true)
        // Reuse accurate fixes produced for navigation apps when this unit's direct GPS callback
        // temporarily degrades to a coarse network location. Accuracy/freshness checks still gate it.
        val requestedProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
            .filter(enabledProviders::contains)
            .distinct()
        val registeredProviders = mutableListOf<String>()
        val errors = mutableListOf<String>()
        requestedProviders.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MS,
                    LOCATION_MIN_DISTANCE_METERS,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }.onSuccess {
                registeredProviders += provider
            }.onFailure { error ->
                errors += "$provider: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (DashboardPreferences.current(context).fusedLocationEnabled && startFusedLocationUpdates(context, errors)) {
            registeredProviders += FUSED_PROVIDER_LABEL
        }
        locationManager = manager
        locationActive = registeredProviders.isNotEmpty()
        if (locationActive) {
            locationRegistrationElapsedMs = SystemClock.elapsedRealtime()
        }
        mutableGpsAcquisition.update {
            it.copy(
                status = if (locationActive) UiText.of(R.string.gps_waiting) else UiText.of(R.string.gps_register_failed),
                registeredProviders = registeredProviders,
                registrationAttempts = it.registrationAttempts + 1,
                lastError = errors.takeIf { list -> list.isNotEmpty() }?.joinToString(),
            )
        }
        if (errors.isNotEmpty()) {
            QfDiagnosticsRecorder.addDiagnosticLog("GPS registration error: ${errors.joinToString()}")
        }
        if (!locationActive) {
            QfDiagnosticsRecorder.addDiagnosticLog("GPS has no registrable providers; enabled=${enabledProviders.joinToString()}")
        }
        if (locationActive) {
            requestedProviders
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .filter { it.hasValidCoordinates() }
                .maxByOrNull(Location::getTime)
                ?.let(::seedLocationDependentServices)
        }
        startLocationWatchdog()
    }

    fun observeLocationSettings() {
        if (locationSettingsObserverStarted) return
        locationSettingsObserverStarted = true
        LauncherStore.scope.launch {
            DashboardPreferences.get(LauncherStore.applicationContext ?: return@launch).state
                .map { settings -> settings.fusedLocationEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    val context = LauncherStore.applicationContext ?: return@collect
                    if (enabled && LauncherStore.accessoryOn) {
                        startFusedLocationUpdates(context, mutableListOf())
                    } else {
                        stopFusedLocationUpdates()
                    }
                }
        }
    }

    @Suppress("MissingPermission")
    private fun startFusedLocationUpdates(context: Context, errors: MutableList<String>): Boolean =
        runCatching {
            if (fusedLocationListener != null) return true
            val client = fusedLocationClient ?: LocationServices.getFusedLocationProviderClient(context)
                .also { fusedLocationClient = it }
            val listener = com.google.android.gms.location.LocationListener { location ->
                acceptLocation(Location(location).apply { provider = FUSED_PROVIDER_LABEL })
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
                .setMinUpdateDistanceMeters(LOCATION_MIN_DISTANCE_METERS)
                .setMaxUpdateAgeMillis(FUSED_MAX_UPDATE_AGE_MS)
                .build()
            fusedLocationListener = listener
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
                .addOnSuccessListener {
                    mutableGpsAcquisition.update { current ->
                        current.copy(registeredProviders = (current.registeredProviders + FUSED_PROVIDER_LABEL).distinct())
                    }
                    QfDiagnosticsRecorder.addDiagnosticLog("GPS fused enabled by the user")
                }
                .addOnFailureListener { error ->
                    fusedLocationListener = null
                    mutableGpsAcquisition.update { current ->
                        current.copy(
                            registeredProviders = current.registeredProviders - FUSED_PROVIDER_LABEL,
                            lastError = "fused: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    QfDiagnosticsRecorder.addDiagnosticLog("GPS fused unavailable: ${error.message ?: error.javaClass.simpleName}")
                }
            true
        }.getOrElse { error ->
            errors += "fused: ${error.message ?: error.javaClass.simpleName}"
            false
        }

    private fun stopFusedLocationUpdates() {
        val listener = fusedLocationListener ?: return
        fusedLocationListener = null
        fusedLocationClient?.removeLocationUpdates(listener)
        mutableGpsAcquisition.update { current ->
            current.copy(registeredProviders = current.registeredProviders - FUSED_PROVIDER_LABEL)
        }
        QfDiagnosticsRecorder.addDiagnosticLog("GPS fused disabled")
    }

    private fun startLocationWatchdog() {
        if (locationWatchdogJob?.isActive == true) return
        locationWatchdogJob = LauncherStore.scope.launch {
            while (LauncherStore.accessoryOn) {
                delay(LOCATION_WATCHDOG_INTERVAL_MS)
                if (!LauncherStore.accessoryOn) break
                val now = SystemClock.elapsedRealtime()
                val freshnessAnchor = maxOf(lastLocationElapsedMs, locationRegistrationElapsedMs)
                val silentFor = if (freshnessAnchor > 0L) now - freshnessAnchor else Long.MAX_VALUE
                val movingByCan = VehicleStateFusion.isMovingByDirectCan(now, GPS_MOVEMENT_RECOVERY_SPEED_KMH)
                val staleThreshold = if (movingByCan) {
                    LOCATION_MOVING_STALE_RESTART_MS
                } else {
                    LOCATION_STALE_RESTART_MS
                }
                val stale = !locationActive || silentFor >= staleThreshold
                mutableGpsAcquisition.update {
                    it.copy(
                        status = if (movingByCan && stale) UiText.of(R.string.gps_moving_no_data) else it.status,
                        movingWithoutGps = movingByCan && stale,
                    )
                }
                if (movingByCan && silentFor >= LOCATION_MOVING_CONTEXT_CLEAR_MS) {
                    OsmRoadContextProvider.onLocationUnavailable()
                    LauncherStore.state.update { it.copy(speedLimitKmh = null) }
                }
                if (stale) {
                    QfDiagnosticsRecorder.addDiagnosticLog(
                        "GPS silent for ${silentFor}ms; movingByCan=$movingByCan; " +
                            "registering the provider again",
                    )
                    stopLocationUpdates(cancelWatchdog = false)
                    startLocationUpdates()
                }
            }
        }
    }

    fun startNavigationWatchdog() {
        if (navigationWatchdogJob?.isActive == true) return
        navigationWatchdogJob = LauncherStore.scope.launch {
            var consecutiveEmptyChecks = 0
            delay(NAVIGATION_WATCHDOG_START_DELAY_MS)
            while (true) {
                val context = LauncherStore.applicationContext
                if (!LauncherStore.accessoryOn || context == null || ExclusiveLauncherSession.active) {
                    consecutiveEmptyChecks = 0
                } else {
                    when (PanelApplicationSession.isConfiguredPanelEmpty(context)) {
                        true -> {
                            consecutiveEmptyChecks += 1
                            if (consecutiveEmptyChecks >= NAVIGATION_EMPTY_CONFIRMATIONS) {
                                val restored = PanelApplicationSession.restoreNavigationIfPanelEmpty(context)
                                QfDiagnosticsRecorder.addDiagnosticLog("Navegador ausente; restauracion automatica=$restored")
                                consecutiveEmptyChecks = 0
                            }
                        }
                        false, null -> consecutiveEmptyChecks = 0
                    }
                }
                delay(NAVIGATION_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    fun stopLocationUpdates(cancelWatchdog: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            locationHandler.post { stopLocationUpdates(cancelWatchdog) }
            return
        }
        locationManager?.removeUpdates(locationListener)
        stopFusedLocationUpdates()
        locationManager = null
        locationActive = false
        locationRegistrationElapsedMs = 0L
        if (cancelWatchdog) {
            locationWatchdogJob?.cancel()
            locationWatchdogJob = null
            mutableGpsAcquisition.update {
                it.copy(
                    status = UiText.of(R.string.gps_stopped_acc),
                    registeredProviders = emptyList(),
                    movingWithoutGps = false,
                )
            }
        }
    }

    private fun seedLocationDependentServices(location: Location) {
        FuelTrackingRepository.onLocation(location)
        LauncherStore.weatherProvider?.refresh(location.latitude, location.longitude)
        OsmRoadContextProvider.onLocation(location)
        QfDiagnosticsRecorder.addDiagnosticLog(
            "Last known location used to start weather/OSM: " +
                "${location.provider ?: "?"}, ${location.time.takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: "hora desconocida"}",
        )
    }

    private fun Location.hasValidCoordinates(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    private const val METERS_PER_SECOND_TO_KMH = 3.6f
    private const val LOCATION_INTERVAL_MS = 500L
    private const val LOCATION_MIN_DISTANCE_METERS = 0.5f
    private const val LOCATION_WATCHDOG_INTERVAL_MS = 5_000L
    private const val FUSED_MAX_UPDATE_AGE_MS = 5_000L
    private const val FUSED_PROVIDER_LABEL = "fused-google"
    private const val NAVIGATION_WATCHDOG_START_DELAY_MS = 15_000L
    private const val NAVIGATION_WATCHDOG_INTERVAL_MS = 10_000L
    private const val NAVIGATION_EMPTY_CONFIRMATIONS = 2
    private const val LOCATION_STALE_RESTART_MS = 120_000L
    private const val LOCATION_MOVING_STALE_RESTART_MS = 30_000L
    private const val LOCATION_MOVING_CONTEXT_CLEAR_MS = 12_000L
    private const val GPS_MOVEMENT_RECOVERY_SPEED_KMH = 5
    private const val GPS_STATIONARY_THRESHOLD_KMH = 3
}

package com.ts10.inspector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.ts10.inspector.dashboard.AstraLauncherScreen
import com.ts10.inspector.ui.AstraDashboardTheme
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.HybridOverlayService
import com.ts10.inspector.panel.PanelApplicationSession
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.settings.DriverProfilePreferences
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.panel.LauncherStartupCoordinator
import com.ts10.inspector.panel.EXTRA_CAR_STARTUP_RECOVERY
import com.ts10.inspector.panel.EXTRA_SKIP_PANEL_RESTORE
import com.ts10.inspector.ui.applyStatusBarContrast
import com.ts10.inspector.diagnostics.TelemetryLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var initializing by mutableStateOf(true)
    private var skipNextPanelRestore = false
    private var effectiveDarkDashboard = false
    private var startupApplicationsScheduled = false
    private var carStartupRecoveryActive = false
    private var carStartupRecoveryJob: Job? = null
    private var coldStartupProfilePromptPending = false

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        LauncherRuntime.start(this)
        com.ts10.inspector.vehicle.obd.Obd2Client.refreshPairedDevices()
        scheduleConfiguredStartupApplications()
        showColdStartupProfilePromptIfPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TelemetryLog.initialize(applicationContext)
        TelemetryLog.event(
            "LAUNCHER",
            "onCreate action=${intent.action ?: "--"} home=${intent.hasCategory(Intent.CATEGORY_HOME)} " +
                "recovery=${intent.getBooleanExtra(EXTRA_CAR_STARTUP_RECOVERY, false)} " +
                "savedState=${savedInstanceState != null}",
        )
        val initializationStarted = SystemClock.elapsedRealtime()
        // Tras una actualización el conductor no ha cambiado: no se vuelve a preguntar.
        coldStartupProfilePromptPending = savedInstanceState == null &&
            !intent.getBooleanExtra(EXTRA_CAR_STARTUP_RECOVERY, false) &&
            !intent.getBooleanExtra(com.ts10.inspector.update.EXTRA_AFTER_UPDATE, false)
        enableEdgeToEdge()
        setContent {
            AstraDashboardTheme(
                onEffectiveDarkChanged = { dark ->
                    effectiveDarkDashboard = dark
                    applyStatusBarContrast(this@MainActivity, dark)
                },
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = LauncherBackground) {
                    // Runtime services publish safe preview state, so HOME can be drawn and used
                    // immediately while CAN, Bluetooth, location and camera clients start behind it.
                    AstraLauncherScreen(
                        actions = LauncherRuntime.actions(this),
                    )
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_CAR_STARTUP_RECOVERY, false)) {
            scheduleCarStartupRecovery()
        }
        lifecycleScope.launch {
            // Los clientes de LauncherRuntime bloquean el hilo principal un rato: arrancan
            // cuando el panel superpuesto ya esta en pantalla (o tras un tiempo maximo).
            HybridOverlayService.awaitFirstWindowDrawn()
            var waitingForPermissions = false
            try {
                LauncherRuntime.start(this@MainActivity)
                TelemetryLog.event(
                    "LAUNCHER",
                    "runtime started elapsedMs=${SystemClock.elapsedRealtime() - initializationStarted}",
                )
                waitingForPermissions = requestRuntimePermissionsIfNeeded()
            } finally {
                initializing = false
                HybridOverlayService.showDashboard(this@MainActivity)
                // During ACC/boot recovery HOME must win the foreground race first. Restoring
                // Waze here used to put its stale full-screen task back above the launcher while
                // the TS10 firmware was still restoring the radio/navigation tasks.
                val restored = if (carStartupRecoveryActive) {
                    false
                } else {
                    PanelApplicationSession.restoreIfPending(this@MainActivity)
                }
                TelemetryLog.event(
                    "LAUNCHER",
                    "initialization complete elapsedMs=${SystemClock.elapsedRealtime() - initializationStarted} " +
                        "waitingPermissions=$waitingForPermissions panelRestored=$restored",
                )
                if (restored) startupApplicationsScheduled = true
                else if (!waitingForPermissions && !carStartupRecoveryActive) {
                    scheduleConfiguredStartupApplications()
                }
                if (!waitingForPermissions && !carStartupRecoveryActive) {
                    showColdStartupProfilePromptIfPending()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TelemetryLog.event("LAUNCHER", "onResume initializing=$initializing")
        reapplyStatusBarContrast()
        if (!initializing) {
            LauncherRuntime.reapplyQfPopupPreferences(this)
            if (ExclusiveLauncherSession.active) {
                TelemetryLog.event("LAUNCHER", "onResume suppressed: exclusive system activity")
            } else if (carStartupRecoveryActive) {
                TelemetryLog.event("LAUNCHER", "onResume panel restore deferred: ACC/boot recovery")
                HybridOverlayService.showDashboard(this)
            } else {
                HybridOverlayService.showDashboard(this)
                if (skipNextPanelRestore) {
                    skipNextPanelRestore = false
                } else {
                    val pendingRestored = PanelApplicationSession.restoreIfPending(this)
                    if (!pendingRestored) {
                        PanelApplicationSession.restoreNavigationIfPanelEmpty(this)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TelemetryLog.event(
            "LAUNCHER",
            "onNewIntent action=${intent.action ?: "--"} home=${intent.hasCategory(Intent.CATEGORY_HOME)} " +
                "recovery=${intent.getBooleanExtra(EXTRA_CAR_STARTUP_RECOVERY, false)}",
        )
        reapplyStatusBarContrast()
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            HybridOverlayService.showDashboard(this)
            val skipPanelRestore = intent.getBooleanExtra(EXTRA_SKIP_PANEL_RESTORE, false)
            if (skipPanelRestore) {
                // Overlay controls briefly bring HOME forward before launching a full-screen
                // task. Do not consume that task's pending restoration in the following
                // onResume; it belongs to the real return from the full-screen activity.
                skipNextPanelRestore = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            } else {
                PanelApplicationSession.restoreForHome(this)
            }
        }
        if (intent.getBooleanExtra(EXTRA_CAR_STARTUP_RECOVERY, false)) {
            scheduleCarStartupRecovery()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reapplyStatusBarContrast()
    }

    private fun reapplyStatusBarContrast() {
        window.decorView.post {
            applyStatusBarContrast(this, effectiveDarkDashboard)
        }
    }

    private fun requestRuntimePermissionsIfNeeded(): Boolean {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            runtimePermissionLauncher.launch(missing.toTypedArray())
            return true
        }
        return false
    }

    private fun scheduleConfiguredStartupApplications() {
        if (startupApplicationsScheduled) return
        startupApplicationsScheduled = true
        val startupToken = LauncherStartupCoordinator.token()
        lifecycleScope.launch {
            delay(INITIAL_PANEL_RESTORE_DELAY_MS)
            if (!LauncherStartupCoordinator.isCurrent(startupToken)) {
                TelemetryLog.event("LAUNCHER", "initial panel restore cancelled by user action")
                return@launch
            }
            val restored = PanelApplicationSession.restoreForHome(this@MainActivity)
            TelemetryLog.event("LAUNCHER", "initial right panel restored=$restored")
        }
    }

    private fun showColdStartupProfilePromptIfPending() {
        if (!coldStartupProfilePromptPending) return
        coldStartupProfilePromptPending = false
        lifecycleScope.launch {
            if (shouldShowDriverProfileSelector("coldPrompt")) {
                HybridOverlayService.showDriverProfiles(this@MainActivity)
            }
        }
    }

    private suspend fun shouldShowDriverProfileSelector(source: String): Boolean {
        val resolvedState = withTimeoutOrNull(PROFILE_SELECTOR_TIMEOUT_MS) {
            Esp32CanClient.state.first { can ->
                can.initialStateResolved || can.swIgnitionKeyIndex != null
            }
        }
        val can = resolvedState ?: Esp32CanClient.state.value
        val profiles = DriverProfilePreferences.get(this)
        val keyKnown = can.swIgnitionKeyIndex != null || profiles.hasRecognizedIgnitionKey
        TelemetryLog.event(
            "DRIVER_PROFILE",
            "$source resolved=${can.initialStateResolved} keyKnown=$keyKnown " +
                "timedOut=${resolvedState == null}",
        )
        return !keyKnown
    }

    /**
     * TS10 restores the previously focused task during ACC wake-up. That restoration can run
     * after Android has already created HOME, leaving Waze full-screen above the launcher.
     * Reassert HOME once the firmware has settled and then put the remembered application back
     * into the bounded right-hand panel.
     */
    private fun scheduleCarStartupRecovery() {
        carStartupRecoveryJob?.cancel()
        PanelApplicationSession.cancelPendingRestore()
        carStartupRecoveryActive = true
        val startupToken = LauncherStartupCoordinator.token()
        TelemetryLog.event("LAUNCHER", "ACC/boot recovery scheduled")
        carStartupRecoveryJob = lifecycleScope.launch {
            var previousTargetMs = 0L
            for (targetMs in CAR_STARTUP_HOME_REASSERT_MS) {
                delay(targetMs - previousTargetMs)
                previousTargetMs = targetMs
                if (!LauncherStartupCoordinator.isCurrent(startupToken)) {
                    TelemetryLog.event("LAUNCHER", "ACC/boot recovery cancelled by user action")
                    DriverProfilePreferences.get(this@MainActivity).clearPendingAccSelector()
                    carStartupRecoveryActive = false
                    return@launch
                }
                if (!com.ts10.inspector.panel.isDefaultLauncher(this@MainActivity)) {
                    TelemetryLog.event("LAUNCHER", "ACC/boot recovery skipped: not default HOME")
                    DriverProfilePreferences.get(this@MainActivity).clearPendingAccSelector()
                    carStartupRecoveryActive = false
                    return@launch
                }
                TelemetryLog.event(
                    "LAUNCHER",
                    "ACC/boot recovery reasserting HOME at ${targetMs}ms",
                )
                HybridOverlayService.showDashboard(this@MainActivity)
                com.ts10.inspector.panel.bringLauncherToForeground(this@MainActivity)
            }
            delay(CAR_STARTUP_PANEL_RESTORE_DELAY_MS)
            if (!LauncherStartupCoordinator.isCurrent(startupToken)) {
                TelemetryLog.event("LAUNCHER", "ACC/boot panel restore cancelled by user action")
                carStartupRecoveryActive = false
                return@launch
            }
            val restored = PanelApplicationSession.restoreForHome(this@MainActivity)
            startupApplicationsScheduled = restored
            carStartupRecoveryActive = false
            TelemetryLog.event("LAUNCHER", "ACC/boot recovery panel restored=$restored")
            val profiles = DriverProfilePreferences.get(this@MainActivity)
            if (profiles.consumePendingAccSelector() &&
                shouldShowDriverProfileSelector("accPrompt")
            ) {
                HybridOverlayService.showDriverProfiles(this@MainActivity)
            }
        }
    }

    private companion object {
        val LauncherBackground = androidx.compose.ui.graphics.Color.Transparent
        const val INITIAL_PANEL_RESTORE_DELAY_MS = 1_200L
        val CAR_STARTUP_HOME_REASSERT_MS = longArrayOf(120L, 850L, 1_850L, 3_000L)
        const val CAR_STARTUP_PANEL_RESTORE_DELAY_MS = 650L
        // USB asks for TS10_STATE three times every 2 s. This covers the full handshake while
        // still allowing startup to continue if the ESP32 is absent or runs older firmware.
        const val PROFILE_SELECTOR_TIMEOUT_MS = 7_000L
    }
}

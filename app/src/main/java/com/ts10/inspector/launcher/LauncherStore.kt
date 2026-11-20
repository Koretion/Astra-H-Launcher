package com.ts10.inspector.launcher

import android.content.Context
import com.ts10.inspector.environment.WeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Mutable state shared by the launcher runtime modules; [LauncherRuntime] exposes it read-only. */
internal object LauncherStore {
    val state = MutableStateFlow(previewLauncherState())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var applicationContext: Context? = null
    var accessoryOn = true
    var qfOutsideTemperatureReceived = false
    var weatherProvider: WeatherProvider? = null
}

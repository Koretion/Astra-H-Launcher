package com.ts10.inspector.vision

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager

internal data class VisionDeviceSnapshot(
    val sdk: Int,
    val release: String,
    val fingerprint: String,
    val hardware: String,
    val board: String,
    val abis: String,
    val processors: Int,
    val memoryTotalMb: Long,
    val memoryAvailableMb: Long,
    val lowMemory: Boolean,
    val processPssMb: Long,
    val javaHeapUsedMb: Long,
    val javaHeapMaxMb: Long,
    val thermalStatus: String,
    val batteryTemperatureC: Float?,
)

internal object VisionDeviceDiagnostics {
    fun snapshot(context: Context): VisionDeviceSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawBatteryTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return VisionDeviceSnapshot(
            sdk = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            board = Build.BOARD.orEmpty(),
            abis = Build.SUPPORTED_ABIS.joinToString(),
            processors = runtime.availableProcessors(),
            memoryTotalMb = memory.totalMem.toMb(),
            memoryAvailableMb = memory.availMem.toMb(),
            lowMemory = memory.lowMemory,
            processPssMb = Debug.getPss() / 1_024,
            javaHeapUsedMb = (runtime.totalMemory() - runtime.freeMemory()).toMb(),
            javaHeapMaxMb = runtime.maxMemory().toMb(),
            thermalStatus = thermalStatusLabel(powerManager.currentThermalStatus),
            batteryTemperatureC = rawBatteryTemperature
                ?.takeUnless { it == Int.MIN_VALUE }
                ?.div(10f),
        )
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    private fun Long.toMb(): Long = this / (1_024L * 1_024L)
}

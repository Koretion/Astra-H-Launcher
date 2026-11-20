package com.ts10.inspector.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.panel.recoverLauncherAfterCarStartup
import com.ts10.inspector.settings.DriverProfilePreferences

/** Restores the selected HOME task after a full boot or a TS10 ACC wake-up. */
class CarStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in STARTUP_ACTIONS) return
        TelemetryLog.initialize(context.applicationContext)
        TelemetryLog.event("LAUNCHER", "startup broadcast action=${intent.action}")
        // Paint Astra's persistent chrome before QF finishes restoring its previous task.
        HybridOverlayService.showDashboard(context.applicationContext)
        if (intent.action == ACTION_ACC_ON) {
            DriverProfilePreferences.get(context.applicationContext).requestAccSelector()
        }
        recoverLauncherAfterCarStartup(context.applicationContext)
    }

    private companion object {
        const val ACTION_ACC_ON = "com.qf.action.ACC_ON"
        val STARTUP_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            ACTION_ACC_ON,
        )
    }
}

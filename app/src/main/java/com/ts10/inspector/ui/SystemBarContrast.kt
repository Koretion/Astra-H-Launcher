package com.ts10.inspector.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps QF status information legible after edge-to-edge and freeform focus changes. */
internal fun applyStatusBarContrast(activity: Activity, darkDashboard: Boolean) {
    val window = activity.window
    // External navigation/media tasks on this Android 10 head unit sometimes leave HOME with
    // FULLSCREEN/IMMERSIVE flags. Explicitly restore the status row whenever HOME gains focus.
    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    WindowCompat.getInsetsController(window, window.decorView)
        .show(WindowInsetsCompat.Type.statusBars())
    window.statusBarColor = statusBarColor(darkDashboard)
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
        useDarkStatusBarIcons(darkDashboard)

    // QF's Android 10 SystemUI can lose the compat update after another task takes focus.
    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.R) {
        @Suppress("DEPRECATION")
        val visibleStatusBarFlags = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
            View.SYSTEM_UI_FLAG_IMMERSIVE.inv() and
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
        window.decorView.systemUiVisibility = if (useDarkStatusBarIcons(darkDashboard)) {
            visibleStatusBarFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            visibleStatusBarFlags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
}

// Match the system row to the dashboard theme. Icon appearance is the inverse of the row color.
internal fun useDarkStatusBarIcons(darkDashboard: Boolean): Boolean = !darkDashboard

// Keep the system row visibly separate from both the launcher and the right-hand application.
internal fun statusBarColor(darkDashboard: Boolean): Int =
    if (darkDashboard) DARK_STATUS_BAR_COLOR else LIGHT_STATUS_BAR_COLOR

internal const val DARK_STATUS_BAR_COLOR: Int = 0xFF050607.toInt()
internal const val LIGHT_STATUS_BAR_COLOR: Int = 0xFFF4F2EF.toInt()

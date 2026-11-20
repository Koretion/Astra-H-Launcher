package com.ts10.inspector.vehicle

import android.content.Context
import android.content.SharedPreferences

/**
 * Keeps the last valid HS-CAN steering angle across ignition cycles. The Astra H sensor reports
 * "not initialised" after ignition until the wheel is turned, while the wheel itself is still
 * where it was parked.
 */
internal class SteeringAngleMemory(
    private val writeIntervalMs: Long = 1_000L,
) {
    private var preferences: SharedPreferences? = null
    private var stored: Float? = null
    private var lastWriteMs = Long.MIN_VALUE / 2

    fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).also { prefs ->
            stored = if (prefs.contains(KEY_ANGLE)) prefs.getFloat(KEY_ANGLE, 0f) else null
        }
    }

    /** Returns the live angle (remembering it) or, while there is none, the remembered one. */
    fun resolve(live: Float?, nowMs: Long): Float? {
        if (live == null) return stored
        val previous = stored
        if (previous == null || (kotlin.math.abs(live - previous) >= MIN_CHANGE_DEGREES && nowMs - lastWriteMs >= writeIntervalMs)) {
            stored = live
            lastWriteMs = nowMs
            preferences?.edit()?.putFloat(KEY_ANGLE, live)?.apply()
        }
        return live
    }

    private companion object {
        const val FILE_NAME = "steering_angle_memory"
        const val KEY_ANGLE = "last_valid_degrees"
        const val MIN_CHANGE_DEGREES = 0.1f
    }
}

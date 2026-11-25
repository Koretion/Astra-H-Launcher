package com.ts10.inspector.vehicle

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase of the on-screen turn lamps, locked to the real flasher.
 *
 * The Astra H BCM sends SW-CAN 0x260 once per flash (every ~681 ms), so each frame is an edge of
 * the real lamps. Without those frames (QF binder or HS-CAN only) the lamps free-run with the
 * cluster's measured 380 ms on / 300 ms off from the moment the indicator was switched on.
 */
internal class TurnSignalFlashClock {
    /** Android elapsed time minus ESP32 time, from the fastest delivery seen (least USB delay). */
    private var espOffsetMs: Long? = null
    private var lastEspMs = 0L
    private var lastEdgeMs: Long? = null
    private var activeSinceMs: Long? = null
    private var lastActiveSeenMs = Long.MIN_VALUE

    /** A turn-lamp frame of an active indicator: [espTimestampUs] is when the ESP32 received it. */
    @Synchronized
    fun onFlashFrame(espTimestampUs: Long, arrivalElapsedMs: Long) {
        val espMs = espTimestampUs / 1_000L
        val candidate = arrivalElapsedMs - espMs
        val offset = espOffsetMs
        espOffsetMs = when {
            // First frame, ESP32 restart (its clock went back) or a long gap: start again.
            offset == null || espMs < lastEspMs || candidate - offset > RESYNC_OFFSET_MS -> candidate
            candidate < offset -> candidate
            // Let the minimum creep up slowly so drift between both clocks cannot accumulate.
            else -> offset + minOf(candidate - offset, OFFSET_RELAX_MS)
        }
        lastEspMs = espMs
        lastEdgeMs = espMs + espOffsetMs!!
    }

    @Synchronized
    fun lampOn(active: Boolean, nowElapsedMs: Long): Boolean {
        if (!active) return false
        if (activeSinceMs == null || nowElapsedMs - lastActiveSeenMs > ACTIVE_GAP_MS) activeSinceMs = nowElapsedMs
        lastActiveSeenMs = nowElapsedMs
        val anchor = lastEdgeMs?.takeIf { nowElapsedMs - it <= EDGE_VALID_MS } ?: activeSinceMs!!
        val phase = Math.floorMod(nowElapsedMs - anchor - FRAME_TO_LAMP_ON_MS, PERIOD_MS)
        return phase < ON_MS
    }

    /** Milliseconds until the lamp state changes, to wake exactly at the next edge. */
    @Synchronized
    fun msUntilChange(nowElapsedMs: Long): Long {
        val anchor = lastEdgeMs?.takeIf { nowElapsedMs - it <= EDGE_VALID_MS } ?: activeSinceMs ?: return PERIOD_MS
        val phase = Math.floorMod(nowElapsedMs - anchor - FRAME_TO_LAMP_ON_MS, PERIOD_MS)
        return (if (phase < ON_MS) ON_MS - phase else PERIOD_MS - phase).coerceAtLeast(1L)
    }

    companion object {
        const val ON_MS = 380L
        const val PERIOD_MS = 680L
        /** Delay from the 0x260 frame to the lamps lighting; 0 until a video says otherwise. */
        const val FRAME_TO_LAMP_ON_MS = 0L
        /** A missed frame keeps the phase for one more flash before falling back to free-run. */
        const val EDGE_VALID_MS = 1_500L
        const val ACTIVE_GAP_MS = 1_000L
        const val RESYNC_OFFSET_MS = 2_000L
        const val OFFSET_RELAX_MS = 1L
    }
}

internal object TurnSignalFlash {
    private val clock = TurnSignalFlashClock()
    private val mutableEdges = MutableStateFlow(0L)
    /** Counts received flash edges so the UI can resynchronise its timer on each one. */
    val edges: StateFlow<Long> = mutableEdges.asStateFlow()

    fun onFlashFrame(espTimestampUs: Long, arrivalElapsedMs: Long = SystemClock.elapsedRealtime()) {
        clock.onFlashFrame(espTimestampUs, arrivalElapsedMs)
        mutableEdges.value++
    }

    fun lampOn(active: Boolean, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        clock.lampOn(active, nowElapsedMs)

    fun msUntilChange(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long =
        clock.msUntilChange(nowElapsedMs)
}

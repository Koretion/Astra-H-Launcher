package com.ts10.inspector.vehicle.qf

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.vehicle.can.Esp32CanClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class QfDataShareBridgeState(
    val enabled: Boolean = false,
    val blockedByRom: Boolean = false,
    val attemptedPackets: Long = 0,
    val sentPackets: Long = 0,
    val lastHsAngleDegrees: Int? = null,
    val lastQfAngleDegrees: Int? = null,
    val lastAngleLevel: Int? = null,
    val status: UiText = UiText.of(R.string.qfb_disabled),
)

internal data class QfSteeringBroadcast(
    val angleLevel: Int,
    val angleDegrees: Int,
    val rangeDegrees: Float,
)

/** Android-only adapter from the validated Astra HS-CAN angle to QF's ACTION_SWA. */
internal object QfDataShareBridge {
    private val mutableState = MutableStateFlow(QfDataShareBridgeState())
    val state: StateFlow<QfDataShareBridgeState> = mutableState.asStateFlow()
    private var observer: Job? = null
    private var lastAngleLevel: Int? = null
    private var lastSentMs = 0L
    private var blockedByRom = false

    fun start(context: Context, scope: CoroutineScope) {
        if (observer != null) return
        val appContext = context.applicationContext
        observer = scope.launch {
            combine(
                DashboardPreferences.get(appContext).state,
                Esp32CanClient.state,
            ) { settings, can -> settings.qfDataShareSteeringEnabled to can.hsSteeringAngleDegrees }
                .collect { (enabled, hsAngle) ->
                    if (!enabled) {
                        lastAngleLevel = null
                        blockedByRom = false
                        mutableState.value = mutableState.value.copy(
                            enabled = false,
                            blockedByRom = false,
                            status = UiText.of(R.string.qfb_disabled),
                        )
                        return@collect
                    }
                    // A protected broadcast throws before leaving this process. Retrying it for
                    // every steering update cannot succeed and only creates exception/log churn.
                    // Switching the setting off and on permits one explicit diagnostic retry.
                    if (blockedByRom) return@collect
                    if (hsAngle == null) {
                        mutableState.value = mutableState.value.copy(
                            enabled = true,
                            status = UiText.of(R.string.qfb_waiting_angle),
                        )
                        return@collect
                    }

                    val swa = qfSteeringBroadcast(hsAngle)
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - lastSentMs
                    if (swa.angleLevel == lastAngleLevel && elapsed < HEARTBEAT_INTERVAL_MS) return@collect
                    if (swa.angleLevel != lastAngleLevel && elapsed < MIN_SEND_INTERVAL_MS) return@collect

                    runCatching {
                        // Exact broadcast emitted by QF VehicleSender. This only updates Android
                        // consumers; it does not write to CAN, the CANBox or the MCU UART.
                        appContext.sendBroadcast(
                            Intent(ACTION_QF_SWA)
                                .putExtra(EXTRA_QF_SWA, swa.angleLevel)
                                .putExtra(EXTRA_QF_ANGLE, swa.angleDegrees)
                                .putExtra(EXTRA_QF_RATE, swa.rangeDegrees),
                        )
                    }.onSuccess {
                        lastAngleLevel = swa.angleLevel
                        lastSentMs = now
                        mutableState.value = mutableState.value.copy(
                            enabled = true,
                            attemptedPackets = mutableState.value.attemptedPackets + 1,
                            sentPackets = mutableState.value.sentPackets + 1,
                            lastHsAngleDegrees = hsAngle.roundToInt(),
                            lastQfAngleDegrees = swa.angleDegrees,
                            lastAngleLevel = swa.angleLevel,
                            status = UiText.of(R.string.qfb_sent),
                        )
                    }.onFailure { error ->
                        // Keep the calculated value visible even when this ROM protects ACTION_SWA.
                        // That distinguishes a QF permission block from missing HS-CAN input.
                        val securityBlocked = isTerminalQfSwaError(error)
                        if (securityBlocked) blockedByRom = true
                        lastAngleLevel = swa.angleLevel
                        lastSentMs = now
                        mutableState.value = mutableState.value.copy(
                            enabled = true,
                            blockedByRom = securityBlocked,
                            attemptedPackets = mutableState.value.attemptedPackets + 1,
                            lastHsAngleDegrees = hsAngle.roundToInt(),
                            lastQfAngleDegrees = swa.angleDegrees,
                            lastAngleLevel = swa.angleLevel,
                            status = if (securityBlocked) {
                                UiText.of(R.string.qfb_blocked)
                            } else {
                                UiText.of(R.string.qfb_error, error.javaClass.simpleName)
                            },
                        )
                    }
                }
        }
    }

    private const val ACTION_QF_SWA = "com.qf.vehicle.action.swa"
    private const val EXTRA_QF_SWA = "extra_swa"
    private const val EXTRA_QF_ANGLE = "extra_angle"
    private const val EXTRA_QF_RATE = "extra_rate"
    private const val MIN_SEND_INTERVAL_MS = 80L
    private const val HEARTBEAT_INTERVAL_MS = 500L
}

internal fun isTerminalQfSwaError(error: Throwable): Boolean = error is SecurityException

/**
 * Mirrors QF VehicleSender's Opel scale and 0..90 image-index formula. Astra HS-CAN was
 * validated as left-positive, while QF's left images occupy 0..44, hence the sign inversion.
 */
internal fun qfSteeringBroadcast(hsAngleDegrees: Float): QfSteeringBroadcast {
    val qfAngle = (-hsAngleDegrees.roundToInt()).coerceIn(-QF_STEERING_RANGE, QF_STEERING_RANGE)
    val angleLevel = if (qfAngle < 0) {
        (((qfAngle + QF_STEERING_RANGE) * QF_MIDDLE_LEVEL.toFloat()) / QF_STEERING_RANGE).toInt()
    } else {
        ((qfAngle * QF_MIDDLE_LEVEL.toFloat()) / QF_STEERING_RANGE).toInt() + QF_MIDDLE_LEVEL
    }.coerceIn(0, QF_MAX_LEVEL)
    return QfSteeringBroadcast(angleLevel, qfAngle, QF_STEERING_RANGE.toFloat())
}

private const val QF_STEERING_RANGE = 540
private const val QF_MIDDLE_LEVEL = 45
private const val QF_MAX_LEVEL = 90

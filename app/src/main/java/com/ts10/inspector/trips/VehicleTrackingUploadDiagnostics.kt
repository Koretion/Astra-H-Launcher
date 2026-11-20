package com.ts10.inspector.trips

import android.content.Context
import java.time.Instant

internal data class VehicleTrackingUploadSnapshot(
    val workerState: String = "Not scheduled",
    val enqueueReason: String? = null,
    val runAttempt: Int = 0,
    val lastAttemptUtc: String? = null,
    val lastBatchUid: String? = null,
    val lastHttpResult: String? = null,
    val lastUploadUtc: String? = null,
    val lastError: String? = null,
)

/** Durable, non-secret upload telemetry. It survives process restarts and is included in QF logs. */
internal object VehicleTrackingUploadDiagnostics {
    private const val PREFS = "vehicle_tracking_upload_diagnostics"
    private const val KEY_WORKER_STATE = "worker_state"
    private const val KEY_ENQUEUE_REASON = "enqueue_reason"
    private const val KEY_RUN_ATTEMPT = "run_attempt"
    private const val KEY_LAST_ATTEMPT_UTC = "last_attempt_utc"
    private const val KEY_LAST_BATCH_UID = "last_batch_uid"
    private const val KEY_LAST_HTTP_RESULT = "last_http_result"
    private const val KEY_LAST_UPLOAD_UTC = "last_upload_utc"
    private const val KEY_LAST_ERROR = "last_error"

    fun snapshot(context: Context): VehicleTrackingUploadSnapshot {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VehicleTrackingUploadSnapshot(
            workerState = preferences.getString(KEY_WORKER_STATE, null) ?: "Not scheduled",
            enqueueReason = preferences.getString(KEY_ENQUEUE_REASON, null),
            runAttempt = preferences.getInt(KEY_RUN_ATTEMPT, 0),
            lastAttemptUtc = preferences.getString(KEY_LAST_ATTEMPT_UTC, null),
            lastBatchUid = preferences.getString(KEY_LAST_BATCH_UID, null),
            lastHttpResult = preferences.getString(KEY_LAST_HTTP_RESULT, null),
            lastUploadUtc = preferences.getString(KEY_LAST_UPLOAD_UTC, null),
            lastError = preferences.getString(KEY_LAST_ERROR, null),
        )
    }

    fun recordEnqueued(context: Context, reason: String, immediate: Boolean) {
        edit(context).apply {
            if (immediate) putString(KEY_WORKER_STATE, "Scheduled immediately")
            putString(KEY_ENQUEUE_REASON, reason)
        }.apply()
    }

    fun recordRunning(context: Context, runAttempt: Int) {
        edit(context)
            .putString(KEY_WORKER_STATE, "Running")
            .putInt(KEY_RUN_ATTEMPT, runAttempt)
            .putString(KEY_LAST_ATTEMPT_UTC, now())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordOutcome(context: Context, state: String, error: String? = null) {
        edit(context)
            .putString(KEY_WORKER_STATE, state)
            .apply {
                if (error == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, error.take(300))
            }
            .apply()
    }

    fun recordHttpSuccess(context: Context, batchUid: String, result: String) {
        val now = now()
        edit(context)
            .putString(KEY_LAST_BATCH_UID, batchUid)
            .putString(KEY_LAST_HTTP_RESULT, result.take(300))
            .putString(KEY_LAST_UPLOAD_UTC, now)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordHttpError(context: Context, error: String) {
        val diagnostic = error.take(300)
        edit(context)
            .putString(KEY_LAST_HTTP_RESULT, "ERROR: $diagnostic")
            .putString(KEY_LAST_ERROR, diagnostic)
            .apply()
    }

    private fun edit(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()

    private fun now(): String = Instant.now().toString()
}

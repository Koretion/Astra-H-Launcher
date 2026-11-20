package com.ts10.inspector.trips

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class VehicleTrackingUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        VehicleTrackingUploadDiagnostics.recordRunning(applicationContext, runAttemptCount)
        VehicleTripTracker.refreshUploadDiagnostics(applicationContext)
        try {
            val uploadResult = VehicleTripTracker.uploadFromWorker(applicationContext)
            when (trackingWorkDisposition(uploadResult)) {
                TrackingWorkDisposition.SUCCESS -> {
                    val state = if (uploadResult == TrackingUploadResult.DISABLED) {
                        "Idle or not configured"
                    } else {
                        "Completed"
                    }
                    VehicleTrackingUploadDiagnostics.recordOutcome(applicationContext, state)
                    Result.success()
                }
                TrackingWorkDisposition.CONTINUE -> {
                    VehicleTrackingUploadDiagnostics.recordOutcome(applicationContext, "Continuing queue")
                    enqueueContinuation(applicationContext)
                    Result.success()
                }
                TrackingWorkDisposition.RETRY -> {
                    VehicleTrackingUploadDiagnostics.recordOutcome(
                        applicationContext,
                        "Retrying with backoff",
                        VehicleTrackingUploadDiagnostics.snapshot(applicationContext).lastError,
                    )
                    Result.retry()
                }
            }.also { VehicleTripTracker.refreshUploadDiagnostics(applicationContext) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            VehicleTrackingUploadDiagnostics.recordOutcome(applicationContext, "Unexpected error; retrying", message)
            VehicleTripTracker.refreshUploadDiagnostics(applicationContext)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "vehicle-tracking-sheet-upload"

        fun enqueue(context: Context, immediate: Boolean = false, reason: String = "datos_pendientes") {
            val builder = requestBuilder()
            if (!immediate) {
                val delay = VehicleTrackingPreferences.current(context)
                    .uploadIntervalSeconds.coerceAtLeast(5)
                builder.setInitialDelay(delay.toLong(), TimeUnit.SECONDS)
            }
            val request = builder.build()
            val appContext = context.applicationContext
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK,
                if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            VehicleTrackingUploadDiagnostics.recordEnqueued(appContext, reason, immediate)
            VehicleTripTracker.refreshUploadDiagnostics(appContext)
        }

        private fun enqueueContinuation(context: Context) {
            val request = requestBuilder()
                .setInitialDelay(1, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun requestBuilder() = OneTimeWorkRequestBuilder<VehicleTrackingUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
            VehicleTrackingUploadDiagnostics.recordOutcome(context, "Cancelled")
            VehicleTripTracker.refreshUploadDiagnostics(context)
        }
    }
}

internal enum class TrackingUploadResult {
    COMPLETE,
    MORE_PENDING,
    RETRY,
    DISABLED,
}

internal enum class TrackingWorkDisposition {
    SUCCESS,
    CONTINUE,
    RETRY,
}

internal fun trackingWorkDisposition(result: TrackingUploadResult): TrackingWorkDisposition = when (result) {
    TrackingUploadResult.COMPLETE, TrackingUploadResult.DISABLED -> TrackingWorkDisposition.SUCCESS
    TrackingUploadResult.MORE_PENDING -> TrackingWorkDisposition.CONTINUE
    TrackingUploadResult.RETRY -> TrackingWorkDisposition.RETRY
}

/** Preserves session order so a completed old trip is closed before points from a newer one. */
internal fun shouldUploadCloseBeforePoints(closeSessionId: Long, pointSessionId: Long?): Boolean =
    pointSessionId == null || closeSessionId < pointSessionId

package com.ts10.inspector.vision

import android.os.SystemClock
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.vehicle.VehicleSignalHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [LaneChangeDetector] at 10 Hz outside Compose, so lane changes are followed even while the
 * dashboard is hidden behind a full-screen app. Publishes the lane counted from the right.
 */
internal object LaneChangeEstimator {
    private const val SAMPLE_INTERVAL_MS = 100L
    private val detector = LaneChangeDetector { TelemetryLog.event("LANE_CHANGE", it) }
    private val mutableLane = MutableStateFlow(0)
    val laneFromRight: StateFlow<Int> = mutableLane.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                sample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun sample() {
        val launcher = LauncherRuntime.state.value
        val forwardCamera = GrabadoraHdCameraClient.state.value.selectedPosition != REAR_CAMERA_POSITION
        val lanes = OsmRoadContextProvider.state.value.road?.lanesInTravelDirection
        val next = detector.update(
            LaneChangeSample(
                nowMs = SystemClock.elapsedRealtime(),
                speedKmh = launcher.speedKmh.toFloat(),
                steeringDeg = VehicleSignalHub.state.value.steeringAngleDegrees?.value,
                leftIndicator = launcher.leftBlinker || launcher.hazard,
                rightIndicator = launcher.rightBlinker || launcher.hazard,
                lane = if (forwardCamera) LocalVisionLaneDetector.state.value.stableLane else null,
                lanesInTravelDirection = lanes,
            ),
        )
        if (next != mutableLane.value) {
            TelemetryLog.event("LANE_POSITION", "fromRight=$next lanes=${lanes ?: 1}")
            mutableLane.value = next
        }
    }

    /** Same convention as the dashboard: position 1 is the rear camera. */
    private const val REAR_CAMERA_POSITION = 1
}

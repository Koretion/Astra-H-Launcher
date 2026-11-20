package com.ts10.inspector.trips

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class VehicleTrackingProtocolTest {
    @Test
    fun `location age uses the monotonic clock`() {
        val fixNanos = TimeUnit.MILLISECONDS.toNanos(98_500L)

        assertEquals(1_500L, locationAgeMillis(fixNanos, nowElapsedMs = 100_000L))
    }

    @Test
    fun `future monotonic fix produces a negative age and can be rejected`() {
        val fixNanos = TimeUnit.MILLISECONDS.toNanos(101_000L)

        assertEquals(-1_000L, locationAgeMillis(fixNanos, nowElapsedMs = 100_000L))
    }

    @Test
    fun `point uid is namespaced by device session and sequence`() {
        assertEquals(
            "RADIO-TS10:session-abc:7",
            trackingPointUid("RADIO-TS10", "session-abc", 7),
        )
    }

    @Test
    fun `elapsed location ordering ignores wall clock jumps during one boot`() {
        assertEquals(
            5_000L,
            trackingElapsedMillis(
                firstEpochMs = 1_000_000L,
                firstElapsedMs = 100_000L,
                secondEpochMs = 995_000L,
                secondElapsedMs = 105_000L,
            ),
        )
    }

    @Test
    fun `elapsed location ordering uses utc across device reboots`() {
        assertEquals(
            30_000L,
            trackingElapsedMillis(
                firstEpochMs = 1_000_000L,
                firstElapsedMs = 900_000L,
                secondEpochMs = 1_030_000L,
                secondElapsedMs = 10_000L,
            ),
        )
    }

    @Test
    fun `gps drift while parked does not confirm a trip`() {
        assertEquals(false, trackingHasStartMovement(0.4f, movementMeters = 5.0, elapsedMs = 10_000L))
    }

    @Test
    fun `two realistic moving fixes can confirm a trip`() {
        assertEquals(true, trackingHasStartMovement(8f, movementMeters = 10.0, elapsedMs = 5_000L))
        assertEquals(true, trackingHasStartMovement(0f, movementMeters = 15.0, elapsedMs = 5_000L))
    }

    @Test
    fun `impossible gps jump is rejected`() {
        assertEquals(false, trackingTransitionIsPlausible(20f, movementMeters = 1_000.0, elapsedMs = 5_000L))
        assertEquals(true, trackingTransitionIsPlausible(20f, movementMeters = 25.0, elapsedMs = 5_000L))
    }

    @Test
    fun `successful batch with backlog continues without failure backoff`() {
        assertEquals(
            TrackingWorkDisposition.CONTINUE,
            trackingWorkDisposition(TrackingUploadResult.MORE_PENDING),
        )
    }

    @Test
    fun `only real upload errors enter retry backoff`() {
        assertEquals(
            TrackingWorkDisposition.RETRY,
            trackingWorkDisposition(TrackingUploadResult.RETRY),
        )
        assertEquals(
            TrackingWorkDisposition.SUCCESS,
            trackingWorkDisposition(TrackingUploadResult.COMPLETE),
        )
    }

    @Test
    fun `open backlog batch accepts ack from an already closed session`() {
        assertEquals(true, trackingAckStatusAccepted("open", "closed"))
    }

    @Test
    fun `closed batch never accepts an open session ack`() {
        assertEquals(false, trackingAckStatusAccepted("closed", "open"))
    }

    @Test
    fun `closes an older completed session before uploading a newer trip`() {
        assertEquals(true, shouldUploadCloseBeforePoints(closeSessionId = 4, pointSessionId = 5))
        assertEquals(true, shouldUploadCloseBeforePoints(closeSessionId = 4, pointSessionId = null))
        assertEquals(false, shouldUploadCloseBeforePoints(closeSessionId = 6, pointSessionId = 5))
    }
}

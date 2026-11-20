package com.ts10.inspector.media

import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionRoutingTest {
    @Test
    fun steeringTrackKeysAreCorrectedForTheirPhysicalDirection() {
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            correctedSteeringMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            correctedSteeringMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            correctedSteeringMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
    }

    @Test
    fun panelPackageRoutesToSpotifyInsteadOfFirstController() {
        assertEquals(
            1,
            mediaControllerIndexForPackage(
                controllerPackages = listOf("com.qf.radio", "com.spotify.music"),
                panelPackage = "com.spotify.music",
                selectedControllerIndex = 0,
            ),
        )
    }

    @Test
    fun missingSpotifySessionDoesNotRouteToAnotherApplication() {
        assertNull(
            mediaControllerIndexForPackage(
                controllerPackages = listOf("com.qf.radio"),
                panelPackage = "com.spotify.music",
                selectedControllerIndex = 0,
            ),
        )
    }

    @Test
    fun selectedSourceWinsWhenTwoSessionsClaimPlayback() {
        val candidates = listOf(
            MediaSessionCandidate("com.google.android.youtube", PlaybackState.STATE_PLAYING, 200L),
            MediaSessionCandidate("com.spotify.music", PlaybackState.STATE_PLAYING, 100L),
        )

        assertEquals(1, selectMediaSessionIndex(candidates, "com.spotify.music"))
    }

    @Test
    fun actualPlaybackStillWinsOverStoppedPreference() {
        val candidates = listOf(
            MediaSessionCandidate("com.google.android.youtube", PlaybackState.STATE_PLAYING, 10L),
            MediaSessionCandidate("com.spotify.music", PlaybackState.STATE_PAUSED, 20L),
        )

        assertEquals(0, selectMediaSessionIndex(candidates, "com.spotify.music"))
    }
}

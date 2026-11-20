package com.ts10.inspector.media

import android.media.session.PlaybackState
import android.view.KeyEvent

internal data class MediaSessionCandidate(
    val packageName: String,
    val playbackState: Int?,
    val lastPositionUpdateTime: Long,
)

/**
 * Selects the session shown by the dashboard. An actively playing session wins, but when more
 * than one player claims to be active the source explicitly selected by the driver wins the tie.
 */
internal fun selectMediaSessionIndex(
    candidates: List<MediaSessionCandidate>,
    preferredPackage: String?,
): Int? = candidates.indices.maxWithOrNull(
    compareBy<Int> { index -> playbackPriority(candidates[index].playbackState) }
        .thenBy { index -> if (candidates[index].packageName == preferredPackage) 1 else 0 }
        .thenBy { index -> candidates[index].lastPositionUpdateTime },
)

/**
 * Never fall back to another application's controller when the panel identifies a concrete app.
 * Returning null deliberately makes the caller use Android's global media-key fallback.
 */
internal fun mediaControllerIndexForPackage(
    controllerPackages: List<String>,
    panelPackage: String?,
    selectedControllerIndex: Int?,
): Int? = if (panelPackage == null) {
    selectedControllerIndex?.takeIf(controllerPackages.indices::contains)
} else {
    controllerPackages.indexOfFirst { it == panelPackage }.takeIf { it >= 0 }
}

/** The TS10 CAN box exposes the two physical steering-wheel track buttons in reverse order. */
internal fun correctedSteeringMediaKey(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_NEXT
    else -> keyCode
}

private fun playbackPriority(state: Int?): Int = when (state) {
    PlaybackState.STATE_PLAYING -> 3
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_CONNECTING,
    -> 2
    PlaybackState.STATE_PAUSED -> 1
    else -> 0
}

package com.ts10.inspector.media

import android.content.ComponentName
import android.content.Intent
import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.settings.DashboardPreferences

class MediaSessionMonitorService : NotificationListenerService() {
    private lateinit var mediaSessionManager: MediaSessionManager
    private var activeController: MediaController? = null
    private var steeringController: MediaController? = null
    private var steeringSpotifyAvailable: Boolean? = null
    private var steeringMediaSession: MediaSession? = null

    private val steeringCallback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return false
            if (event.action == KeyEvent.ACTION_UP) return true
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true

            val corrected = correctedSteeringMediaKey(event.keyCode)
            val target = steeringController ?: return false
            val controls = target.transportControls
            TelemetryLog.event(
                "MEDIA_KEY",
                "package=${target.packageName} raw=${event.keyCode} corrected=$corrected",
            )
            when (corrected) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_PLAY -> controls.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> controls.pause()
                KeyEvent.KEYCODE_MEDIA_STOP -> controls.stop()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (target.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
                else -> return false
            }
            return true
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            updateSteeringMediaSession()
            LauncherRuntime.updateMediaState()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            LauncherRuntime.updateMediaState()
        }

        override fun onSessionDestroyed() {
            selectActiveController()
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        selectActiveController(it.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        steeringMediaSession = MediaSession(this, "TS10 steering media correction").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(steeringCallback)
            setPlaybackState(steeringPlaybackState(PlaybackState.STATE_PAUSED))
        }
        val component = ComponentName(this, MediaSessionMonitorService::class.java)
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        selectActiveController(mediaSessionManager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        steeringController = null
        steeringSpotifyAvailable = null
        steeringMediaSession?.run {
            isActive = false
            release()
        }
        steeringMediaSession = null
        LauncherRuntime.setMediaControllers(emptyList(), null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val configuredPackages = DashboardPreferences.current(this).mediaPackages
        if (sbn.packageName !in configuredPackages) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val artist = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT,
        ).firstNotNullOfOrNull { key ->
            extras.getCharSequence(key)?.toString()?.trim()?.takeIf(String::isNotBlank)
        }
        if (title.isNullOrBlank()) return
        LauncherRuntime.updateMediaNotification(
            packageName = sbn.packageName,
            title = title,
            artist = artist,
            playing = sbn.isOngoing ||
                sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        LauncherRuntime.clearMediaNotification(sbn.packageName)
    }

    private fun selectActiveController(
        controllers: List<MediaController> = mediaSessionManager.getActiveSessions(
            ComponentName(this, MediaSessionMonitorService::class.java),
        ),
    ) {
        val eligibleControllers = controllers.filterNot { it.packageName == packageName }
        steeringController = eligibleControllers
            .filter { it.packageName == SPOTIFY_PACKAGE }
            .maxWithOrNull(
                compareBy<MediaController> { controller ->
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0
                }.thenBy { controller -> controller.playbackState?.lastPositionUpdateTime ?: 0L },
            )
        val spotifyAvailable = steeringController != null
        if (spotifyAvailable != steeringSpotifyAvailable) {
            steeringSpotifyAvailable = spotifyAvailable
            TelemetryLog.event(
                "MEDIA_KEY",
                "spotifyController=${if (spotifyAvailable) "available" else "missing"}",
            )
        }
        val preferredPackage = DashboardPreferences.current(this).mediaPackage
        val selectedIndex = selectMediaSessionIndex(
            candidates = eligibleControllers.map { controller ->
                MediaSessionCandidate(
                    packageName = controller.packageName,
                    playbackState = controller.playbackState?.state,
                    lastPositionUpdateTime = controller.playbackState?.lastPositionUpdateTime ?: 0L,
                )
            },
            preferredPackage = preferredPackage,
        )
        val selected = selectedIndex?.let(eligibleControllers::get)
        if (selected?.sessionToken == activeController?.sessionToken) {
            updateSteeringMediaSession()
            LauncherRuntime.setMediaControllers(eligibleControllers, selected)
            LauncherRuntime.updateMediaState(selected)
            return
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = selected
        selected?.registerCallback(controllerCallback)
        updateSteeringMediaSession()
        LauncherRuntime.setMediaControllers(eligibleControllers, selected)
    }

    private fun updateSteeringMediaSession(controller: MediaController? = steeringController) {
        steeringMediaSession?.apply {
            setPlaybackState(steeringPlaybackState(controller?.playbackState?.state ?: PlaybackState.STATE_PAUSED))
            // Keep the correction scoped to Spotify, but do not tie it to the dashboard's selected
            // controller. A paused Spotify session can still receive the first steering key while
            // another stale player reports a more active state.
            isActive = controller != null
        }
    }

    private fun steeringPlaybackState(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        )
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    private companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}

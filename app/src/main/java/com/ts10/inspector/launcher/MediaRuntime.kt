package com.ts10.inspector.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import com.ts10.inspector.media.QfRadioStationNameFilter
import com.ts10.inspector.media.mediaControllerIndexForPackage
import com.ts10.inspector.media.normalizeQfMetadata
import com.ts10.inspector.panel.launchApplicationInContentArea
import com.ts10.inspector.settings.DashboardPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

private data class MediaNotificationSnapshot(
    val packageName: String,
    val title: String,
    val artist: String?,
    val playing: Boolean,
)

/** Media sources: Android MediaSessions, notifications and the QF radio, USB and Bluetooth players. */
internal object MediaRuntime {
    var mediaController: MediaController? = null
        private set
    private var mediaControllers: List<MediaController> = emptyList()
    private var mediaNotification: MediaNotificationSnapshot? = null
    private var qfRadioActive = false
    private var qfBluetoothActive = false
    private val qfRadioStationNameFilter = QfRadioStationNameFilter()
    private var selectedExternalMediaPackage: String? = null
    // The factory Bluetooth app is the AVRCP bridge for the phone. Spotify on the phone does not
    // always publish an Android MediaSession visible to the head unit, but this broadcast still
    // contains its title and artist.
    private var bluetoothRelayMetadata: MediaNotificationSnapshot? = null
    private var heldMediaFocusRequest: AudioFocusRequest? = null
    private var mediaPlaybackResumeJob: Job? = null

    fun setMediaController(controller: MediaController?) {
        mediaController = controller
        updateMediaState(controller)
    }

    fun setMediaControllers(controllers: List<MediaController>, selected: MediaController?) {
        mediaControllers = controllers.distinctBy { it.sessionToken }
        setMediaController(selected)
    }

    fun updateMediaState(controller: MediaController? = mediaController) {
        if (controller == null) {
            mediaNotification?.let { notification ->
                publishMediaNotification(notification)
                return
            }
            bluetoothRelayMetadata
                ?.takeIf { selectedExternalMediaPackage != null }
                ?.let { relay ->
                    val packageName = selectedExternalMediaPackage ?: return@let
                    LauncherStore.state.update {
                        it.copy(
                            mediaPlaying = true,
                            mediaProgress = 0f,
                            mediaTitle = relay.title,
                            mediaArtist = relay.artist,
                            mediaSourceLabel = mediaSourceLabel(packageName),
                            mediaPackage = packageName,
                        )
                    }
                    return
                }
            if (!qfRadioActive && !qfBluetoothActive) {
                LauncherStore.state.update {
                    it.copy(
                        mediaPlaying = false,
                        mediaProgress = 0f,
                        mediaTitle = null,
                        mediaArtist = null,
                        mediaSourceLabel = null,
                        // Keep the selected external source visible while Spotify/YouTube is
                        // launching and has not created its MediaSession yet.
                        mediaPackage = selectedExternalMediaPackage,
                    )
                }
            }
            return
        }
        val playback = controller.playbackState
        val metadata = controller.metadata
        val packageName = controller.packageName
        val activelyPlaying = playback?.state == PlaybackState.STATE_PLAYING ||
            playback?.state == PlaybackState.STATE_BUFFERING
        if (qfRadioActive && packageName !in QF_RADIO_PACKAGES && !activelyPlaying) return
        if (qfBluetoothActive && packageName != QF_BLUETOOTH_PACKAGE && !activelyPlaying) return
        if (activelyPlaying && packageName !in QF_RADIO_PACKAGES) {
            qfRadioActive = false
            qfBluetoothActive = packageName == QF_BLUETOOTH_PACKAGE
            if (selectedExternalMediaPackage == packageName) {
                selectedExternalMediaPackage = null
                releaseTemporaryMediaFocus()
            }
        }
        val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = playback?.position ?: 0L
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        val notification = mediaNotification?.takeIf { it.packageName == packageName }
        val title = listOf(
            android.media.MediaMetadata.METADATA_KEY_TITLE,
            android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            android.media.MediaMetadata.METADATA_KEY_ALBUM,
        ).firstNotNullOfOrNull { key ->
            metadata?.getText(key)?.toString()?.trim()?.takeIf(String::isNotBlank)
        } ?: notification?.title
        val artist = listOf(
            android.media.MediaMetadata.METADATA_KEY_ARTIST,
            android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            android.media.MediaMetadata.METADATA_KEY_AUTHOR,
            android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        ).firstNotNullOfOrNull { key ->
            metadata?.getText(key)?.toString()?.trim()?.takeIf(String::isNotBlank)
        } ?: notification?.artist
        LauncherStore.state.update {
            it.copy(
                mediaPlaying = playback?.state == PlaybackState.STATE_PLAYING ||
                    (notification?.playing == true && !title.isNullOrBlank()),
                mediaProgress = progress.coerceIn(0f, 1f),
                mediaTitle = title,
                mediaArtist = artist,
                mediaSourceLabel = mediaSourceLabel(packageName),
                mediaPackage = packageName,
            )
        }
    }

    fun updateMediaNotification(
        packageName: String,
        title: String,
        artist: String?,
        playing: Boolean,
    ) {
        val notification = MediaNotificationSnapshot(packageName, title, artist, playing)
        mediaNotification = notification
        val controller = mediaControllerForPackage(packageName)
        if (controller != null) {
            updateMediaState(controller)
        } else if (mediaController == null || playing) {
            publishMediaNotification(notification)
        }
    }

    fun clearMediaNotification(packageName: String) {
        if (mediaNotification?.packageName != packageName) return
        mediaNotification = null
        updateMediaState(mediaController)
    }

    private fun publishMediaNotification(notification: MediaNotificationSnapshot) {
        if (qfRadioActive && !notification.playing) return
        if (notification.playing) {
            qfRadioActive = false
            qfBluetoothActive = false
        }
        LauncherStore.state.update {
            it.copy(
                mediaPlaying = notification.playing,
                mediaProgress = 0f,
                mediaTitle = notification.title,
                mediaArtist = notification.artist,
                mediaSourceLabel = mediaSourceLabel(notification.packageName),
                mediaPackage = notification.packageName,
            )
        }
    }

    fun updateQfRadio(intent: Intent) {
        // The TS10 radio keeps broadcasting its last station after another source is chosen.
        // Do not let those stale updates replace the selected app icon before that app creates
        // or starts its MediaSession.
        if (selectedExternalMediaPackage != null && !qfRadioActive) return
        val frequency = intent.getStringExtra(EXTRA_QF_RADIO_FREQUENCY).orEmpty()
        val rawStation = intent.getStringExtra(EXTRA_QF_RADIO_NAME).orEmpty()
        val band = intent.getIntExtra(EXTRA_QF_RADIO_BAND, 0)
        val bandLabel = if (band in 0..2) "FM${band + 1}" else "AM${band % 3 + 1}"
        val station = qfRadioStationNameFilter.update(band, frequency, rawStation)
        val title = station.ifBlank {
            listOf(bandLabel, frequency).filter(String::isNotBlank).joinToString(" ")
        }
        qfRadioActive = true
        qfBluetoothActive = false
        LauncherStore.state.update {
            it.copy(
                mediaPlaying = true,
                mediaProgress = 0f,
                mediaTitle = title.ifBlank { "Radio" },
                mediaArtist = null,
                mediaSourceLabel = "RADIO",
                mediaPackage = QF_RADIO_PACKAGE,
            )
        }
        val discardedStation = normalizeQfMetadata(rawStation)
            ?.takeIf { it != station }
            ?.let { " (RDS pending/discarded: $it)" }
            .orEmpty()
        QfDiagnosticsRecorder.addDiagnosticLog("QF radio: ${title.ifBlank { "no station" }}$discardedStation")
    }

    fun updateQfBluetoothMusic(intent: Intent) {
        val title = normalizeQfMetadata(intent.getStringExtra(EXTRA_QF_BLUETOOTH_TITLE)).orEmpty()
        val artist = normalizeQfMetadata(intent.getStringExtra(EXTRA_QF_BLUETOOTH_ARTIST)).orEmpty()
        bluetoothRelayMetadata = MediaNotificationSnapshot(
            packageName = QF_BLUETOOTH_PACKAGE,
            title = title.ifBlank { "Musica Bluetooth" },
            artist = artist.takeIf(String::isNotBlank),
            playing = true,
        )
        val externalPackage = selectedExternalMediaPackage
        if (externalPackage != null) {
            qfRadioActive = false
            qfBluetoothActive = false
            LauncherStore.state.update {
                it.copy(
                    mediaPlaying = true,
                    mediaProgress = 0f,
                    mediaTitle = title.ifBlank { "Musica Bluetooth" },
                    mediaArtist = artist.takeIf(String::isNotBlank),
                    mediaSourceLabel = mediaSourceLabel(externalPackage),
                    mediaPackage = externalPackage,
                )
            }
            QfDiagnosticsRecorder.addDiagnosticLog("Bluetooth A2DP for $externalPackage: ${title.ifBlank { "no title" }}")
            return
        }
        qfRadioActive = false
        qfBluetoothActive = true
        LauncherStore.state.update {
            it.copy(
                mediaPlaying = true,
                mediaProgress = 0f,
                mediaTitle = title.ifBlank { "Musica Bluetooth" },
                mediaArtist = artist.takeIf(String::isNotBlank),
                mediaSourceLabel = "BLUETOOTH",
                mediaPackage = QF_BLUETOOTH_PACKAGE,
            )
        }
        QfDiagnosticsRecorder.addDiagnosticLog("Bluetooth A2DP: ${title.ifBlank { "sin titulo" }}")
    }

    fun previousMedia(context: Context) {
        val packageName = activeMediaPackage(context)
        val controller = mediaControllerForPackage(packageName)
        when {
            packageName == QF_BLUETOOTH_PACKAGE -> sendQfBluetoothMusicCommand(context, QF_BT_MUSIC_PREVIOUS)
            packageName in QF_RADIO_PACKAGES -> {
                if (qfRadioActive) sendQfCommand(context, QF_RADIO_PREVIOUS)
                else launchApplicationInContentArea(context, packageName!!)
            }
            packageName == QF_MUSIC_PACKAGE -> sendQfMusicCommand(context, switch = -1)
            controller != null -> controller.transportControls.skipToPrevious()
            else -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    fun toggleMedia(context: Context) {
        val packageName = activeMediaPackage(context)
        val controller = mediaControllerForPackage(packageName)
        when {
            packageName == QF_BLUETOOTH_PACKAGE -> {
                val play = !LauncherStore.state.value.mediaPlaying
                sendQfBluetoothMusicCommand(
                    context,
                    if (play) QF_BT_MUSIC_PLAY else QF_BT_MUSIC_PAUSE,
                )
                LauncherStore.state.update { it.copy(mediaPlaying = play) }
            }
            packageName in QF_RADIO_PACKAGES -> launchApplicationInContentArea(context, packageName!!)
            packageName == QF_MUSIC_PACKAGE -> {
                sendQfMusicCommand(
                    context,
                    switch = 0,
                    play = !LauncherStore.state.value.mediaPlaying,
                )
            }
            controller != null -> {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
            else -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun nextMedia(context: Context) {
        val packageName = activeMediaPackage(context)
        val controller = mediaControllerForPackage(packageName)
        when {
            packageName == QF_BLUETOOTH_PACKAGE -> sendQfBluetoothMusicCommand(context, QF_BT_MUSIC_NEXT)
            packageName in QF_RADIO_PACKAGES -> {
                if (qfRadioActive) sendQfCommand(context, QF_RADIO_NEXT)
                else launchApplicationInContentArea(context, packageName!!)
            }
            packageName == QF_MUSIC_PACKAGE -> sendQfMusicCommand(context, switch = 1)
            controller != null -> controller.transportControls.skipToNext()
            else -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun stopMedia(context: Context) {
        val packageName = activeMediaPackage(context)
        val controller = mediaControllerForPackage(packageName)
        when {
            packageName == QF_BLUETOOTH_PACKAGE -> {
                sendQfBluetoothMusicCommand(context, QF_BT_MUSIC_PAUSE)
                LauncherStore.state.update { it.copy(mediaPlaying = false) }
            }
            packageName in QF_RADIO_PACKAGES -> launchApplicationInContentArea(context, packageName!!)
            packageName == QF_MUSIC_PACKAGE -> sendQfMusicCommand(context, switch = 0, play = false)
            controller != null -> controller.transportControls.stop()
            else -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_STOP)
        }
    }

    private fun activeMediaPackage(context: Context): String? = LauncherStore.state.value.mediaPackage
        ?: mediaController?.packageName
        ?: DashboardPreferences.current(context).mediaPackage

    private fun mediaControllerForPackage(packageName: String?): MediaController? {
        val selectedIndex = mediaController?.let { selected ->
            mediaControllers.indexOfFirst { it.sessionToken == selected.sessionToken }
                .takeIf { it >= 0 }
        }
        val index = mediaControllerIndexForPackage(
            controllerPackages = mediaControllers.map { it.packageName },
            panelPackage = packageName,
            selectedControllerIndex = selectedIndex,
        )
        return index?.let(mediaControllers::get)
            ?: mediaController?.takeIf { packageName == null || it.packageName == packageName }
    }

    fun selectMediaSource(context: Context, packageName: String) {
        mediaPlaybackResumeJob?.cancel()
        val preferences = DashboardPreferences.get(context)
        val currentPackage = activeMediaPackage(context)
        selectedExternalMediaPackage = packageName.takeUnless {
            it == QF_BLUETOOTH_PACKAGE ||
                it == QF_MUSIC_PACKAGE ||
                it in QF_RADIO_PACKAGES
        }
        if (currentPackage != null && currentPackage != packageName) {
            pauseCurrentMedia(context, currentPackage)
        }
        preferences.setMedia(packageName)
        qfBluetoothActive = packageName == QF_BLUETOOTH_PACKAGE
        LauncherStore.state.update {
            it.copy(
                mediaPackage = packageName,
                mediaSourceLabel = mediaSourceLabel(packageName),
            )
        }
        when {
            packageName == QF_BLUETOOTH_PACKAGE -> {
                releaseTemporaryMediaFocus()
                launchMediaApplication(context, packageName)
                sendQfBluetoothMusicCommand(context, QF_BT_MUSIC_PLAY)
                LauncherStore.state.update { it.copy(mediaPlaying = true) }
            }
            packageName in QF_RADIO_PACKAGES -> {
                selectedExternalMediaPackage = null
                releaseTemporaryMediaFocus()
                qfRadioActive = true
                launchApplicationInContentArea(context, packageName)
            }
            packageName == QF_MUSIC_PACKAGE -> {
                releaseTemporaryMediaFocus()
                launchApplicationInContentArea(context, packageName)
                sendQfMusicCommand(context, switch = 0, play = true)
            }
            else -> {
                launchApplicationInContentArea(context, packageName)
                val controller = mediaControllerForPackage(packageName)
                if (controller != null &&
                    controller.playbackState?.state != PlaybackState.STATE_PLAYING
                ) {
                    controller.transportControls.play()
                }
            }
        }
    }

    /**
     * Some TS10 builds pause YouTube when a different task takes the right panel even though its
     * MediaSession remains active. Reassert PLAY once after the target activity has settled.
     * Apps configured as media sources are excluded because opening one of those is an intentional
     * source change.
     */
    internal fun preserveActiveMediaPlayback(context: Context, targetPackage: String) {
        mediaPlaybackResumeJob?.cancel()
        val controller = mediaController ?: return
        val playbackState = controller.playbackState?.state
        if (
            controller.packageName == targetPackage ||
            playbackState !in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
        ) {
            return
        }
        val settings = DashboardPreferences.current(context)
        if (targetPackage == settings.mediaPackage || targetPackage in settings.mediaPackages) return

        mediaPlaybackResumeJob = LauncherStore.scope.launch {
            delay(MEDIA_RESUME_AFTER_APP_LAUNCH_MS)
            val currentState = controller.playbackState?.state
            if (currentState != PlaybackState.STATE_PLAYING &&
                currentState != PlaybackState.STATE_BUFFERING
            ) {
                runCatching { controller.transportControls.play() }
                    .onSuccess {
                        QfDiagnosticsRecorder.addDiagnosticLog(
                            "Resuming ${controller.packageName} after opening $targetPackage",
                        )
                    }
            }
        }
    }

    private fun pauseCurrentMedia(context: Context, packageName: String) {
        when {
            packageName == QF_BLUETOOTH_PACKAGE -> {
                sendQfBluetoothMusicCommand(context, QF_BT_MUSIC_PAUSE)
                qfBluetoothActive = false
            }
            packageName == QF_MUSIC_PACKAGE -> sendQfMusicCommand(context, switch = 0, play = false)
            packageName in QF_RADIO_PACKAGES -> {
                qfRadioActive = false
                holdTemporaryMediaFocus(context)
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            mediaControllerForPackage(packageName) != null ->
                mediaControllerForPackage(packageName)?.transportControls?.pause()
            else -> dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    /**
     * Factory Radio is not an Android MediaSession, so a pause transport command cannot stop it.
     * Holding transient audio focus silences it as soon as YouTube (or another normal player) is
     * selected. The external player takes that focus from us when playback begins.
     */
    private fun holdTemporaryMediaFocus(context: Context) {
        releaseTemporaryMediaFocus()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    heldMediaFocusRequest = null
                }
            }
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            heldMediaFocusRequest = request
        }
    }

    private fun releaseTemporaryMediaFocus() {
        val context = LauncherStore.applicationContext ?: return
        val request = heldMediaFocusRequest ?: return
        context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        heldMediaFocusRequest = null
    }

    private fun sendQfMusicCommand(context: Context, switch: Int, play: Boolean = true) {
        val status = if (play) QF_MUSIC_PLAY else QF_MUSIC_PAUSE
        val intent = Intent(ACTION_QF_MEDIA_CONTROL)
            .setPackage(QF_MUSIC_PACKAGE)
            .putExtra(EXTRA_QF_MUSIC_STATUS, status)
            .putExtra(EXTRA_QF_MUSIC_SWITCH, switch)
        context.sendBroadcast(intent)
        QfDiagnosticsRecorder.addDiagnosticLog("Control Musica QF: status=$status switch=$switch")
    }

    private fun sendQfBluetoothMusicCommand(context: Context, command: Int) {
        context.sendBroadcast(
            Intent(ACTION_QF_BLUETOOTH_MUSIC_CONTROL)
                .setPackage(QF_BLUETOOTH_PACKAGE)
                .putExtra(EXTRA_QF_BLUETOOTH_COMMAND, command),
        )
        QfDiagnosticsRecorder.addDiagnosticLog("Control Bluetooth A2DP: command=$command")
    }

    fun launchMediaApplication(context: Context, packageName: String): Boolean =
        launchApplicationInContentArea(
            context = context,
            packageName = packageName,
            activityClassName = if (packageName == QF_BLUETOOTH_PACKAGE) {
                QF_BLUETOOTH_MUSIC_ACTIVITY
            } else {
                null
            },
        )

    private fun sendQfCommand(context: Context, action: String) {
        context.sendBroadcast(Intent(action).setPackage(QF_RADIO_PACKAGE))
        QfDiagnosticsRecorder.addDiagnosticLog("Control Radio QF: $action")
    }

    private fun mediaSourceLabel(packageName: String): String = when (packageName) {
        QF_RADIO_PACKAGE, LEGACY_RADIO_PACKAGE, QF_ORIGINAL_TUNER_PACKAGE -> "RADIO"
        QF_MUSIC_PACKAGE -> "MUSICA USB"
        QF_BLUETOOTH_PACKAGE -> "BLUETOOTH"
        YOUTUBE_PACKAGE -> "YOUTUBE"
        else -> LauncherStore.applicationContext?.let { context ->
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0),
                ).toString().uppercase(Locale.getDefault())
            }.getOrNull()
        } ?: "MULTIMEDIA"
    }

    fun runMediaCustomAction(keyword: String) {
        val controller = mediaControllerForPackage(LauncherStore.state.value.mediaPackage) ?: return
        val action = controller.playbackState?.customActions?.firstOrNull {
            it.action.contains(keyword, ignoreCase = true) ||
                it.name.toString().contains(keyword, ignoreCase = true)
        } ?: return
        controller.transportControls.sendCustomAction(action.action, null)
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private const val ACTION_QF_BLUETOOTH_MUSIC_CONTROL = "com.qf.action.BT.MUSIC.CONTROL"
    private const val ACTION_QF_MEDIA_CONTROL = "com.qf.action.CONTROL_ACTION"
    private const val EXTRA_QF_RADIO_FREQUENCY = "com.qf.radio.update_action_key"
    private const val EXTRA_QF_RADIO_BAND = "com.qf.radio.update_action_band_key"
    private const val EXTRA_QF_RADIO_NAME = "com.qf.radio.update_action_name_key"
    private const val EXTRA_QF_MUSIC_STATUS = "music_player_status_key"
    private const val EXTRA_QF_MUSIC_SWITCH = "music_player_switch_key"
    private const val EXTRA_QF_BLUETOOTH_COMMAND = "command"
    private const val EXTRA_QF_BLUETOOTH_TITLE = "songName"
    private const val EXTRA_QF_BLUETOOTH_ARTIST = "songSinger"
    private const val QF_RADIO_PREVIOUS = "/customize/radio/pre"
    private const val QF_RADIO_NEXT = "/customize/radio/next"
    private const val QF_RADIO_PACKAGE = "com.android.fmradio.ext"
    private const val LEGACY_RADIO_PACKAGE = "com.android.fmradio"
    private const val QF_ORIGINAL_TUNER_PACKAGE = "com.qf.vehicle"
    private const val QF_MUSIC_PACKAGE = "com.qf.musicplayer"
    private const val QF_BLUETOOTH_PACKAGE = "com.qf.bluetooth"
    private const val QF_BLUETOOTH_MUSIC_ACTIVITY = "com.qf.bluetooth.MusicActivity"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val QF_MUSIC_PAUSE = 17
    private const val QF_MUSIC_PLAY = 18
    private const val QF_BT_MUSIC_PREVIOUS = 1
    private const val QF_BT_MUSIC_NEXT = 2
    private const val QF_BT_MUSIC_PLAY = 4
    private const val QF_BT_MUSIC_PAUSE = 5
    private val QF_RADIO_PACKAGES = setOf(
        QF_RADIO_PACKAGE,
        LEGACY_RADIO_PACKAGE,
        QF_ORIGINAL_TUNER_PACKAGE,
    )
    private const val MEDIA_RESUME_AFTER_APP_LAUNCH_MS = 1_000L
}

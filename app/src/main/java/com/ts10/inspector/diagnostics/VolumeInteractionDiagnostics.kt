package com.ts10.inspector.diagnostics

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.ArrayDeque

/** Read-only correlation between carousel gestures and system-owned music-volume changes. */
internal object VolumeInteractionDiagnostics {
    private val events = ArrayDeque<String>()
    private var started = false
    private var audio: AudioManager? = null
    private var lastVolume = -1
    private var lastGestureElapsedMs = 0L
    private var gestureActive = false

    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        lastVolume = currentVolume()
        app.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = sample("settings")
            },
        )
        add("observer start volume=$lastVolume max=${maxVolume()}")
    }

    @Synchronized fun carouselGesture(active: Boolean) {
        if (gestureActive == active) return
        gestureActive = active
        lastGestureElapsedMs = SystemClock.elapsedRealtime()
        add("carousel ${if (active) "DOWN" else "UP"} volume=${currentVolume()}")
        sample("gesture")
    }

    @Synchronized private fun sample(source: String) {
        val volume = currentVolume()
        if (volume < 0 || volume == lastVolume) return
        val sinceGesture = SystemClock.elapsedRealtime() - lastGestureElapsedMs
        val detail = "$lastVolume->$volume source=$source gestureActive=$gestureActive sinceGestureMs=$sinceGesture"
        add("volume $detail")
        TelemetryLog.event("VOLUME_DIAGNOSTIC", detail)
        lastVolume = volume
    }

    @Synchronized fun snapshot(): List<String> = buildList {
        add("Current/max: ${currentVolume()}/${maxVolume()}")
        add("Gesture active: $gestureActive")
        addAll(events)
    }

    private fun currentVolume(): Int = runCatching {
        audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    }.getOrDefault(-1)
    private fun maxVolume(): Int = runCatching {
        audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
    }.getOrDefault(-1)
    private fun add(message: String) {
        events.addLast("uptimeMs=${SystemClock.elapsedRealtime()} $message")
        while (events.size > 40) events.removeFirst()
    }
}

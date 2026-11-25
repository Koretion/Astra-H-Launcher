package com.ts10.inspector.diagnostics

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ts10.inspector.R
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.ui.AppLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ScreenRecordingState(
    val recording: Boolean = false,
    val startedElapsedMs: Long = 0L,
    /** Folder and name of the last finished video, shown in Log and diagnostics. */
    val lastVideo: String? = null,
)

/**
 * Records the whole screen (launcher, panels and overlays) to Movies/AstraLauncher without sound.
 * Android asks for consent every time; the recording lives in a foreground service of this
 * process, so it does not depend on a third-party recorder the ROM may close.
 */
internal object ScreenRecording {
    private val mutableState = MutableStateFlow(ScreenRecordingState())
    val state: StateFlow<ScreenRecordingState> = mutableState.asStateFlow()

    fun toggle(context: Context) {
        if (mutableState.value.recording) stop(context) else ScreenRecordActivity.launch(context)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
    }

    internal fun started() {
        mutableState.value = mutableState.value.copy(recording = true, startedElapsedMs = SystemClock.elapsedRealtime())
    }

    internal fun stopped(lastVideo: String?) {
        mutableState.value = ScreenRecordingState(recording = false, lastVideo = lastVideo ?: mutableState.value.lastVideo)
    }

    internal fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}

/** Transparent activity that only shows Android's screen-capture consent dialog. */
class ScreenRecordActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ExclusiveLauncherSession.complete(this)
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(applicationContext, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenRecordService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } else {
            TelemetryLog.event("SCREEN_RECORD", "consent denied")
            ScreenRecording.toast(applicationContext, AppLocale.string(applicationContext, R.string.screen_record_denied))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            ExclusiveLauncherSession.complete(this)
            finish()
            return
        }
        consent.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        fun launch(context: Context) {
            ExclusiveLauncherSession.begin(context)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    val started = runCatching {
                        context.startActivity(
                            Intent(context, ScreenRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    if (!started) ExclusiveLauncherSession.complete(context)
                },
                120L,
            )
        }
    }
}

class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var output: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var videoName: String? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        // Android or the user revoked the capture (for example from the notification shade).
        override fun onStop() {
            Handler(Looper.getMainLooper()).post { finishRecording() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> finishRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (recorder != null) return
        // The foreground service must exist before the projection is obtained (Android 10+).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        runCatching {
            val manager = checkNotNull(getSystemService(MediaProjectionManager::class.java)) { "no MediaProjectionManager" }
            val capture = checkNotNull(manager.getMediaProjection(resultCode, checkNotNull(data))) { "projection refused" }
            projection = capture
            capture.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            // Hardware encoders want even dimensions.
            val width = metrics.widthPixels and 1.inv()
            val height = metrics.heightPixels and 1.inv()
            val name = "AstraLauncher_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)) + ".mp4"
            val uri = checkNotNull(
                contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    },
                ),
            ) { "MediaStore insert failed" }
            videoUri = uri
            videoName = name
            val descriptor = checkNotNull(contentResolver.openFileDescriptor(uri, "w")) { "cannot open video file" }
            output = descriptor
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = mediaRecorder
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val hevc = hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
            mediaRecorder.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(FRAME_RATE)
            val bitRate = if (hevc) HEVC_BIT_RATE else H264_BIT_RATE
            mediaRecorder.setVideoEncodingBitRate(bitRate)
            mediaRecorder.setOutputFile(descriptor.fileDescriptor)
            mediaRecorder.prepare()
            virtualDisplay = capture.createVirtualDisplay(
                "AstraLauncherRecording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null,
            )
            mediaRecorder.start()
            ScreenRecording.started()
            TelemetryLog.event(
                "SCREEN_RECORD",
                "started ${width}x$height ${if (hevc) "hevc" else "h264"} ${bitRate / 1_000}kbps ${FRAME_RATE}fps $name",
            )
        }.onFailure { error ->
            TelemetryLog.event("SCREEN_RECORD", "start failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
            ScreenRecording.toast(this, AppLocale.string(this, R.string.screen_record_failed, error.message.orEmpty()))
            release(keepVideo = false)
        }
    }

    private fun finishRecording() {
        if (recorder == null && projection == null) {
            release(keepVideo = false)
            return
        }
        // stop() throws when no frame was ever encoded; such a file is unusable and is deleted.
        val encoded = runCatching { recorder?.stop() }.isSuccess
        release(keepVideo = encoded)
    }

    private fun release(keepVideo: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projection?.let { capture ->
            runCatching { capture.unregisterCallback(projectionCallback) }
            runCatching { capture.stop() }
        }
        projection = null
        runCatching { output?.close() }
        output = null
        val uri = videoUri
        val saved = if (uri != null && keepVideo) {
            runCatching {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }.isSuccess
        } else {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            false
        }
        val location = videoName?.takeIf { saved }?.let { "${Environment.DIRECTORY_MOVIES}/$FOLDER/$it" }
        if (location != null) {
            TelemetryLog.event("SCREEN_RECORD", "saved $location")
            ScreenRecording.toast(this, AppLocale.string(this, R.string.screen_record_saved, location))
        } else if (uri != null) {
            TelemetryLog.event("SCREEN_RECORD", "discarded empty recording")
        }
        videoUri = null
        videoName = null
        ScreenRecording.stopped(location)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) finishRecording()
        super.onDestroy()
    }

    private fun notification() = run {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                AppLocale.string(this, R.string.screen_record_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLocale.string(this, R.string.screen_record_title))
            .setContentText(AppLocale.string(this, R.string.screen_record_running))
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(stop)
            .addAction(0, AppLocale.string(this, R.string.screen_record_stop_action), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ts10.inspector.action.SCREEN_RECORD_START"
        const val ACTION_STOP = "com.ts10.inspector.action.SCREEN_RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 0x7E11
        private const val FOLDER = "AstraLauncher"
        /** The launcher UI needs no more; fewer frames also mean a smaller file. */
        private const val FRAME_RATE = 24
        /**
         * Upper limits: the encoders are variable bit rate, so a still screen costs far less (about
         * 3 MB/min measured); full motion stays near 9 MB/min with HEVC and 13 MB/min with H.264.
         */
        private const val HEVC_BIT_RATE = 1_200_000
        private const val H264_BIT_RATE = 1_800_000

        /** Software HEVC encoders are too slow for a live 720p screen; only hardware ones count. */
        private fun hasHardwareEncoder(mime: String): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }
        }.getOrDefault(false)
    }
}

package com.ts10.inspector.diagnostics

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ts10.inspector.BuildConfig
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent, bounded telemetry for physical TS10 vision tests.
 *
 * Writes are serialized on a background-priority thread. Two 512 KiB files are retained, so
 * diagnostics survive process death and reboots without allowing storage use to grow forever.
 * Camera pixels are never written here.
 */
internal object TelemetryLog {
    private const val TAG = "VisionTelemetry"
    private const val CURRENT_FILE = "vision_telemetry.log"
    private const val PREVIOUS_FILE = "vision_telemetry.previous.log"
    private const val MAX_FILE_BYTES = 512L * 1_024L
    private const val FLUSH_TIMEOUT_SECONDS = 2L

    private val thread = HandlerThread(
        "TS10-vision-telemetry",
        Process.THREAD_PRIORITY_BACKGROUND,
    ).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val initialized = AtomicBoolean(false)
    private val sessionId = UUID.randomUUID().toString().take(8)

    @Volatile private var directory: File? = null

    fun initialize(context: Context) {
        if (directory == null) {
            synchronized(lock) {
                if (directory == null) directory = context.applicationContext.filesDir
            }
        }
        if (initialized.compareAndSet(false, true)) {
            event(
                "SESSION",
                "start id=$sessionId app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                    "uptimeMs=${SystemClock.elapsedRealtime()} persistentLimitBytes=${MAX_FILE_BYTES * 2}",
            )
        }
    }

    fun event(category: String, message: String) {
        if (!initialized.get() || directory == null) return
        val safeCategory = sanitize(category).ifBlank { "EVENT" }
        val safeMessage = sanitize(message)
        handler.post { appendLine(safeCategory, safeMessage) }
    }

    /** Called from the report's IO dispatcher. It waits briefly for already queued events. */
    fun snapshot(context: Context): String {
        initialize(context)
        if (Thread.currentThread() !== thread) {
            val pendingWrites = CountDownLatch(1)
            handler.post { pendingWrites.countDown() }
            pendingWrites.await(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return runCatching {
            synchronized(lock) {
                val root = directory ?: return@synchronized "Telemetry directory unavailable"
                buildString {
                    val previous = File(root, PREVIOUS_FILE)
                    val current = File(root, CURRENT_FILE)
                    if (previous.isFile) {
                        appendLine("--- previous rolling file ---")
                        append(previous.readText())
                        if (!endsWith('\n')) appendLine()
                    }
                    appendLine("--- current rolling file ---")
                    if (current.isFile) append(current.readText()) else appendLine("--")
                }
            }
        }.getOrElse { error ->
            "Unable to read persistent telemetry: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    private fun appendLine(category: String, message: String) {
        runCatching {
            val root = directory ?: return
            val line = "${Instant.now()} uptimeMs=${SystemClock.elapsedRealtime()} " +
                "session=$sessionId [$category] $message\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            synchronized(lock) {
                val current = File(root, CURRENT_FILE)
                if (current.length() + bytes.size > MAX_FILE_BYTES) rotate(root, current)
                current.appendBytes(bytes)
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to append persistent vision telemetry", error)
        }
    }

    private fun rotate(root: File, current: File) {
        val previous = File(root, PREVIOUS_FILE)
        if (previous.exists() && !previous.delete()) {
            Log.w(TAG, "Unable to delete previous telemetry file")
        }
        if (current.exists() && !current.renameTo(previous)) {
            current.copyTo(previous, overwrite = true)
            current.writeText("")
        }
    }

    private fun sanitize(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\t', ' ')
        .trim()
}

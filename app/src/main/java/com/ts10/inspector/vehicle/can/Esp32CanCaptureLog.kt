package com.ts10.inspector.vehicle.can

import android.content.Context
import android.os.SystemClock
import com.ts10.inspector.diagnostics.TelemetryLog
import java.io.BufferedWriter
import java.io.File
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional on-device capture of raw ESP32 CAN frames, written off the main thread. */
internal class Esp32CanCaptureLog {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(LOG_QUEUE_LENGTH),
    )
    private var directory: File? = null
    private var file: File? = null
    private var writer: BufferedWriter? = null
    @Volatile private var active = false
    @Volatile var loggedFrames = 0L
        private set
    @Volatile var droppedFrames = 0L
        private set

    fun initialize(context: Context) {
        if (directory == null) directory = File(context.filesDir, "can-captures").apply { mkdirs() }
    }

    fun start(): String? {
        val target = File(directory ?: return null, "TS10_ESP32_CAN_${LocalDateTime.now().format(FILE_TIME)}.log")
        file = target
        active = true
        loggedFrames = 0
        droppedFrames = 0
        submit {
            closeWriter()
            writer = target.bufferedWriter().also {
                it.appendLine("# TS10 ESP32 CAN RAW v1")
                it.appendLine("# Started ${LocalDateTime.now()}")
                it.appendLine("# Fields: esp_us android_ns seq id ext rtr dlc data")
                it.flush()
            }
        }
        return target.absolutePath
    }

    fun stop() {
        active = false
        submit {
            writer?.appendLine("# Stopped ${LocalDateTime.now()} logged=$loggedFrames local_drops=$droppedFrames")
            closeWriter()
        }
    }

    fun clear() {
        val target = file ?: return
        submit {
            closeWriter()
            target.writeText("")
            loggedFrames = 0
            droppedFrames = 0
            if (active) {
                writer = target.bufferedWriter().also {
                    it.appendLine("# TS10 ESP32 CAN RAW v1 (cleared ${LocalDateTime.now()})")
                    it.flush()
                }
            }
        }
    }

    fun append(frame: Esp32CanFrame): Boolean {
        if (!active) return true
        return submit {
            val out = writer ?: return@submit
            val payload = frame.data.joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }
            out.append("esp_us=${frame.timestampUs}")
            out.append(" android_ns=${SystemClock.elapsedRealtimeNanos()}")
            out.append(" bus=${frame.bus.label} seq=${frame.sequence}")
            out.append(" id=%X".format(Locale.US, frame.identifier))
            out.append(" ext=${if (frame.extended) 1 else 0}")
            out.append(" rtr=${if (frame.rtr) 1 else 0}")
            out.append(" dlc=${frame.dlc}")
            out.append(" data=$payload")
            out.newLine()
            loggedFrames++
            if (loggedFrames % LOG_FLUSH_INTERVAL == 0L) out.flush()
        }
    }

    fun event(message: String) {
        if (!active) return
        submit { writer?.appendLine("# EVENT android_ns=${SystemClock.elapsedRealtimeNanos()} $message") }
    }

    fun stats(stats: Esp32CanRemoteStats) {
        if (!active) return
        submit {
            writer?.appendLine(
                "# STATS android_ns=${SystemClock.elapsedRealtimeNanos()} bitrate_kbps=${stats.bitrateKbps} " +
                    "rx=${stats.receivedFrames} queue_drops=${stats.queueDrops} " +
                    "no_client_drops=${stats.noClientDrops} filtered=${stats.filteredFrames} " +
                    "mode=${stats.streamMode} twai_errors=${stats.twaiErrors}",
            )
        }
    }

    fun usbStats(stats: Esp32CanUsbStats) {
        if (!active) return
        submit {
            writer?.appendLine(
                "# TWAI android_ns=${SystemClock.elapsedRealtimeNanos()} " +
                    "state=${stats.swControllerState ?: -1} rx=${stats.swReceived} edges=${stats.swRxEdges} " +
                    "rx_level=${stats.swRxLevelHigh?.let { if (it) 1 else 0 } ?: -1} " +
                    "bus_error_alerts=${stats.swBusErrorAlerts} bus_errors=${stats.swBusErrorCount} " +
                    "rx_error_counter=${stats.swRxErrorCounter} tx_error_counter=${stats.swTxErrorCounter} " +
                    "rx_queue_full=${stats.swRxQueueFullAlerts} rx_pending=${stats.swMessagesPending} " +
                    "rx_missed=${stats.swRxMissedCount} rx_overrun=${stats.swRxOverrunCount} " +
                    "error_passive=${stats.swErrorPassiveAlerts} bus_off=${stats.swBusOffAlerts} " +
                    "above_warn=${stats.swAboveErrorWarningAlerts} below_warn=${stats.swBelowErrorWarningAlerts}",
            )
        }
    }

    fun appendTo(destination: Writer) {
        val target = try {
            executor.submit<File?> {
                writer?.flush()
                file
            }.get(EXPORT_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            destination.appendLine("Could not sync the CAN capture: ${error.message}")
            return
        }
        destination.appendLine()
        destination.appendLine("=== ESP32 LAST KNOWN STATE AT USB CONNECTION ===")
        val initial = directory?.let { File(it, "ESP32_LAST_STATE.log") }
        if (initial?.isFile == true) initial.bufferedReader().use { it.copyTo(destination) }
        else destination.appendLine("No saved state snapshot")
        destination.appendLine()
        destination.appendLine("=== ESP32 CAN RAW COMPLETE CAPTURE ===")
        if (target == null || !target.isFile) {
            destination.appendLine("No CAN capture file")
            return
        }
        target.bufferedReader().use { input -> input.copyTo(destination) }
    }

    fun saveInitialState(snapshot: Esp32CanStateSnapshot) {
        val root = directory ?: return
        val receivedNs = SystemClock.elapsedRealtimeNanos()
        submit {
            runCatching {
                File(root, "ESP32_LAST_STATE.log").bufferedWriter().use { out ->
                    out.appendLine("# Last known state; NOT live CAN or an event history")
                    out.appendLine("# Saved ${LocalDateTime.now()} boot=${snapshot.bootId} generation=${snapshot.generation} snapshot_esp_us=${snapshot.timestampUs} received_android_ns=$receivedNs")
                    snapshot.frames.forEach { frame ->
                        out.appendLine("esp_us=${frame.timestampUs} bus=${frame.bus.label} id=${frame.identifier.toString(16).uppercase()} dlc=${frame.dlc} data=${frame.data.joinToString("") { "%02X".format(Locale.US, it.toInt() and 255) }}")
                    }
                }
            }.onFailure { TelemetryLog.event("CAN_INITIAL_STATE", "No se pudo guardar instantanea: ${it.message}") }
        }
    }

    private fun submit(block: () -> Unit): Boolean = try {
        executor.execute(block)
        true
    } catch (_: RejectedExecutionException) {
        droppedFrames++
        false
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    companion object {
        private const val LOG_QUEUE_LENGTH = 8192
        private const val LOG_FLUSH_INTERVAL = 128L
        private const val EXPORT_FLUSH_TIMEOUT_SECONDS = 30L
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}

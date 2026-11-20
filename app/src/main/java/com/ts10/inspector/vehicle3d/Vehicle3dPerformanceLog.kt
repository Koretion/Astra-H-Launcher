package com.ts10.inspector.vehicle3d

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ts10.inspector.vision.VisionDeviceDiagnostics
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class Vehicle3dResourceMetrics(
    val processCpuCorePercent: Float?,
    val processCpuDevicePercent: Float?,
    val processPssMb: Long,
    val nativeHeapMb: Long,
    val javaHeapMb: Long,
    val systemAvailableMb: Long,
    val thermalStatus: String,
    val batteryTemperatureC: Float?,
)

internal fun calculateVehicle3dCpuPercent(
    elapsedMilliseconds: Long,
    processCpuMilliseconds: Long,
    processors: Int,
): Pair<Float, Float>? {
    if (elapsedMilliseconds <= 0L || processCpuMilliseconds < 0L || processors <= 0) return null
    val corePercent = processCpuMilliseconds.toFloat() * 100f / elapsedMilliseconds
    return corePercent to (corePercent / processors).coerceIn(0f, 100f)
}

/** A bounded, per-visit CSV log for the isolated 3D vehicle laboratory. */
internal class Vehicle3dPerformanceLog(context: Context) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread(
        "TS10-vehicle-3d-metrics",
        Process.THREAD_PRIORITY_BACKGROUND,
    ).apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val sessionStartedMs = SystemClock.elapsedRealtime()
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val directory = File(appContext.filesDir, DIRECTORY)
    private val file = File(directory, "vehicle_3d_${FILE_TIME_FORMAT.format(Instant.now())}_$sessionId.csv")

    private var previousSampleMs = sessionStartedMs
    private var previousProcessCpuMs = Process.getElapsedCpuTime()

    init {
        handler.post {
            directory.mkdirs()
            pruneOldLogs()
            appendLine(CSV_HEADER)
            Log.i(TAG, "session_start id=$sessionId file=${file.absolutePath}")
        }
    }

    fun record(
        event: String,
        camera: String,
        status: String,
        fps: Int,
        submittedFrames: Long,
        loadMilliseconds: Long?,
        onMetrics: ((Vehicle3dResourceMetrics) -> Unit)? = null,
    ) {
        if (closed.get()) return
        handler.post {
            val nowMs = SystemClock.elapsedRealtime()
            val processCpuMs = Process.getElapsedCpuTime()
            val cpu = calculateVehicle3dCpuPercent(
                elapsedMilliseconds = nowMs - previousSampleMs,
                processCpuMilliseconds = processCpuMs - previousProcessCpuMs,
                processors = Runtime.getRuntime().availableProcessors(),
            )
            previousSampleMs = nowMs
            previousProcessCpuMs = processCpuMs

            val device = VisionDeviceDiagnostics.snapshot(appContext)
            val metrics = Vehicle3dResourceMetrics(
                processCpuCorePercent = cpu?.first,
                processCpuDevicePercent = cpu?.second,
                processPssMb = device.processPssMb,
                nativeHeapMb = Debug.getNativeHeapAllocatedSize().toMb(),
                javaHeapMb = device.javaHeapUsedMb,
                systemAvailableMb = device.memoryAvailableMb,
                thermalStatus = device.thermalStatus,
                batteryTemperatureC = device.batteryTemperatureC,
            )
            val line = listOf(
                Instant.now().toString(),
                (nowMs - sessionStartedMs).toString(),
                event,
                camera,
                status,
                fps.toString(),
                submittedFrames.toString(),
                loadMilliseconds?.toString().orEmpty(),
                metrics.processCpuCorePercent.decimalOrEmpty(),
                metrics.processCpuDevicePercent.decimalOrEmpty(),
                metrics.processPssMb.toString(),
                metrics.nativeHeapMb.toString(),
                metrics.javaHeapMb.toString(),
                device.javaHeapMaxMb.toString(),
                metrics.systemAvailableMb.toString(),
                device.memoryTotalMb.toString(),
                metrics.thermalStatus,
                metrics.batteryTemperatureC.decimalOrEmpty(),
            ).joinToString(",", transform = ::csvCell)
            appendLine(line)
            Log.i(
                TAG,
                "event=$event camera=$camera status=$status fps=$fps frames=$submittedFrames " +
                    "loadMs=${loadMilliseconds ?: "--"} cpuCore=${metrics.processCpuCorePercent.decimalOrEmpty()}% " +
                    "cpuDevice=${metrics.processCpuDevicePercent.decimalOrEmpty()}% " +
                    "pssMb=${metrics.processPssMb} nativeMb=${metrics.nativeHeapMb} " +
                    "javaMb=${metrics.javaHeapMb} availableMb=${metrics.systemAvailableMb} " +
                    "thermal=${metrics.thermalStatus} batteryC=${metrics.batteryTemperatureC.decimalOrEmpty()}",
            )
            if (onMetrics != null) mainHandler.post { if (!closed.get()) onMetrics(metrics) }
        }
    }

    fun close(
        camera: String,
        status: String,
        fps: Int,
        submittedFrames: Long,
        loadMilliseconds: Long?,
    ) {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            val line = listOf(
                Instant.now().toString(),
                (SystemClock.elapsedRealtime() - sessionStartedMs).toString(),
                "session_end",
                camera,
                status,
                fps.toString(),
                submittedFrames.toString(),
                loadMilliseconds?.toString().orEmpty(),
            ).plus(List(10) { "" }).joinToString(",", transform = ::csvCell)
            appendLine(line)
            Log.i(TAG, "session_end id=$sessionId file=${file.absolutePath}")
            thread.quitSafely()
        }
    }

    private fun appendLine(line: String) {
        runCatching { file.appendText("$line\n", Charsets.UTF_8) }
            .onFailure { Log.w(TAG, "Unable to append 3D metrics", it) }
    }

    private fun pruneOldLogs() {
        directory.listFiles { candidate -> candidate.isFile && candidate.extension == "csv" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_RETAINED_FILES - 1)
            ?.forEach { stale ->
                if (!stale.delete()) Log.w(TAG, "Unable to delete old 3D metrics: ${stale.name}")
            }
    }

    private fun Long.toMb(): Long = this / (1_024L * 1_024L)

    private fun Float?.decimalOrEmpty(): String =
        this?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()

    companion object {
        private const val TAG = "Vehicle3dMetrics"
        private const val DIRECTORY = "vehicle-3d-metrics"
        private const val MAX_RETAINED_FILES = 10
        private const val CSV_HEADER =
            "timestamp_utc,elapsed_ms,event,camera,status,fps,submitted_frames,load_ms," +
                "process_cpu_core_pct,process_cpu_device_pct,process_pss_mb,native_heap_mb," +
                "java_heap_mb,java_heap_max_mb,system_available_mb,system_total_mb," +
                "thermal_status,battery_temperature_c"
        private val FILE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneOffset.UTC)

        fun snapshot(context: Context): String {
            val root = File(context.filesDir, DIRECTORY)
            val logs = root.listFiles { candidate -> candidate.isFile && candidate.extension == "csv" }
                ?.sortedBy(File::lastModified)
                ?.takeLast(MAX_RETAINED_FILES)
                .orEmpty()
            if (logs.isEmpty()) return "No vehicle 3D measurements recorded\n"
            return buildString {
                logs.forEach { log ->
                    appendLine("--- ${log.name} ---")
                    append(runCatching { log.readText() }.getOrElse { "Unable to read: ${it.message}\n" })
                    if (!endsWith('\n')) appendLine()
                }
            }
        }

        private fun csvCell(value: String): String {
            val escaped = value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ')
            return if (escaped.any { it == ',' || it == '\"' }) "\"$escaped\"" else escaped
        }
    }
}

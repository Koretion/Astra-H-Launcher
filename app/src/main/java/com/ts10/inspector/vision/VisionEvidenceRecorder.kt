package com.ts10.inspector.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.MediaStore
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.vehicle.fuel.distanceMeters
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Bounded local evidence collection for classifiers that need real TS10 camera crops.
 * Only small candidate crops are stored, never continuous video or whole frames.
 *
 * Desactivado por defecto (Ajustes › Avanzado): el launcher no usa estas imágenes para funcionar.
 * Al arrancar se borran las de más de [RETENTION_DAYS] días y, si quedan más de [MAX_STORED], las
 * más antiguas.
 */
internal object VisionEvidenceRecorder {
    private val thread = HandlerThread("TS10-vision-evidence", Process.THREAD_PRIORITY_BACKGROUND)
        .apply { start() }
    private val handler = Handler(thread.looper)
    private val savedThisProcess = AtomicInteger(0)
    private val lastSpeedSaveElapsed = AtomicLong(0L)
    private val lastLightSaveElapsed = AtomicLong(0L)

    fun recordSpeedCandidates(
        context: Context,
        frame: VisionRgbFrame,
        candidates: List<SpeedSignCandidate>,
        nowElapsed: Long,
    ) {
        if (!enabled(context)) return
        if (candidates.isEmpty() || !reserve(lastSpeedSaveElapsed, nowElapsed, SPEED_SAVE_INTERVAL_MS)) return
        val captures = candidates.take(MAX_CROPS_PER_EVENT).mapNotNull { candidate ->
            copyRgbCrop(frame, candidate.box, CROP_PADDING)
        }
        if (captures.isEmpty()) return
        val osmRoad = OsmRoadContextProvider.state.value.road
        val category = osmRoad?.maxSpeedKmh?.let { "speed_candidate_osm_$it" } ?: "speed_candidate_unlabelled"
        handler.post {
            captures.forEachIndexed { index, bitmap ->
                save(context.applicationContext, bitmap, category, index)
                bitmap.recycle()
            }
            osmRoad?.let {
                TelemetryLog.event(
                    "EVIDENCE",
                    "speed OSM label=${it.maxSpeedKmh ?: "unknown"} way=${it.wayId} " +
                        "distance=${"%.1f".format(java.util.Locale.US, it.distanceMeters)}m",
                )
            }
        }
    }

    fun recordTrafficLights(
        context: Context,
        frame: VisionRgbFrame,
        detections: List<LocalVisionDetection>,
        nowElapsed: Long,
    ) {
        if (!enabled(context)) return
        val lights = detections.filter { it.kind == RoadObjectKind.TRAFFIC_LIGHT }
        if (lights.isEmpty() || !reserve(lastLightSaveElapsed, nowElapsed, LIGHT_SAVE_INTERVAL_MS)) return
        val captures = lights.take(MAX_CROPS_PER_EVENT).mapNotNull { detection ->
            copyRgbCrop(frame, detection.box, CROP_PADDING)
                ?.let { detection.trafficLightColor.logLabel to it }
        }
        handler.post {
            captures.forEachIndexed { index, (label, bitmap) ->
                save(context.applicationContext, bitmap, "traffic_light_$label", index)
                bitmap.recycle()
            }
        }
    }

    private fun enabled(context: Context): Boolean =
        DashboardPreferences.current(context).visionEvidenceEnabled

    /** Borra en segundo plano las imágenes caducadas o que pasan del máximo. */
    fun cleanUp(context: Context) {
        val app = context.applicationContext
        handler.post {
            val cutoffSeconds = System.currentTimeMillis() / 1000 - RETENTION_DAYS * 24 * 60 * 60
            val stored = storedImages(app)
            val expired = stored.filter { it.second < cutoffSeconds }
            val overflow = (stored - expired.toSet()).drop(MAX_STORED)
            val removed = (expired + overflow).count { (uri, _) -> runCatching { app.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) }
            if (removed > 0) TelemetryLog.event("EVIDENCE", "cleanup removed=$removed kept=${stored.size - removed}")
        }
    }

    /** Borra todas las imágenes guardadas; [onDone] recibe cuántas se borraron. */
    fun deleteAll(context: Context, onDone: (Int) -> Unit) {
        val app = context.applicationContext
        handler.post {
            val removed = storedImages(app).count { (uri, _) -> runCatching { app.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) }
            TelemetryLog.event("EVIDENCE", "deleted all removed=$removed")
            onDone(removed)
        }
    }

    /** Imágenes propias en Pictures/AstraVision, de la más reciente a la más antigua (uri, fecha en segundos). */
    private fun storedImages(context: Context): List<Pair<android.net.Uri, Long>> = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$EVIDENCE_ROOT/%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)) to cursor.getLong(1))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun reserve(clock: AtomicLong, nowElapsed: Long, intervalMs: Long): Boolean {
        if (savedThisProcess.get() >= MAX_SAVED_PER_PROCESS) return false
        val previous = clock.get()
        if (nowElapsed - previous < intervalMs) return false
        return clock.compareAndSet(previous, nowElapsed)
    }

    private fun save(context: Context, bitmap: Bitmap, category: String, index: Int) {
        if (savedThisProcess.incrementAndGet() > MAX_SAVED_PER_PROCESS) return
        val name = "${category}_${Instant.now().toString().replace(':', '-')}_$index.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "$EVIDENCE_ROOT/$category")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        runCatching {
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert rejected")
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
            } ?: error("MediaStore without stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            TelemetryLog.event(
                "EVIDENCE",
                "saved category=$category size=${bitmap.width}x${bitmap.height} count=${savedThisProcess.get()}",
            )
        }.onFailure { error ->
            TelemetryLog.event(
                "EVIDENCE",
                "save failed category=$category error=${error.javaClass.simpleName}:${error.message.orEmpty()}",
            )
        }
    }

    private fun copyRgbCrop(frame: VisionRgbFrame, box: NormalizedDetectionBox, padding: Float): Bitmap? {
        val rect = cropRect(box, frame.width, frame.height, padding) ?: return null
        val pixels = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) for (x in 0 until rect.width) {
            pixels[y * rect.width + x] = (0xff shl 24) or frame.rgb(rect.left + x, rect.top + y)
        }
        return Bitmap.createBitmap(pixels, rect.width, rect.height, Bitmap.Config.ARGB_8888)
    }

    private fun cropRect(
        box: NormalizedDetectionBox,
        width: Int,
        height: Int,
        padding: Float,
    ): PixelCropRect? {
        val rawLeft = (box.left * width).toInt()
        val rawTop = (box.top * height).toInt()
        val rawRight = (box.right * width).toInt()
        val rawBottom = (box.bottom * height).toInt()
        val pad = (max(rawRight - rawLeft, rawBottom - rawTop) * padding).toInt().coerceAtLeast(2)
        val left = (rawLeft - pad).coerceIn(0, width - 1)
        val top = (rawTop - pad).coerceIn(0, height - 1)
        val right = (rawRight + pad).coerceIn(left + 1, width)
        val bottom = (rawBottom + pad).coerceIn(top + 1, height)
        if (right - left < 4 || bottom - top < 4) return null
        return PixelCropRect(left, top, right - left, bottom - top)
    }

    private data class PixelCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

    private const val SPEED_SAVE_INTERVAL_MS = 8_000L
    private const val LIGHT_SAVE_INTERVAL_MS = 6_000L
    private const val MAX_CROPS_PER_EVENT = 2
    private const val MAX_SAVED_PER_PROCESS = 60
    private const val CROP_PADDING = 0.35f
    private const val JPEG_QUALITY = 92
    private const val EVIDENCE_ROOT = "Pictures/AstraVision"
    private const val RETENTION_DAYS = 14L
    private const val MAX_STORED = 400
}

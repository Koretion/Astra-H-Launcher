package com.ts10.inspector.trips

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

internal data class TrackingSession(
    val id: Long,
    val key: String,
    val startUtc: String,
    val endUtc: String?,
    val status: String,
    val closeReason: String?,
    val pointCount: Int,
    val distanceKm: Double,
    val maxSpeedKmh: Float,
    val driverProfileId: String?,
    val driverProfileName: String?,
)

internal data class TrackingPoint(
    val id: Long,
    val sequence: Int,
    val point: StoredPoint,
)

internal data class PendingBatch(
    val session: TrackingSession,
    val points: List<TrackingPoint>,
)

internal class TrackingDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_key TEXT NOT NULL UNIQUE,
                start_utc TEXT NOT NULL,
                end_utc TEXT,
                status TEXT NOT NULL,
                close_reason TEXT,
                point_count INTEGER NOT NULL DEFAULT 0,
                distance_km REAL NOT NULL DEFAULT 0,
                max_speed_kmh REAL NOT NULL DEFAULT 0,
                driver_profile_id TEXT,
                driver_profile_name TEXT,
                close_uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                sequence INTEGER NOT NULL,
                utc TEXT NOT NULL,
                fix_time_ms INTEGER NOT NULL,
                fix_elapsed_ms INTEGER NOT NULL,
                provider TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude_m REAL,
                speed_kmh REAL NOT NULL,
                gps_speed_kmh REAL NOT NULL,
                speed_source TEXT NOT NULL,
                accuracy_m REAL NOT NULL,
                satellites INTEGER NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0,
                UNIQUE(session_id, sequence),
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX points_pending ON points(uploaded, session_id, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE points ADD COLUMN fix_time_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE points ADD COLUMN fix_elapsed_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE points ADD COLUMN provider TEXT NOT NULL DEFAULT 'legacy'")
            db.execSQL(
                "UPDATE points SET fix_time_ms = CAST(strftime('%s', utc) AS INTEGER) * 1000 WHERE fix_time_ms = 0",
            )
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE points ADD COLUMN gps_speed_kmh REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE points ADD COLUMN speed_source TEXT NOT NULL DEFAULT 'GPS'")
            db.execSQL("UPDATE points SET gps_speed_kmh = speed_kmh")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN driver_profile_id TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN driver_profile_name TEXT")
        }
    }

    fun activeSession(): TrackingSession? = readableDatabase.rawQuery(
        "SELECT * FROM sessions WHERE status = ? ORDER BY id DESC LIMIT 1",
        arrayOf(STATUS_OPEN),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toSession() else null }

    fun createSession(startUtc: String, driverProfileId: String?, driverProfileName: String?): TrackingSession {
        val db = writableDatabase
        val key = "session_${UUID.randomUUID()}"
        val id = db.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("session_key", key)
                put("start_utc", startUtc)
                put("status", STATUS_OPEN)
                driverProfileId?.let { put("driver_profile_id", it) }
                driverProfileName?.let { put("driver_profile_name", it) }
            },
        )
        return sessionById(id) ?: error("No se pudo crear la sesion $key")
    }

    fun insertPoint(session: TrackingSession, point: StoredPoint, movementMeters: Double) {
        val sequence = session.pointCount + 1
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "points",
                null,
                ContentValues().apply {
                    put("session_id", session.id)
                    put("sequence", sequence)
                    put("utc", point.utc)
                    put("fix_time_ms", point.epochMillis)
                    put("fix_elapsed_ms", point.elapsedRealtimeMs)
                    put("provider", point.provider)
                    put("latitude", point.latitude)
                    put("longitude", point.longitude)
                    point.altitudeMeters?.let { put("altitude_m", it) } ?: putNull("altitude_m")
                    put("speed_kmh", point.speedKmh)
                    put("gps_speed_kmh", point.gpsSpeedKmh)
                    put("speed_source", point.speedSource)
                    put("accuracy_m", point.accuracyMeters)
                    put("satellites", point.satellites)
                },
            )
            db.execSQL(
                """
                UPDATE sessions
                SET point_count = point_count + 1,
                    distance_km = distance_km + ?,
                    max_speed_kmh = MAX(max_speed_kmh, ?)
                WHERE id = ?
                """.trimIndent(),
                arrayOf(movementMeters / 1_000.0, point.speedKmh, session.id),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun lastPoint(sessionId: Long): StoredPoint? = readableDatabase.rawQuery(
        "SELECT * FROM points WHERE session_id = ? ORDER BY sequence DESC LIMIT 1",
        arrayOf(sessionId.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPoint() else null }

    fun closeSession(sessionId: Long, endUtc: String, reason: String) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("status", STATUS_CLOSED)
                put("end_utc", endUtc)
                put("close_reason", reason)
            },
            "id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    fun deleteSession(sessionId: Long) {
        writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId.toString()))
    }

    fun pendingPointBatch(batchSize: Int): PendingBatch? {
        val db = readableDatabase
        val sessionId = db.rawQuery(
            """
            SELECT p.session_id
            FROM points p
            JOIN sessions s ON s.id = p.session_id
            WHERE p.uploaded = 0
              AND (s.status = ? OR s.point_count >= ? OR s.distance_km >= ?)
            ORDER BY p.id
            LIMIT 1
            """.trimIndent(),
            arrayOf(STATUS_CLOSED, "4", "0.02"),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return null }
        val session = sessionById(sessionId) ?: return null
        val points = db.rawQuery(
            "SELECT * FROM points WHERE uploaded = 0 AND session_id = ? ORDER BY sequence LIMIT ?",
            arrayOf(sessionId.toString(), batchSize.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TrackingPoint(
                            id = cursor.long("id"),
                            sequence = cursor.int("sequence"),
                            point = cursor.toPoint(),
                        ),
                    )
                }
            }
        }
        return points.takeIf(List<TrackingPoint>::isNotEmpty)?.let { PendingBatch(session, it) }
    }

    fun markPointsUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE points SET uploaded = 1 WHERE id IN (${ids.joinToString(",") { "?" }})",
            ids.toTypedArray(),
        )
    }

    fun nextPendingClose(): TrackingSession? = readableDatabase.rawQuery(
        """
        SELECT s.* FROM sessions s
        WHERE s.status = ?
          AND s.close_uploaded = 0
          AND NOT EXISTS (
              SELECT 1 FROM points p WHERE p.session_id = s.id AND p.uploaded = 0
          )
        ORDER BY s.id
        LIMIT 1
        """.trimIndent(),
        arrayOf(STATUS_CLOSED),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toSession() else null }

    fun markCloseUploaded(sessionId: Long) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("close_uploaded", 1) },
            "id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    fun pendingUploadCount(): Int = readableDatabase.rawQuery(
        """
        SELECT
          (SELECT COUNT(*) FROM points WHERE uploaded = 0) +
          (SELECT COUNT(*) FROM sessions WHERE status = ? AND close_uploaded = 0)
        """.trimIndent(),
        arrayOf(STATUS_CLOSED),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun sessionCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sessions",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun recentTrips(limit: Int): List<VehicleTripSummary> = readableDatabase.rawQuery(
        """
        SELECT s.id, s.start_utc, s.end_utc, s.distance_km, s.max_speed_kmh,
               s.driver_profile_id, s.driver_profile_name,
               COALESCE(AVG(CASE WHEN p.speed_kmh >= 3 THEN p.speed_kmh END), 0) AS average_speed
        FROM sessions s
        LEFT JOIN points p ON p.session_id = s.id
        WHERE s.status = ?
        GROUP BY s.id
        ORDER BY s.id DESC
        LIMIT ?
        """.trimIndent(),
        arrayOf(STATUS_CLOSED, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val sessionId = cursor.getLong(0)
                add(
                    VehicleTripSummary(
                        id = sessionId,
                        startUtc = cursor.getString(1),
                        endUtc = cursor.takeUnless { it.isNull(2) }?.getString(2),
                        distanceKm = cursor.getDouble(3),
                        maxSpeedKmh = cursor.getFloat(4),
                        driverProfileId = cursor.takeUnless { it.isNull(5) }?.getString(5),
                        driverProfileName = cursor.takeUnless { it.isNull(6) }?.getString(6).orEmpty(),
                        averageSpeedKmh = cursor.getFloat(7),
                        // Reserved until a trustworthy CAN/OBD fuel integral is persisted.
                        consumptionLitersPer100Km = null,
                        route = routeForSession(sessionId),
                    ),
                )
            }
        }
    }

    private fun routeForSession(sessionId: Long): List<VehicleTripMapPoint> {
        val total = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM points WHERE session_id = ?",
            arrayOf(sessionId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val stride = (total / MAX_ROUTE_PREVIEW_POINTS).coerceAtLeast(1)
        return readableDatabase.rawQuery(
            """
            SELECT latitude, longitude FROM points
            WHERE session_id = ? AND (sequence = 1 OR sequence % ? = 0 OR sequence = ?)
            ORDER BY sequence
            """.trimIndent(),
            arrayOf(sessionId.toString(), stride.toString(), total.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(VehicleTripMapPoint(cursor.getDouble(0), cursor.getDouble(1)))
                }
            }
        }
    }

    fun pointCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM points",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun pruneUploadedSessions(retain: Int) {
        val db = writableDatabase
        val removable = db.rawQuery(
            """
            SELECT id FROM sessions
            WHERE status = ? AND close_uploaded = 1
            ORDER BY id DESC
            LIMIT -1 OFFSET ?
            """.trimIndent(),
            arrayOf(STATUS_CLOSED, retain.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        removable.forEach { deleteSession(it) }
    }

    fun sessionById(id: Long): TrackingSession? = readableDatabase.rawQuery(
        "SELECT * FROM sessions WHERE id = ?",
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toSession() else null }

    private fun Cursor.toSession() = TrackingSession(
        id = long("id"),
        key = string("session_key"),
        startUtc = string("start_utc"),
        endUtc = nullableString("end_utc"),
        status = string("status"),
        closeReason = nullableString("close_reason"),
        pointCount = int("point_count"),
        distanceKm = double("distance_km"),
        maxSpeedKmh = float("max_speed_kmh"),
        driverProfileId = nullableString("driver_profile_id"),
        driverProfileName = nullableString("driver_profile_name"),
    )

    private fun Cursor.toPoint() = StoredPoint(
        utc = string("utc"),
        epochMillis = long("fix_time_ms").takeIf { it > 0L }
            ?: runCatching { Instant.parse(string("utc")).toEpochMilli() }.getOrDefault(0L),
        elapsedRealtimeMs = long("fix_elapsed_ms"),
        provider = string("provider"),
        latitude = double("latitude"),
        longitude = double("longitude"),
        altitudeMeters = nullableDouble("altitude_m"),
        speedKmh = float("speed_kmh"),
        gpsSpeedKmh = float("gps_speed_kmh"),
        speedSource = string("speed_source"),
        accuracyMeters = float("accuracy_m"),
        satellites = int("satellites"),
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name))
    private fun Cursor.nullableString(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.int(name: String) = getInt(index(name))
    private fun Cursor.float(name: String) = getFloat(index(name))
    private fun Cursor.double(name: String) = getDouble(index(name))
    private fun Cursor.nullableDouble(name: String) = index(name).let { if (isNull(it)) null else getDouble(it) }

    private companion object {
        const val MAX_ROUTE_PREVIEW_POINTS = 160
        const val DATABASE_NAME = "vehicle_tracking.db"
        const val DATABASE_VERSION = 4
        const val STATUS_OPEN = "open"
        const val STATUS_CLOSED = "closed"
    }
}

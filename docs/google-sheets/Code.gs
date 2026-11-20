/**
 * Astra Launcher - receptor V4 de trayectos para Google Sheets.
 *
 * Toda la configuracion se guarda en:
 * Apps Script > Configuracion del proyecto > Propiedades de la secuencia de comandos.
 * Los nombres admitidos y sus valores predeterminados estan documentados en
 * README.es.md de esta carpeta. No escribas secretos dentro de este archivo.
 *
 * Despliegue: sustituir el codigo de la implementacion existente y publicar una nueva
 * version de la Web App conservando su URL /exec. Copiar el archivo en el editor no actualiza
 * por si solo la version que atiende a la radio.
 */
const TRACKER_SERVER_VERSION = 4;

const CFG = Object.freeze({
  get API_KEY() { return scriptProperty_('API_KEY', ''); },
  get SHEET_POINTS() { return scriptProperty_('SHEET_POINTS', 'points'); },
  get SHEET_SESSIONS() { return scriptProperty_('SHEET_SESSIONS', 'sessions'); },
  get SHEET_BATCHES() { return scriptProperty_('SHEET_BATCHES', 'batches'); },
  get TZ() { return scriptProperty_('TZ', 'Europe/Madrid'); },
  get DRIVE_GPX_FOLDER_ID() { return scriptProperty_('DRIVE_GPX_FOLDER_ID', ''); },
  get GPX_UPDATE_ON_EVERY_POST() { return scriptBoolean_('GPX_UPDATE_ON_EVERY_POST', false); },
  get MAPS_MAX_POINTS() { return scriptNumber_('MAPS_MAX_POINTS', 20); },
  get MAPS_MAX_URL_LENGTH() { return scriptNumber_('MAPS_MAX_URL_LENGTH', 1800); },
  get MAPS_STOP_MINUTES() { return scriptNumber_('MAPS_STOP_MINUTES', 5); },
  get MAPS_STOP_MAX_SPEED_KMH() { return scriptNumber_('MAPS_STOP_MAX_SPEED_KMH', 10); },
  get MAPS_STOP_RADIUS_M() { return scriptNumber_('MAPS_STOP_RADIUS_M', 40); },
  get MAPS_TURN_MIN_DEG() { return scriptNumber_('MAPS_TURN_MIN_DEG', 30); },
  get MAPS_TURN_MIN_SEGMENT_M() { return scriptNumber_('MAPS_TURN_MIN_SEGMENT_M', 60); },
  get MAPS_KEY_POINT_INTERVAL_KM() { return scriptNumber_('MAPS_KEY_POINT_INTERVAL_KM', 10); },
  get TRACK_MAX_ACCURACY_M() { return scriptNumber_('TRACK_MAX_ACCURACY_M', 60); },
  get TRACK_MAX_SEGMENT_SPEED_KMH() { return scriptNumber_('TRACK_MAX_SEGMENT_SPEED_KMH', 180); },
  get TRACK_MAX_REPORTED_SPEED_KMH() { return scriptNumber_('TRACK_MAX_REPORTED_SPEED_KMH', 220); },
  get TRACK_MOVING_MIN_SPEED_KMH() { return scriptNumber_('TRACK_MOVING_MIN_SPEED_KMH', 3); },
  get TRACK_MAX_NORMAL_GAP_SEC() { return scriptNumber_('TRACK_MAX_NORMAL_GAP_SEC', 120); },
  get RECALC_SESSIONS_PER_RUN() { return scriptNumber_('RECALC_SESSIONS_PER_RUN', 25); },
  get RECALC_TIME_BUDGET_SEC() { return scriptNumber_('RECALC_TIME_BUDGET_SEC', 240); },
  get TELEGRAM_ENABLED() { return scriptBoolean_('TELEGRAM_ENABLED', false); },
  get TELEGRAM_BOT_TOKEN() { return scriptProperty_('TELEGRAM_BOT_TOKEN', ''); },
  get TELEGRAM_CHAT_ID() {
    return scriptProperty_('TELEGRAM_CHAT_IDS', scriptProperty_('TELEGRAM_CHAT_ID', ''));
  },
  get TELEGRAM_DISABLE_PREVIEW() { return scriptBoolean_('TELEGRAM_DISABLE_PREVIEW', true); },
  get TELEGRAM_MAP_ENABLED() { return scriptBoolean_('TELEGRAM_MAP_ENABLED', true); },
  get GEOAPIFY_API_KEY() { return scriptProperty_('GEOAPIFY_API_KEY', ''); },
  get GEOAPIFY_MAP_WIDTH() { return scriptNumber_('GEOAPIFY_MAP_WIDTH', 900); },
  get GEOAPIFY_MAP_HEIGHT() { return scriptNumber_('GEOAPIFY_MAP_HEIGHT', 600); },
  get GEOAPIFY_MAP_STYLE() { return scriptProperty_('GEOAPIFY_MAP_STYLE', 'osm-bright'); },
  get STALE_CLOSE_MINUTES() { return scriptNumber_('STALE_CLOSE_MINUTES', 20); },
  get STALE_SCAN_LOOKBACK_HOURS() { return scriptNumber_('STALE_SCAN_LOOKBACK_HOURS', 72); },
});

const POINT_HEADERS = [
  'rx_utc', 'device_id', 'session_id', 'batch_uid', 'point_seq', 'point_uid',
  'utc', 'lat', 'lon', 'speed_kmh', 'alt_m', 'satellites', 'fix', 'fix_3d',
  'rssi_dbm', 'accuracy_m', 'provider', 'gps_speed_kmh', 'speed_source'
];
const SESSION_HEADERS = [
  'rx_utc', 'device_id', 'session_id', 'uploaded_utc', 'start_utc', 'end_utc',
  'close_reason', 'status', 'last_batch_uid', 'last_points_count', 'max_speed_kmh',
  'distance_km', 'gpx_file_id', 'gpx_drive_url', 'gpx_download_url', 'gmaps_url',
  'updated_utc', 'raw_points_count', 'valid_points_count', 'rejected_points_count',
  'anomaly_count', 'duration_s', 'moving_duration_s', 'stopped_duration_s', 'unknown_duration_s', 'average_speed_kmh',
  'moving_average_speed_kmh', 'max_speed_observed_kmh', 'max_speed_confirmed_kmh',
  'stop_count', 'key_points_count', 'track_quality', 'driver_profile_id',
  'driver_profile_name'
];
const BATCH_HEADERS = [
  'batch_key', 'rx_utc', 'device_id', 'session_id', 'batch_uid', 'point_count'
];
const TELEGRAM_LEGACY_NOTIFICATION_PREFIX = 'tg_notified:';
const TELEGRAM_NOTIFICATION_PREFIX = 'tg_v4_notified:';
const TELEGRAM_NOTIFICATION_MARKER_LIMIT = 200;

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('Tracker TS10')
    .addItem('Preparar o actualizar hojas', 'setupTrackerSheets')
    .addItem('Instalar mantenimiento', 'installMaintenanceTrigger')
    .addItem('Recalcular todas las sesiones (V4)', 'recalculateAllSessionsV4')
    .addItem('Ver estado del recalculo V4', 'showRecalculationStatusV4')
    .addItem('Cancelar recalculo V4', 'cancelRecalculateAllSessionsV4')
    .addItem('Enviar resumen V4 de la última sesión', 'sendLatestSessionSummaryV4')
    .addItem('Probar Telegram', 'testTelegram')
    .addItem('Limpiar marcas antiguas de Telegram', 'cleanupTelegramNotificationProperties')
    .addToUi();
}

function setupTrackerSheets() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  ensureSheetByHeaders_(ss, CFG.SHEET_POINTS, POINT_HEADERS);
  ensureSheetByHeaders_(ss, CFG.SHEET_SESSIONS, SESSION_HEADERS);
  ensureSheetByHeaders_(ss, CFG.SHEET_BATCHES, BATCH_HEADERS);
  SpreadsheetApp.flush();
  return 'Hojas de tracking V4 preparadas sin borrar datos existentes';
}

function doGet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return jsonOut({
    ok: true,
    service: 'Astra Launcher vehicle tracker V4',
    server_version: TRACKER_SERVER_VERSION,
    sheets_ready: !!(
      ss.getSheetByName(CFG.SHEET_POINTS) &&
      ss.getSheetByName(CFG.SHEET_SESSIONS) &&
      ss.getSheetByName(CFG.SHEET_BATCHES)
    ),
    api_key_configured: !!CFG.API_KEY,
    telegram_enabled: CFG.TELEGRAM_ENABLED,
    telegram_map_enabled: CFG.TELEGRAM_MAP_ENABLED,
    geoapify_configured: !!CFG.GEOAPIFY_API_KEY,
    gpx_enabled: !!CFG.DRIVE_GPX_FOLDER_ID,
  }, 200);
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const now = new Date();
    const rxUtc = fmtUtc(now);

    if (!e || !e.postData || !e.postData.contents) {
      return jsonOut({ ok: false, error: 'empty_body' }, 400);
    }

    let body;
    try {
      body = JSON.parse(e.postData.contents);
    } catch (_) {
      return jsonOut({ ok: false, error: 'invalid_json' }, 400);
    }

    if (!body.api_key || body.api_key !== CFG.API_KEY) {
      return jsonOut({ ok: false, error: 'unauthorized' }, 401);
    }

    const deviceId = String(body.device_id || '').trim();
    const sessionId = String(body.session_id || '').trim();
    const batchUid = String(body.batch_uid || '').trim();
    if (!deviceId || !sessionId) {
      return jsonOut({ ok: false, error: 'missing_device_or_session' }, 400);
    }
    if (!batchUid) return jsonOut({ ok: false, error: 'missing_batch_uid' }, 400);

    const points = Array.isArray(body.points) ? body.points : [];
    const status = String(body.status || 'open').toLowerCase();
    if (status !== 'open' && status !== 'closed') {
      return jsonOut({ ok: false, error: 'invalid_status' }, 400);
    }

    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const pointsSheet = ensureSheetByHeaders_(ss, CFG.SHEET_POINTS, POINT_HEADERS);
    const sessionsSheet = ensureSheetByHeaders_(ss, CFG.SHEET_SESSIONS, SESSION_HEADERS);
    const batchesSheet = ensureSheetByHeaders_(ss, CFG.SHEET_BATCHES, BATCH_HEADERS);
    const batchKey = deviceId + ':' + sessionId + ':' + batchUid;
    const priorBatch = findBatchRecord_(batchesSheet, batchKey);
    // Older launcher versions could rebuild the pending queue with a different batch size after
    // a timeout. A batch UID is useful as a fast idempotency hint, but it must never prevent the
    // point-by-point reconciliation below from draining that durable queue.
    const priorBatchCountMismatch = !!priorBatch &&
      Number(priorBatch.point_count || 0) !== points.length;
    let duplicateBatch = !!priorBatch && !priorBatchCountMismatch;
    if (!duplicateBatch) {
      const recoveredCount = countBatchPoints_(pointsSheet, deviceId, sessionId, batchUid);
      if (recoveredCount > 0) {
        duplicateBatch = recoveredCount === points.length;
      }
    }

    const incomingUids = new Set();
    const toInsert = [];
    let accepted = 0;
    let skipped = 0;
    let reconciled = 0;
    let previousPointSeq = 0;

    for (const p of points) {
      const pointSeq = Math.trunc(Number(p.point_seq || 0));
      const lat = Number(p.lat);
      const lon = Number(p.lon);
      const utc = String(p.utc || '').trim();
      if (!isFinite(pointSeq) || pointSeq <= previousPointSeq) {
        throw new Error('invalid_point_sequence');
      }
      previousPointSeq = pointSeq;
      if (!isFinite(lat) || lat < -90 || lat > 90) throw new Error('invalid_latitude');
      if (!isFinite(lon) || lon < -180 || lon > 180) throw new Error('invalid_longitude');
      if (!utc || !parseUtc_(utc)) throw new Error('invalid_point_utc');
      const pointUid = deviceId + ':' + sessionId + ':' + pointSeq;
      const suppliedPointUid = String(p.point_uid || pointUid).trim();
      if (suppliedPointUid !== pointUid) {
        throw new Error('invalid_point_uid');
      }
      if (incomingUids.has(pointUid)) {
        throw new Error('duplicate_point_uid_in_payload');
      }
      incomingUids.add(pointUid);
      const pointRow = [
        rxUtc,
        deviceId,
        sessionId,
        batchUid,
        pointSeq,
        pointUid,
        utc,
        lat,
        lon,
        Math.max(0, finiteNumber_(p.speed_kmh, 0)),
        p.alt_m === '' || p.alt_m === null || p.alt_m === undefined ? '' : finiteNumber_(p.alt_m, ''),
        Math.max(0, Math.trunc(finiteNumber_(p.satellites, 0))),
        !!p.fix,
        !!p.fix_3d,
        finiteNumber_(p.rssi_dbm, 0),
        Math.max(0, finiteNumber_(p.accuracy_m, 0)),
        String(p.provider || '').trim(),
        Math.max(0, finiteNumber_(p.gps_speed_kmh, 0)),
        String(p.speed_source || 'GPS').trim(),
      ];
      const existingPoint = findPointRecord_(pointsSheet, pointUid);
      if (existingPoint) {
        // A pending SQLite queue can be regrouped after changing Puntos/lote. In that case the
        // same immutable point legitimately arrives under a different batch_uid. Treat it as an
        // idempotent replay only after checking its identity and coordinates.
        if (!sameTrackingPoint_(existingPoint, deviceId, sessionId, pointSeq, lat, lon)) {
          // The radio's SQLite queue is the durable source of truth. Historical builds could
          // reuse a sequence after a partial/ambiguous ACK, leaving one stale row in Sheets and
          // permanently blocking every later upload. Replace that one authenticated logical
          // point in place, preserving uniqueness and allowing the queue to continue.
          pointsSheet.getRange(existingPoint.__row, 1, 1, POINT_HEADERS.length).setValues([pointRow]);
          reconciled++;
        }
        skipped++;
        continue;
      }
      accepted++;
      toInsert.push(pointRow);
    }

    if (toInsert.length > 0) {
      const startRow = pointsSheet.getLastRow() + 1;
      pointsSheet.getRange(startRow, 1, toInsert.length, toInsert[0].length).setValues(toInsert);
    }
    if (!priorBatch) {
      appendObjectRows_(batchesSheet, BATCH_HEADERS, [{
        batch_key: batchKey,
        rx_utc: rxUtc,
        device_id: deviceId,
        session_id: sessionId,
        batch_uid: batchUid,
        point_count: points.length,
      }]);
    }

    // V4 uses one canonical analysis for Sheets, GPX, Maps and Telegram. Never mix the
    // Android summary with a different subset of points on the server.
    const rawTrack = getSessionTrackPoints(pointsSheet, deviceId, sessionId);
    const analysis = analyzeTrack_(rawTrack);
    const track = analysis.track;
    const distanceKmComputed = analysis.rawPointsCount > 0
      ? analysis.distanceKm
      : finiteNumber_(body.distance_km, 0);
    const maxSpeedKmhComputed = analysis.rawPointsCount > 0
      ? analysis.maxSpeedConfirmedKmh
      : finiteNumber_(body.max_speed_kmh, 0);
    const bodyStartUtc = String(body.start_utc || '').trim();
    const bodyEndUtc = String(body.end_utc || '').trim();
    const trackBounds = getTrackUtcBounds_(track);
    const canonicalStartUtc = trackBounds.startUtc || bodyStartUtc;
    const canonicalEndUtc =
      (status === 'closed' || bodyEndUtc)
        ? (trackBounds.endUtc || bodyEndUtc)
        : bodyEndUtc;

    const sessionData = {
      rx_utc: rxUtc,
      device_id: deviceId,
      session_id: sessionId,
      uploaded_utc: String(body.uploaded_utc || rxUtc),
      start_utc: canonicalStartUtc,
      end_utc: canonicalEndUtc,
      close_reason: String(body.close_reason || ''),
      status: status,
      last_batch_uid: batchUid,
      last_points_count: analysis.validPointsCount,
      max_speed_kmh: maxSpeedKmhComputed,
      distance_km: distanceKmComputed,
      gpx_file_id: '',
      gpx_drive_url: '',
      gpx_download_url: '',
      gmaps_url: '',
      updated_utc: rxUtc,
      raw_points_count: analysis.rawPointsCount,
      valid_points_count: analysis.validPointsCount,
      rejected_points_count: analysis.rejectedPointsCount,
      anomaly_count: analysis.anomalyCount,
      duration_s: analysis.durationSec,
      moving_duration_s: analysis.movingDurationSec,
      stopped_duration_s: analysis.stoppedDurationSec,
      unknown_duration_s: analysis.unknownDurationSec,
      average_speed_kmh: analysis.averageSpeedKmh,
      moving_average_speed_kmh: analysis.movingAverageSpeedKmh,
      max_speed_observed_kmh: analysis.maxSpeedObservedKmh,
      max_speed_confirmed_kmh: analysis.maxSpeedConfirmedKmh,
      stop_count: analysis.stopCount,
      key_points_count: analysis.keyPointsCount,
      track_quality: analysis.quality,
      driver_profile_id: String(body.driver_profile_id || '').trim(),
      driver_profile_name: String(body.driver_profile_name || '').trim(),
    };

    const sessionRow = upsertSessionRowByHeaders(sessionsSheet, sessionData);

    const shouldUpdateLinks =
      CFG.GPX_UPDATE_ON_EVERY_POST || status === 'closed' || !!sessionData.end_utc;

    let gpxInfo = null;
    let gmapsUrl = '';

    if (shouldUpdateLinks) {
      if (track.length >= 2) {
        gpxInfo = createOrUpdateGpxInDrive(deviceId, sessionId, track);
        gmapsUrl = buildGoogleMapsDirectionsUrl(track, {
          maxPoints: CFG.MAPS_MAX_POINTS,
          maxUrlLength: CFG.MAPS_MAX_URL_LENGTH,
        });
      } else if (track.length === 1) {
        gpxInfo = createOrUpdateGpxInDrive(deviceId, sessionId, track);
        gmapsUrl = `https://www.google.com/maps?q=${track[0].lat},${track[0].lon}`;
      }
    }

    if (sessionRow > 0 && (gpxInfo || gmapsUrl || analysis.rawPointsCount > 0)) {
      const patch = {
        close_reason: String(sessionData.close_reason || ''),
        max_speed_kmh: maxSpeedKmhComputed,
        distance_km: distanceKmComputed,
        updated_utc: rxUtc,
        raw_points_count: analysis.rawPointsCount,
        valid_points_count: analysis.validPointsCount,
        rejected_points_count: analysis.rejectedPointsCount,
        anomaly_count: analysis.anomalyCount,
        duration_s: analysis.durationSec,
        moving_duration_s: analysis.movingDurationSec,
        stopped_duration_s: analysis.stoppedDurationSec,
        unknown_duration_s: analysis.unknownDurationSec,
        average_speed_kmh: analysis.averageSpeedKmh,
        moving_average_speed_kmh: analysis.movingAverageSpeedKmh,
        max_speed_observed_kmh: analysis.maxSpeedObservedKmh,
        max_speed_confirmed_kmh: analysis.maxSpeedConfirmedKmh,
        stop_count: analysis.stopCount,
        key_points_count: analysis.keyPointsCount,
        track_quality: analysis.quality,
      };
      if (gpxInfo) {
        patch.gpx_file_id = gpxInfo.fileId;
        patch.gpx_drive_url = gpxInfo.driveUrl;
        patch.gpx_download_url = gpxInfo.downloadUrl;
      }
      if (gmapsUrl) {
        patch.gmaps_url = gmapsUrl;
      }
      updateSessionLinksByHeaders(sessionsSheet, sessionRow, patch);
    }

    const sessionSnapshot = sessionRow > 0
      ? getSessionRowByHeaders_(sessionsSheet, sessionRow)
      : sessionData;

    if (CFG.TELEGRAM_ENABLED) {
      const trackForNotify = track;
      let mapsUrlForNotify = gmapsUrl || '';
      let lastLocationUrlForNotify = '';
      if (!mapsUrlForNotify) {
        if (trackForNotify.length >= 2) {
          mapsUrlForNotify = buildGoogleMapsDirectionsUrl(trackForNotify, {
            maxPoints: CFG.MAPS_MAX_POINTS,
            maxUrlLength: CFG.MAPS_MAX_URL_LENGTH,
          }) || '';
        } else if (trackForNotify.length === 1) {
          mapsUrlForNotify = `https://www.google.com/maps?q=${trackForNotify[0].lat},${trackForNotify[0].lon}`;
        }
      }
      const startLocationUrlForNotify = trackForNotify.length > 0
        ? buildGoogleMapsPointUrl_(trackForNotify[0].lat, trackForNotify[0].lon)
        : '';
      if (trackForNotify.length > 0) {
        const lastPoint = trackForNotify[trackForNotify.length - 1];
        lastLocationUrlForNotify = buildGoogleMapsPointUrl_(lastPoint.lat, lastPoint.lon);
      }

      if (status !== 'closed') {
        notifyTelegramSessionEventIfNeeded_({
          eventType: 'start',
          deviceId,
          sessionId,
          status: 'open',
          startUtc: String(sessionSnapshot.start_utc || sessionData.start_utc || ''),
          endUtc: '',
          updatedUtc: String(sessionSnapshot.updated_utc || rxUtc),
          track: trackForNotify,
          analysis,
          distanceKm: distanceKmComputed,
          pointsCount: analysis.validPointsCount,
          startLocationUrl: startLocationUrlForNotify,
        });
      }
      if (status === 'closed' || !!sessionData.end_utc) {
        const notifyEndUtc =
          String(sessionSnapshot.end_utc || '') ||
          sessionData.end_utc ||
          (trackForNotify.length > 0 ? String(trackForNotify[trackForNotify.length - 1].utc || '') : '') ||
          rxUtc;
        notifyTelegramSessionEventIfNeeded_({
          eventType: 'end',
          deviceId,
          sessionId,
          status: 'closed',
          startUtc: String(sessionSnapshot.start_utc || sessionData.start_utc || ''),
          endUtc: notifyEndUtc,
          updatedUtc: String(sessionSnapshot.updated_utc || rxUtc),
          track: trackForNotify,
          analysis,
          distanceKm: distanceKmComputed,
          pointsCount: analysis.validPointsCount,
          maxSpeedKmh: maxSpeedKmhComputed,
          closeReason: String(sessionData.close_reason || ''),
          stopCount: analysis.stopCount,
          stopDurationSec: analysis.stoppedDurationSec,
          mapsUrl: mapsUrlForNotify,
          gpxUrl: gpxInfo ? gpxInfo.driveUrl : String(sessionSnapshot.gpx_drive_url || ''),
          gpxFileId: gpxInfo ? gpxInfo.fileId : String(sessionSnapshot.gpx_file_id || ''),
          gpxDownloadUrl: gpxInfo ? gpxInfo.downloadUrl : String(sessionSnapshot.gpx_download_url || ''),
          lastLocationUrl: lastLocationUrlForNotify,
        });
      }
    }

    return jsonOut({
      ok: true,
      server_version: TRACKER_SERVER_VERSION,
      device_id: deviceId,
      status: String(sessionSnapshot.status || status),
      ack_batch_uid: batchUid,
      accepted,
      skipped,
      received: points.length,
      session_id: sessionId,
      batch_uid: batchUid,
      inserted_points: accepted,
      reconciled_points: reconciled,
      received_points: points.length,
      duplicate_batch: duplicateBatch,
      batch_record_mismatch_recovered: priorBatchCountMismatch,
      links_updated: shouldUpdateLinks,
      track_quality: analysis.quality,
      valid_track_points: analysis.validPointsCount,
      rejected_track_points: analysis.rejectedPointsCount,
      distance_km: analysis.distanceKm,
      max_speed_confirmed_kmh: analysis.maxSpeedConfirmedKmh,
    }, 200);
  } catch (err) {
    console.error(err && err.stack ? err.stack : err);
    return jsonOut({ ok: false, error: String(err && err.message ? err.message : err) }, 500);
  } finally {
    lock.releaseLock();
  }
}

function closeStaleOpenSessions() {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const pointsSheet = ss.getSheetByName(CFG.SHEET_POINTS);
    const sessionsSheet = ss.getSheetByName(CFG.SHEET_SESSIONS);
    if (!pointsSheet || !sessionsSheet) {
      throw new Error('missing_sheet');
    }

    ensureHeaders_(sessionsSheet, SESSION_HEADERS, getHeaderMap_(sessionsSheet));

    const headerMap = getHeaderMap_(sessionsSheet);
    const lastRow = sessionsSheet.getLastRow();
    if (lastRow < 2) return;

    const lastCol = sessionsSheet.getLastColumn();
    const rows = sessionsSheet.getRange(2, 1, lastRow - 1, lastCol).getValues();
    const now = new Date();
    const nowUtc = fmtUtc(now);
    const staleMs = Math.max(1, Number(CFG.STALE_CLOSE_MINUTES || 20)) * 60000;
    const lookbackMs = Math.max(1, Number(CFG.STALE_SCAN_LOOKBACK_HOURS || 72)) * 3600000;

    for (let i = rows.length - 1; i >= 0; i--) {
      const rowIndex = i + 2;
      const status = String(rows[i][headerMap.status - 1] || '').toLowerCase().trim();
      if (status !== 'open') continue;

      const updatedUtc = String(rows[i][headerMap.updated_utc - 1] || '').trim();
      const updatedDate = parseUtc_(updatedUtc);
      if (!updatedDate) continue;

      const ageMs = now.getTime() - updatedDate.getTime();
      if (ageMs < staleMs || ageMs > lookbackMs) continue;

      const deviceId = String(rows[i][headerMap.device_id - 1] || '').trim();
      const sessionId = String(rows[i][headerMap.session_id - 1] || '').trim();
      if (!deviceId || !sessionId) continue;

      const analysis = analyzeTrack_(getSessionTrackPoints(pointsSheet, deviceId, sessionId));
      const track = analysis.track;
      const trackBounds = getTrackUtcBounds_(track);
      let gpxInfo = null;
      let gmapsUrl = '';
      let endUtc = String(rows[i][headerMap.end_utc - 1] || '').trim();
      if (!endUtc) {
        endUtc = trackBounds.endUtc
          ? trackBounds.endUtc
          : updatedUtc;
      }

      if (track.length >= 2) {
        gpxInfo = createOrUpdateGpxInDrive(deviceId, sessionId, track);
        gmapsUrl = buildGoogleMapsDirectionsUrl(track, {
          maxPoints: CFG.MAPS_MAX_POINTS,
          maxUrlLength: CFG.MAPS_MAX_URL_LENGTH,
        });
      } else if (track.length === 1) {
        gpxInfo = createOrUpdateGpxInDrive(deviceId, sessionId, track);
        gmapsUrl = `https://www.google.com/maps?q=${track[0].lat},${track[0].lon}`;
      }

      const distanceKm = analysis.distanceKm;
      const maxSpeedKmh = analysis.maxSpeedConfirmedKmh;
      updateSessionLinksByHeaders(sessionsSheet, rowIndex, Object.assign(sessionAnalysisPatch_(analysis), {
        status: 'closed',
        start_utc: trackBounds.startUtc || String(rows[i][headerMap.start_utc - 1] || ''),
        end_utc: endUtc,
        close_reason: 'stale_timeout',
        uploaded_utc: nowUtc,
        updated_utc: nowUtc,
        max_speed_kmh: maxSpeedKmh,
        distance_km: distanceKm,
        gpx_file_id: gpxInfo ? gpxInfo.fileId : String(rows[i][headerMap.gpx_file_id - 1] || ''),
        gpx_drive_url: gpxInfo ? gpxInfo.driveUrl : String(rows[i][headerMap.gpx_drive_url - 1] || ''),
        gpx_download_url: gpxInfo ? gpxInfo.downloadUrl : String(rows[i][headerMap.gpx_download_url - 1] || ''),
        gmaps_url: gmapsUrl || String(rows[i][headerMap.gmaps_url - 1] || ''),
      }));

      if (CFG.TELEGRAM_ENABLED) {
        let lastLocationUrl = '';
        if (track.length > 0) {
          const lastPoint = track[track.length - 1];
          lastLocationUrl = buildGoogleMapsPointUrl_(lastPoint.lat, lastPoint.lon);
        }
        notifyTelegramSessionEventIfNeeded_({
          eventType: 'end',
          deviceId,
          sessionId,
          status: 'closed',
          startUtc: String(rows[i][headerMap.start_utc - 1] || ''),
          endUtc: endUtc,
          track,
          analysis,
          distanceKm,
          pointsCount: analysis.validPointsCount,
          maxSpeedKmh,
          closeReason: 'stale_timeout',
          stopCount: analysis.stopCount,
          stopDurationSec: analysis.stoppedDurationSec,
          mapsUrl: gmapsUrl || String(rows[i][headerMap.gmaps_url - 1] || ''),
          gpxUrl: gpxInfo
            ? gpxInfo.driveUrl
            : String(rows[i][headerMap.gpx_drive_url - 1] || ''),
          gpxFileId: gpxInfo
            ? gpxInfo.fileId
            : String(rows[i][headerMap.gpx_file_id - 1] || ''),
          gpxDownloadUrl: gpxInfo
            ? gpxInfo.downloadUrl
            : String(rows[i][headerMap.gpx_download_url - 1] || ''),
          lastLocationUrl,
        });
      }
    }
  } finally {
    lock.releaseLock();
  }
}

function installMaintenanceTrigger() {
  const fn = 'closeStaleOpenSessions';
  const triggers = ScriptApp.getProjectTriggers();
  for (const t of triggers) {
    if (t.getHandlerFunction && t.getHandlerFunction() === fn) {
      return; // ya existe
    }
  }
  ScriptApp.newTrigger(fn).timeBased().everyMinutes(5).create();
}

function recalculateAllSessionsV4() {
  return runSessionRecalculationV4_();
}

/** Continuation handler used by the one-shot time trigger. */
function continueRecalculateAllSessionsV4() {
  return runSessionRecalculationV4_();
}

function runSessionRecalculationV4_() {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const startedMs = Date.now();
    const budgetMs = Math.min(270, Math.max(30, Number(CFG.RECALC_TIME_BUDGET_SEC || 240))) * 1000;
    const maxSessions = Math.max(1, Math.trunc(Number(CFG.RECALC_SESSIONS_PER_RUN || 25)));
    const props = PropertiesService.getScriptProperties();
    const activeSpreadsheet = SpreadsheetApp.getActiveSpreadsheet();
    const storedSpreadsheetId = String(props.getProperty('recalc_v4_spreadsheet_id') || '');
    const ss = activeSpreadsheet || (storedSpreadsheetId
      ? SpreadsheetApp.openById(storedSpreadsheetId)
      : null);
    if (!ss) throw new Error('No se pudo abrir la hoja para continuar el recalculo');
    props.setProperty('recalc_v4_spreadsheet_id', ss.getId());
    const pointsSheet = ss.getSheetByName(CFG.SHEET_POINTS);
    const sessionsSheet = ss.getSheetByName(CFG.SHEET_SESSIONS);
    if (!pointsSheet || !sessionsSheet) {
      throw new Error('missing_sheet');
    }

    ensureHeaders_(sessionsSheet, SESSION_HEADERS, getHeaderMap_(sessionsSheet));
    const map = getHeaderMap_(sessionsSheet);
    const lastRow = sessionsSheet.getLastRow();
    if (lastRow < 2) {
      clearRecalculationStateV4_();
      return 'No hay sesiones que recalcular';
    }

    const nowUtc = fmtUtc(new Date());
    const lastCol = sessionsSheet.getLastColumn();
    const rows = sessionsSheet.getRange(2, 1, lastRow - 1, lastCol).getValues();
    let cursor = Math.max(0, Math.trunc(finiteNumber_(props.getProperty('recalc_v4_cursor'), 0)));
    if (cursor >= rows.length) cursor = 0;

    const selected = [];
    let scan = cursor;
    while (scan < rows.length && selected.length < maxSessions) {
      const deviceId = String(rows[scan][map.device_id - 1] || '').trim();
      const sessionId = String(rows[scan][map.session_id - 1] || '').trim();
      selected.push({ index: scan, deviceId, sessionId });
      scan++;
    }
    const wantedKeys = new Set(selected
      .filter((item) => item.deviceId && item.sessionId)
      .map((item) => sessionTrackKey_(item.deviceId, item.sessionId)));
    const tracksBySession = getSessionTracksForKeys_(pointsSheet, wantedKeys);
    const patches = new Array(rows.length).fill(null);
    let updated = 0;
    let nextCursor = cursor;
    for (const item of selected) {
      if (Date.now() - startedMs >= budgetMs) break;
      const i = item.index;
      const deviceId = item.deviceId;
      const sessionId = item.sessionId;
      nextCursor = i + 1;
      if (!deviceId || !sessionId) continue;

      const rawTrack = tracksBySession.get(sessionTrackKey_(deviceId, sessionId)) || [];
      const analysis = analyzeTrack_(rawTrack);
      const track = analysis.track;
      const trackBounds = getTrackUtcBounds_(track);
      if (!trackBounds.startUtc && !trackBounds.endUtc) continue;

      const gmapsUrl = track.length > 0 ? buildGoogleMapsDirectionsUrl(track) : '';
      patches[i] = Object.assign(sessionAnalysisPatch_(analysis), {
        start_utc: trackBounds.startUtc || String(rows[i][map.start_utc - 1] || ''),
        end_utc: trackBounds.endUtc || String(rows[i][map.end_utc - 1] || ''),
        gmaps_url: gmapsUrl || String(rows[i][map.gmaps_url - 1] || ''),
        updated_utc: nowUtc,
      });
      updated++;
    }
    writeSessionPatchesInBatches_(sessionsSheet, rows, map, patches);
    SpreadsheetApp.flush();

    const previousUpdated = Math.max(0, Math.trunc(finiteNumber_(props.getProperty('recalc_v4_updated'), 0)));
    const totalUpdated = previousUpdated + updated;
    if (nextCursor < rows.length) {
      props.setProperties({
        recalc_v4_cursor: String(nextCursor),
        recalc_v4_updated: String(totalUpdated),
      });
      scheduleRecalculationContinuationV4_();
      return `${updated} sesiones procesadas ahora; progreso ${nextCursor}/${rows.length}. Continuara automaticamente.`;
    }

    clearRecalculationStateV4_();
    return `${totalUpdated} sesiones recalculadas con el analizador V4`;
  } finally {
    lock.releaseLock();
  }
}

function cancelRecalculateAllSessionsV4() {
  clearRecalculationStateV4_();
  return 'Recalculo V4 cancelado';
}

function showRecalculationStatusV4() {
  const props = PropertiesService.getScriptProperties();
  const cursor = props.getProperty('recalc_v4_cursor');
  const updated = Math.max(0, Math.trunc(finiteNumber_(props.getProperty('recalc_v4_updated'), 0)));
  const message = cursor === null
    ? 'No hay ningun recalculo V4 pendiente.'
    : `Recalculo V4 activo: ${cursor} filas recorridas y ${updated} sesiones actualizadas.`;
  SpreadsheetApp.getUi().alert(message);
  return message;
}

function scheduleRecalculationContinuationV4_() {
  const handler = 'continueRecalculateAllSessionsV4';
  for (const trigger of ScriptApp.getProjectTriggers()) {
    if (trigger.getHandlerFunction && trigger.getHandlerFunction() === handler) {
      ScriptApp.deleteTrigger(trigger);
    }
  }
  ScriptApp.newTrigger(handler).timeBased().after(60 * 1000).create();
}

function clearRecalculationStateV4_() {
  PropertiesService.getScriptProperties().deleteProperty('recalc_v4_cursor');
  PropertiesService.getScriptProperties().deleteProperty('recalc_v4_updated');
  PropertiesService.getScriptProperties().deleteProperty('recalc_v4_spreadsheet_id');
  const handler = 'continueRecalculateAllSessionsV4';
  for (const trigger of ScriptApp.getProjectTriggers()) {
    if (trigger.getHandlerFunction && trigger.getHandlerFunction() === handler) {
      ScriptApp.deleteTrigger(trigger);
    }
  }
}

// Alias temporal para instalaciones que todavía tengan el nombre antiguo en un botón.
function repairSessionTimingFromPoints() {
  return recalculateAllSessionsV4();
}

/** Explicit menu action. It is the only path that intentionally resends a closed summary. */
function sendLatestSessionSummaryV4() {
  if (!telegramReady_()) throw new Error('Configura Telegram antes de enviar el resumen');
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const pointsSheet = ss.getSheetByName(CFG.SHEET_POINTS);
  const sessionsSheet = ss.getSheetByName(CFG.SHEET_SESSIONS);
  if (!pointsSheet || !sessionsSheet) throw new Error('missing_sheet');
  ensureHeaders_(sessionsSheet, SESSION_HEADERS, getHeaderMap_(sessionsSheet));
  const map = getHeaderMap_(sessionsSheet);
  const lastRow = sessionsSheet.getLastRow();
  if (lastRow < 2) throw new Error('no_sessions');
  const rows = sessionsSheet.getRange(2, 1, lastRow - 1, sessionsSheet.getLastColumn()).getValues();
  let row = null;
  for (let i = rows.length - 1; i >= 0; i--) {
    if (String(rows[i][map.status - 1] || '').toLowerCase().trim() === 'closed') {
      row = rows[i];
      break;
    }
  }
  if (!row) throw new Error('no_closed_sessions');

  const deviceId = String(row[map.device_id - 1] || '').trim();
  const sessionId = String(row[map.session_id - 1] || '').trim();
  const analysis = analyzeTrack_(getSessionTrackPoints(pointsSheet, deviceId, sessionId));
  if (analysis.validPointsCount === 0) throw new Error('session_without_valid_points');
  const mapsUrl = buildGoogleMapsDirectionsUrl(analysis.track);
  const lastPoint = analysis.track[analysis.track.length - 1];
  const event = {
    eventType: 'end',
    deviceId,
    sessionId,
    startUtc: String(row[map.start_utc - 1] || ''),
    endUtc: String(row[map.end_utc - 1] || ''),
    closeReason: String(row[map.close_reason - 1] || ''),
    track: analysis.track,
    analysis,
    mapsUrl,
    gpxUrl: String(row[map.gpx_drive_url - 1] || ''),
    gpxFileId: String(row[map.gpx_file_id - 1] || ''),
    gpxDownloadUrl: String(row[map.gpx_download_url - 1] || ''),
    lastLocationUrl: buildGoogleMapsPointUrl_(lastPoint.lat, lastPoint.lon),
  };
  if (!sendTelegramSessionReport_(event)) throw new Error('telegram_delivery_failed');
  const props = PropertiesService.getScriptProperties();
  const notificationKey = `${TELEGRAM_NOTIFICATION_PREFIX}end:${deviceId}:${sessionId}`;
  pruneTelegramNotificationProperties_(props, notificationKey);
  props.setProperty(notificationKey, String(Date.now()));
  return `Resumen V4 enviado para ${sessionId}`;
}

function upsertSessionRowByHeaders(sessionsSheet, data) {
  const headerMap = getHeaderMap_(sessionsSheet);
  ensureHeaders_(sessionsSheet, SESSION_HEADERS, headerMap);

  const lastCol = sessionsSheet.getLastColumn();
  const lastRow = sessionsSheet.getLastRow();
  const map = getHeaderMap_(sessionsSheet);

  let targetRow = -1;
  if (lastRow >= 2) {
    const all = sessionsSheet.getRange(2, 1, lastRow - 1, lastCol).getValues();
    for (let i = all.length - 1; i >= 0; i--) {
      const did = String(all[i][map.device_id - 1] || '');
      const sid = String(all[i][map.session_id - 1] || '');
      if (did === data.device_id && sid === data.session_id) {
        targetRow = i + 2;
        break;
      }
    }
  }

  const row = new Array(lastCol).fill('');
  Object.keys(map).forEach((k) => {
    if (Object.prototype.hasOwnProperty.call(data, k)) {
      row[map[k] - 1] = data[k];
    }
  });

  if (targetRow > 0) {
    const current = sessionsSheet.getRange(targetRow, 1, 1, lastCol).getValues()[0];
    const currentStatus = map.status
      ? String(current[map.status - 1] || '').toLowerCase().trim()
      : '';
    if (currentStatus === 'closed' && String(data.status || '').toLowerCase() !== 'closed') {
      data.status = 'closed';
      if (map.end_utc) data.end_utc = current[map.end_utc - 1];
      if (map.close_reason) data.close_reason = current[map.close_reason - 1];
    }
    Object.keys(map).forEach((k) => {
      if (Object.prototype.hasOwnProperty.call(data, k)) {
        if ((k === 'start_utc' || k === 'end_utc') && !String(data[k] || '').trim()) return;
        if (
          (k === 'gpx_file_id' || k === 'gpx_drive_url' ||
           k === 'gpx_download_url' || k === 'gmaps_url') &&
          !String(data[k] || '').trim()
        ) return;
        current[map[k] - 1] = data[k];
      }
    });
    sessionsSheet.getRange(targetRow, 1, 1, lastCol).setValues([current]);
    return targetRow;
  }

  sessionsSheet.appendRow(row);
  return sessionsSheet.getLastRow();
}

function updateSessionLinksByHeaders(sessionsSheet, row, patch) {
  const map = getHeaderMap_(sessionsSheet);
  const lastCol = sessionsSheet.getLastColumn();
  const current = sessionsSheet.getRange(row, 1, 1, lastCol).getValues()[0];
  Object.keys(patch).forEach((k) => {
    if (map[k]) current[map[k] - 1] = patch[k];
  });
  sessionsSheet.getRange(row, 1, 1, lastCol).setValues([current]);
}

/**
 * Writes a complete recalculation in a few contiguous column blocks. This avoids
 * one read + one write per session and leaves unrelated user columns untouched.
 */
function writeSessionPatchesInBatches_(sessionsSheet, rows, map, patches) {
  const fields = new Set();
  let firstPatchedRow = -1;
  let lastPatchedRow = -1;
  for (let index = 0; index < patches.length; index++) {
    const patch = patches[index];
    if (!patch) continue;
    if (firstPatchedRow < 0) firstPatchedRow = index;
    lastPatchedRow = index;
    Object.keys(patch).forEach((field) => {
      if (map[field]) fields.add(field);
    });
  }
  if (fields.size === 0) return;

  const columns = Array.from(fields)
    .map((field) => ({ field, column: map[field] }))
    .sort((a, b) => a.column - b.column);
  const blocks = [];
  for (const item of columns) {
    const block = blocks[blocks.length - 1];
    if (!block || item.column !== block[block.length - 1].column + 1) {
      blocks.push([item]);
    } else {
      block.push(item);
    }
  }

  for (const block of blocks) {
    const values = rows.slice(firstPatchedRow, lastPatchedRow + 1).map((row, offset) => block.map((item) => {
      const index = firstPatchedRow + offset;
      const patch = patches[index];
      return patch && Object.prototype.hasOwnProperty.call(patch, item.field)
        ? patch[item.field]
        : row[item.column - 1];
    }));
    sessionsSheet
      .getRange(firstPatchedRow + 2, block[0].column, values.length, block.length)
      .setValues(values);
  }
}

function getSessionRowByHeaders_(sessionsSheet, row) {
  const map = getHeaderMap_(sessionsSheet);
  const lastCol = sessionsSheet.getLastColumn();
  const current = sessionsSheet.getRange(row, 1, 1, lastCol).getValues()[0];
  const out = {};
  Object.keys(map).forEach((k) => {
    out[k] = current[map[k] - 1];
  });
  return out;
}

function getSessionTrackPoints(pointsSheet, deviceId, sessionId) {
  // Queue replay calls this once per uploaded batch. Retaining every historical session here
  // made each later request more expensive and eventually caused Android HTTP timeouts.
  const key = sessionTrackKey_(deviceId, sessionId);
  return getSessionTracksForKeys_(pointsSheet, new Set([key])).get(key) || [];
}

function getAllSessionTracks_(pointsSheet) {
  return getSessionTracksForKeys_(pointsSheet, null);
}

/** Reads the points sheet once, retaining only the requested sessions when supplied. */
function getSessionTracksForKeys_(pointsSheet, wantedKeys) {
  const grouped = new Map();
  if (wantedKeys && wantedKeys.size === 0) return grouped;
  const lastRow = pointsSheet.getLastRow();
  if (lastRow < 2) return grouped;

  const map = getHeaderMap_(pointsSheet);
  const vals = pointsSheet.getRange(2, 1, lastRow - 1, pointsSheet.getLastColumn()).getValues();
  let sourceIndex = 0;
  for (const r of vals) {
    const value = (name, fallback) => map[name] ? r[map[name] - 1] : fallback;
    const deviceId = String(value('device_id', '') || '').trim();
    const sessionId = String(value('session_id', '') || '').trim();
    if (!deviceId || !sessionId) continue;
    const key = sessionTrackKey_(deviceId, sessionId);
    if (wantedKeys && !wantedKeys.has(key)) continue;
    const lat = Number(value('lat', Number.NaN));
    const lon = Number(value('lon', Number.NaN));
    if (!isFinite(lat) || !isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
    const utc = String(value('utc', '') || '').trim();
    const parsedUtc = parseUtc_(utc);
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push({
      point_seq: Math.trunc(Number(value('point_seq', sourceIndex + 1))),
      point_uid: String(value('point_uid', '') || '').trim(),
      utc,
      lat,
      lon,
      speed_kmh: finiteNumber_(value('speed_kmh', 0), 0),
      gps_speed_kmh: finiteNumber_(value('gps_speed_kmh', 0), 0),
      speed_source: String(value('speed_source', '') || '').trim(),
      accuracy_m: finiteNumber_(value('accuracy_m', 0), 0),
      provider: String(value('provider', '') || '').trim(),
      satellites: Math.max(0, Math.trunc(finiteNumber_(value('satellites', 0), 0))),
      ele: value('alt_m', '') === '' ? null : finiteNumber_(value('alt_m', null), null),
      _ts: parsedUtc ? parsedUtc.getTime() : Number.NaN,
      _src: sourceIndex++,
    });
  }

  for (const track of grouped.values()) {
    track.sort((a, b) => {
      const aSeq = Number(a.point_seq);
      const bSeq = Number(b.point_seq);
      if (isFinite(aSeq) && isFinite(bSeq) && aSeq !== bSeq) return aSeq - bSeq;
      const aHas = isFinite(a._ts);
      const bHas = isFinite(b._ts);
      if (aHas && bHas) {
        if (a._ts !== b._ts) return a._ts - b._ts;
        return a._src - b._src;
      }
      if (aHas && !bHas) return -1;
      if (!aHas && bHas) return 1;
      return a._src - b._src;
    });
    for (const p of track) {
      delete p._ts;
      delete p._src;
    }
  }
  return grouped;
}

function sessionTrackKey_(deviceId, sessionId) {
  return `${String(deviceId || '').trim()}\u0000${String(sessionId || '').trim()}`;
}

/**
 * Builds the only canonical view of a session. Every downstream consumer uses this
 * result, so Telegram, Sheets, GPX and Maps cannot disagree with one another.
 */
function analyzeTrack_(rawTrack) {
  const raw = Array.isArray(rawTrack) ? rawTrack.slice() : [];
  raw.sort((a, b) => {
    const aSeq = Math.trunc(finiteNumber_(a && a.point_seq, 0));
    const bSeq = Math.trunc(finiteNumber_(b && b.point_seq, 0));
    if (aSeq > 0 && bSeq > 0 && aSeq !== bSeq) return aSeq - bSeq;
    const aDate = parseUtc_(a && a.utc);
    const bDate = parseUtc_(b && b.utc);
    if (aDate && bDate) return aDate.getTime() - bDate.getTime();
    return 0;
  });
  const maxAccuracyM = Math.max(5, Number(CFG.TRACK_MAX_ACCURACY_M || 60));
  const maxSegmentSpeedKmh = Math.max(30, Number(CFG.TRACK_MAX_SEGMENT_SPEED_KMH || 180));
  const maxReportedSpeedKmh = Math.max(maxSegmentSpeedKmh, Number(CFG.TRACK_MAX_REPORTED_SPEED_KMH || 220));
  const movingMinSpeedKmh = Math.max(1, Number(CFG.TRACK_MOVING_MIN_SPEED_KMH || 3));
  const maxNormalGapSec = Math.max(10, Number(CFG.TRACK_MAX_NORMAL_GAP_SEC || 120));

  const valid = [];
  const segments = [];
  const seenSeq = new Set();
  const seenIdentity = new Set();
  let rejected = 0;
  let anomalies = 0;
  let gaps = 0;
  let distanceM = 0;
  let movingDistanceM = 0;
  let movingDurationSec = 0;
  let stoppedDurationSec = 0;
  let unknownDurationSec = 0;

  for (const candidate of raw) {
    const p = normalizeTrackPoint_(candidate);
    if (!p) {
      rejected++;
      anomalies++;
      continue;
    }
    const identity = p.point_uid || `${p.utc}:${p.lat.toFixed(7)}:${p.lon.toFixed(7)}`;
    if (seenIdentity.has(identity)) {
      rejected++;
      continue;
    }
    seenIdentity.add(identity);
    if (p.point_seq > 0 && seenSeq.has(p.point_seq)) {
      rejected++;
      anomalies++;
      continue;
    }
    if (p.point_seq > 0) seenSeq.add(p.point_seq);
    if (p.accuracy_m > maxAccuracyM) {
      rejected++;
      continue;
    }
    if (valid.length === 0) {
      valid.push(p);
      continue;
    }

    const previous = valid[valid.length - 1];
    const dtSec = (p._ts - previous._ts) / 1000;
    if (!isFinite(dtSec) || dtSec <= 0) {
      rejected++;
      anomalies++;
      continue;
    }
    const rawDistanceM = latLonDistanceM_(previous, p);
    const impliedSpeedKmh = rawDistanceM / dtSec * 3.6;
    const accuracyAllowanceM = Math.max(20, previous.accuracy_m + p.accuracy_m);
    if (impliedSpeedKmh > maxSegmentSpeedKmh && rawDistanceM > accuracyAllowanceM) {
      rejected++;
      anomalies++;
      continue;
    }

    const gap = dtSec > maxNormalGapSec;
    if (gap) gaps++;
    const deadbandM = Math.min(12, Math.max(2, (previous.accuracy_m + p.accuracy_m) * 0.15));
    const segmentDistanceM = rawDistanceM <= deadbandM ? 0 : rawDistanceM;
    const trustedReportedKmh = trustedSpeedKmh_(p, maxReportedSpeedKmh);
    const moving = segmentDistanceM > 0 &&
      (impliedSpeedKmh >= movingMinSpeedKmh || trustedReportedKmh >= movingMinSpeedKmh);

    distanceM += segmentDistanceM;
    if (gap) {
      unknownDurationSec += dtSec;
    } else if (moving) {
      movingDurationSec += dtSec;
      movingDistanceM += segmentDistanceM;
    } else {
      stoppedDurationSec += dtSec;
    }
    segments.push({
      from: previous,
      to: p,
      durationSec: dtSec,
      distanceM: segmentDistanceM,
      impliedSpeedKmh,
      moving,
      gap,
    });
    valid.push(p);
  }

  const durationSec = valid.length >= 2
    ? Math.max(0, Math.round((valid[valid.length - 1]._ts - valid[0]._ts) / 1000))
    : 0;
  const speedSummary = summarizeSpeeds_(valid, segments, maxReportedSpeedKmh);
  const publicTrack = valid.map(stripInternalTrackPoint_);
  const stops = extractLongStops_(publicTrack);
  const keyPoints = selectTrackAnchors_(publicTrack, CFG.MAPS_MAX_POINTS);
  const quality = trackQuality_(raw.length, valid.length, anomalies, gaps);

  return {
    track: publicTrack,
    segments,
    stops,
    rawPointsCount: raw.length,
    validPointsCount: valid.length,
    rejectedPointsCount: rejected,
    anomalyCount: anomalies,
    gapCount: gaps,
    durationSec,
    movingDurationSec: Math.round(movingDurationSec),
    stoppedDurationSec: Math.round(stoppedDurationSec),
    unknownDurationSec: Math.round(unknownDurationSec),
    distanceKm: roundNumber_(distanceM / 1000, 3),
    movingDistanceKm: roundNumber_(movingDistanceM / 1000, 3),
    averageSpeedKmh: durationSec > 0 ? roundNumber_(distanceM / 1000 / (durationSec / 3600), 1) : 0,
    movingAverageSpeedKmh: movingDurationSec > 0
      ? roundNumber_(movingDistanceM / 1000 / (movingDurationSec / 3600), 1)
      : 0,
    maxSpeedObservedKmh: speedSummary.observed,
    maxSpeedConfirmedKmh: speedSummary.confirmed,
    stopCount: stops.length,
    keyPointsCount: keyPoints.length,
    quality,
  };
}

function normalizeTrackPoint_(candidate) {
  if (!candidate) return null;
  const lat = Number(candidate.lat);
  const lon = Number(candidate.lon);
  const utc = String(candidate.utc || '').trim();
  const parsed = parseUtc_(utc);
  if (!isFinite(lat) || lat < -90 || lat > 90 || !isFinite(lon) || lon < -180 || lon > 180 || !parsed) {
    return null;
  }
  return {
    point_seq: Math.trunc(finiteNumber_(candidate.point_seq, 0)),
    point_uid: String(candidate.point_uid || ''),
    utc,
    lat,
    lon,
    speed_kmh: Math.max(0, finiteNumber_(candidate.speed_kmh, 0)),
    gps_speed_kmh: Math.max(0, finiteNumber_(candidate.gps_speed_kmh, 0)),
    speed_source: String(candidate.speed_source || '').trim(),
    accuracy_m: Math.max(0, finiteNumber_(candidate.accuracy_m, 0)),
    provider: String(candidate.provider || '').trim(),
    satellites: Math.max(0, Math.trunc(finiteNumber_(candidate.satellites, 0))),
    ele: candidate.ele === null || candidate.ele === '' ? null : finiteNumber_(candidate.ele, null),
    _ts: parsed.getTime(),
  };
}

function stripInternalTrackPoint_(p) {
  return {
    point_seq: p.point_seq,
    point_uid: p.point_uid,
    utc: p.utc,
    lat: p.lat,
    lon: p.lon,
    speed_kmh: p.speed_kmh,
    gps_speed_kmh: p.gps_speed_kmh,
    speed_source: p.speed_source,
    accuracy_m: p.accuracy_m,
    provider: p.provider,
    satellites: p.satellites,
    ele: p.ele,
  };
}

function trustedSpeedKmh_(p, maxReportedSpeedKmh) {
  const reported = finiteNumber_(p && p.speed_kmh, 0);
  return reported >= 0 && reported <= maxReportedSpeedKmh ? reported : 0;
}

function summarizeSpeeds_(track, segments, maxReportedSpeedKmh) {
  const speeds = track.map((p) => trustedSpeedKmh_(p, maxReportedSpeedKmh));
  const observed = speeds.reduce((max, value) => Math.max(max, value), 0);
  let confirmed = 0;
  for (let i = 0; i < track.length; i++) {
    const speed = speeds[i];
    if (speed <= confirmed) continue;
    const source = String(track[i].speed_source || '').toUpperCase();
    const trustedSource = source.includes('OBD') || source.includes('CAN') || source.includes('QF');
    const previousCompatible = i > 0 && speeds[i - 1] >= speed * 0.80;
    const nextCompatible = i + 1 < speeds.length && speeds[i + 1] >= speed * 0.80;
    const spatialCompatible = (i > 0 && segments[i - 1] && segments[i - 1].impliedSpeedKmh >= speed * 0.60) ||
      (segments[i] && segments[i].impliedSpeedKmh >= speed * 0.60);
    if (trustedSource || previousCompatible || nextCompatible || spatialCompatible) confirmed = speed;
  }
  if (confirmed === 0 && speeds.length > 0) {
    const sorted = speeds.filter((v) => v > 0).sort((a, b) => a - b);
    if (sorted.length > 0) confirmed = sorted[Math.floor((sorted.length - 1) * 0.95)];
  }
  return {
    observed: roundNumber_(observed, 1),
    confirmed: roundNumber_(Math.min(observed, confirmed), 1),
  };
}

function trackQuality_(rawCount, validCount, anomalies, gaps) {
  if (rawCount === 0 || validCount < 2) return 'INSUFICIENTE';
  const rejectedRatio = Math.max(0, rawCount - validCount) / rawCount;
  let score = Math.max(0, Math.round(100 - rejectedRatio * 100 - anomalies * 3 - gaps * 5));
  if (anomalies > 0) score = Math.min(score, 94);
  if (gaps > 0) score = Math.min(score, 84);
  if (score >= 95) return `EXCELENTE (${score}/100)`;
  if (score >= 85) return `BUENA (${score}/100)`;
  if (score >= 70) return `REVISAR (${score}/100)`;
  return `DEFICIENTE (${score}/100)`;
}

function sessionAnalysisPatch_(analysis) {
  return {
    last_points_count: analysis.validPointsCount,
    max_speed_kmh: analysis.maxSpeedConfirmedKmh,
    distance_km: analysis.distanceKm,
    raw_points_count: analysis.rawPointsCount,
    valid_points_count: analysis.validPointsCount,
    rejected_points_count: analysis.rejectedPointsCount,
    anomaly_count: analysis.anomalyCount,
    duration_s: analysis.durationSec,
    moving_duration_s: analysis.movingDurationSec,
    stopped_duration_s: analysis.stoppedDurationSec,
    unknown_duration_s: analysis.unknownDurationSec,
    average_speed_kmh: analysis.averageSpeedKmh,
    moving_average_speed_kmh: analysis.movingAverageSpeedKmh,
    max_speed_observed_kmh: analysis.maxSpeedObservedKmh,
    max_speed_confirmed_kmh: analysis.maxSpeedConfirmedKmh,
    stop_count: analysis.stopCount,
    key_points_count: analysis.keyPointsCount,
    track_quality: analysis.quality,
  };
}

function getTrackUtcBounds_(track) {
  const clean = Array.isArray(track) ? track : [];
  let startUtc = '';
  let endUtc = '';
  for (let i = 0; i < clean.length; i++) {
    const utc = String(clean[i] && clean[i].utc ? clean[i].utc : '').trim();
    if (!utc || !parseUtc_(utc)) continue;
    if (!startUtc) startUtc = utc;
    endUtc = utc;
  }
  return { startUtc, endUtc };
}

function createOrUpdateGpxInDrive(deviceId, sessionId, track) {
  if (!CFG.DRIVE_GPX_FOLDER_ID || CFG.DRIVE_GPX_FOLDER_ID.indexOf('CAMBIA') === 0) {
    Logger.log('GPX omitido: DRIVE_GPX_FOLDER_ID no configurado');
    return null;
  }

  try {
    const folder = DriveApp.getFolderById(CFG.DRIVE_GPX_FOLDER_ID);
    const fileName = `${deviceId}_${sessionId}.gpx`;
    const gpxText = buildGpx(track, `${deviceId} ${sessionId}`);

    let file = null;
    const it = folder.getFilesByName(fileName);
    if (it.hasNext()) {
      file = it.next();
      file.setContent(gpxText);
    } else {
      file = folder.createFile(fileName, gpxText, MimeType.PLAIN_TEXT);
    }

    const fileId = file.getId();
    return {
      fileId,
      driveUrl: `https://drive.google.com/file/d/${fileId}/view`,
      downloadUrl: `https://drive.google.com/uc?export=download&id=${fileId}`,
    };
  } catch (err) {
    Logger.log(`GPX omitido por error de Drive (device=${deviceId} session=${sessionId}): ${err}`);
    return null;
  }
}

function buildGpx(track, name) {
  const esc = xmlEscape_;
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<gpx version="1.1" creator="Astra Launcher Tracker V4" xmlns="http://www.topografix.com/GPX/1/1">',
  ];
  lines.push(`  <metadata><name>${esc(name)}</name></metadata>`);
  lines.push('  <trk>');
  lines.push(`    <name>${esc(name)}</name>`);
  lines.push('    <trkseg>');

  for (const p of track) {
    lines.push(`      <trkpt lat="${p.lat.toFixed(7)}" lon="${p.lon.toFixed(7)}">`);
    if (p.ele !== null && isFinite(p.ele)) {
      lines.push(`        <ele>${Number(p.ele).toFixed(2)}</ele>`);
    }
    if (p.utc) {
      lines.push(`        <time>${esc(p.utc)}</time>`);
    }
    lines.push('      </trkpt>');
  }

  lines.push('    </trkseg>');
  lines.push('  </trk>');
  lines.push('</gpx>');
  return lines.join('\n');
}

function buildGoogleMapsDirectionsUrl(track, opts) {
  if (!track || track.length === 0) return '';
  const options = opts || {};
  const maxPoints = Math.min(25, Math.max(2, Number(options.maxPoints || CFG.MAPS_MAX_POINTS || 20)));
  const maxUrlLength = Number(options.maxUrlLength || CFG.MAPS_MAX_URL_LENGTH || 1800);

  const anchors = selectTrackAnchors_(track, maxPoints);
  const points = anchors.slice();
  if (points.length === 0) return '';

  const uniquePoints = [];
  let prev = '';
  for (const p of points) {
    const coord = `${Number(p.lat).toFixed(6)},${Number(p.lon).toFixed(6)}`;
    if (coord !== prev) {
      uniquePoints.push(coord);
      prev = coord;
    }
  }
  if (uniquePoints.length === 0) return '';
  if (uniquePoints.length === 1) return `https://www.google.com/maps?q=${uniquePoints[0]}`;

  while (uniquePoints.length > 2) {
    const url = `https://www.google.com/maps/dir/${uniquePoints.join('/')}`;
    if (url.length <= maxUrlLength && uniquePoints.length <= maxPoints) return url;
    uniquePoints.splice(uniquePoints.length - 2, 1); // elimina penultimo, conserva inicio y fin
  }
  return `https://www.google.com/maps/dir/${uniquePoints.join('/')}`;
}

function buildGoogleMapsPointUrl_(lat, lon) {
  const safeLat = Number(lat);
  const safeLon = Number(lon);
  if (!isFinite(safeLat) || !isFinite(safeLon)) return '';
  if (safeLat < -90 || safeLat > 90 || safeLon < -180 || safeLon > 180) return '';
  return `https://www.google.com/maps?q=${safeLat.toFixed(6)},${safeLon.toFixed(6)}`;
}

function selectTrackAnchors_(track, maxPoints) {
  const clean = (track || []).filter((p) => {
    const lat = Number(p.lat);
    const lon = Number(p.lon);
    return isFinite(lat) && isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
  });
  if (clean.length === 0) return [];
  if (clean.length === 1) return [clean[0]];

  const limit = Math.min(25, Math.max(2, Number(maxPoints || 20)));
  const stops = extractLongStops_(clean);

  const anchors = [0, clean.length - 1];
  for (const s of stops) {
    if (isFinite(s.midIndex) && s.midIndex > 0 && s.midIndex < clean.length - 1) {
      anchors.push(s.midIndex);
    }
  }
  // Keep the recorded shape dense enough that Google cannot invent a different roundabout exit
  // or snap a sparse four-point summary through streets that were never visited.
  for (const idx of extractShapeAnchors_(clean, limit)) anchors.push(idx);
  for (const idx of simplifyTrackIndexes_(clean, 12)) {
    anchors.push(idx);
  }
  for (const idx of extractDistanceAnchors_(clean, Math.max(1, Number(CFG.MAPS_KEY_POINT_INTERVAL_KM || 10)) * 1000)) {
    anchors.push(idx);
  }
  for (const idx of extractTurnAnchors_(clean)) anchors.push(idx);

  const uniqSorted = Array.from(new Set(anchors)).sort((a, b) => a - b);
  reduceAnchorsByImportance_(uniqSorted, clean, limit, true);
  return uniqSorted.map((idx) => clean[idx]);
}

function extractDistanceAnchors_(track, intervalM) {
  if (!track || track.length < 3 || intervalM <= 0) return [];
  const out = [];
  let accumulatedM = 0;
  let nextAtM = intervalM;
  for (let i = 1; i < track.length; i++) {
    accumulatedM += latLonDistanceM_(track[i - 1], track[i]);
    if (accumulatedM >= nextAtM && i < track.length - 1) {
      out.push(i);
      while (nextAtM <= accumulatedM) nextAtM += intervalM;
    }
  }
  return out;
}

function extractShapeAnchors_(track, maxPoints) {
  if (!track || track.length < 3) return [];
  let totalM = 0;
  for (let i = 1; i < track.length; i++) totalM += latLonDistanceM_(track[i - 1], track[i]);
  if (!isFinite(totalM) || totalM <= 0) return [];
  const availableSegments = Math.max(1, Math.min(track.length - 1, Number(maxPoints || 20) - 1));
  // About 60 m in urban driving, relaxing only when the URL point budget requires it.
  const intervalM = Math.max(60, totalM / availableSegments);
  return extractDistanceAnchors_(track, intervalM);
}

/** Ramer-Douglas-Peucker over local equirectangular distances. */
function simplifyTrackIndexes_(track, toleranceM) {
  if (!track || track.length <= 2) return track && track.length === 2 ? [0, 1] : [0];
  const keep = new Set([0, track.length - 1]);
  const stack = [[0, track.length - 1]];
  while (stack.length > 0) {
    const range = stack.pop();
    const start = range[0];
    const end = range[1];
    let bestIndex = -1;
    let bestDistance = -1;
    for (let i = start + 1; i < end; i++) {
      const distance = pointToSegmentDistanceM_(track[i], track[start], track[end]);
      if (distance > bestDistance) {
        bestDistance = distance;
        bestIndex = i;
      }
    }
    if (bestIndex > start && bestIndex < end && bestDistance >= toleranceM) {
      keep.add(bestIndex);
      stack.push([start, bestIndex]);
      stack.push([bestIndex, end]);
    }
  }
  return Array.from(keep).sort((a, b) => a - b);
}

function pointToSegmentDistanceM_(point, start, end) {
  const referenceLat = Number(point.lat) * Math.PI / 180;
  const scaleX = Math.cos(referenceLat) * 111320;
  const scaleY = 110540;
  const px = (Number(point.lon) - Number(start.lon)) * scaleX;
  const py = (Number(point.lat) - Number(start.lat)) * scaleY;
  const ex = (Number(end.lon) - Number(start.lon)) * scaleX;
  const ey = (Number(end.lat) - Number(start.lat)) * scaleY;
  const denominator = ex * ex + ey * ey;
  if (denominator <= 0) return Math.sqrt(px * px + py * py);
  const t = Math.max(0, Math.min(1, (px * ex + py * ey) / denominator));
  const dx = px - t * ex;
  const dy = py - t * ey;
  return Math.sqrt(dx * dx + dy * dy);
}

function extractTurnAnchors_(track) {
  const clean = track || [];
  if (clean.length < 3) return [];
  const minTurnDeg = Math.max(5, Number(CFG.MAPS_TURN_MIN_DEG || 30));
  const minSegM = Math.max(5, Number(CFG.MAPS_TURN_MIN_SEGMENT_M || 60));
  const out = [];
  for (let i = 1; i < clean.length - 1; i++) {
    const prev = clean[i - 1];
    const cur = clean[i];
    const next = clean[i + 1];
    const inDist = latLonDistanceM_(prev, cur);
    const outDist = latLonDistanceM_(cur, next);
    if (!isFinite(inDist) || !isFinite(outDist) || inDist < minSegM || outDist < minSegM) continue;
    const turn = headingDeltaDeg_(bearingDeg_(prev, cur), bearingDeg_(cur, next));
    if (isFinite(turn) && turn >= minTurnDeg) {
      out.push(i);
    }
  }
  return out;
}

function reduceAnchorsByImportance_(anchorIdx, points, maxPoints, keepEndpoints) {
  if (anchorIdx.length <= maxPoints || maxPoints < 2) return;
  const first = anchorIdx[0];
  const last = anchorIdx[anchorIdx.length - 1];
  const startPos = keepEndpoints ? 1 : 0;
  const endPos = keepEndpoints ? anchorIdx.length - 1 : anchorIdx.length;
  const ranked = [];
  for (let pos = startPos; pos < endPos; pos++) {
    const prev = points[anchorIdx[Math.max(0, pos - 1)]];
    const cur = points[anchorIdx[pos]];
    const next = points[anchorIdx[Math.min(anchorIdx.length - 1, pos + 1)]];
    if (!prev || !cur || !next) continue;
    const inDist = latLonDistanceM_(prev, cur);
    const outDist = latLonDistanceM_(cur, next);
    const turn = headingDeltaDeg_(bearingDeg_(prev, cur), bearingDeg_(cur, next));
    ranked.push({ index: anchorIdx[pos], score: Math.min(inDist, outDist) + (turn * 4.0) });
  }
  const reserved = keepEndpoints ? 2 : 0;
  const chosen = ranked
    .sort((a, b) => b.score - a.score)
    .slice(0, Math.max(0, maxPoints - reserved))
    .map((item) => item.index);
  if (keepEndpoints) chosen.push(first, last);
  chosen.sort((a, b) => a - b);
  anchorIdx.splice(0, anchorIdx.length, ...chosen);
}

function extractLongStops_(track) {
  const clean = track || [];
  if (clean.length < 2) return [];
  const minStopMs = Math.max(1, Number(CFG.MAPS_STOP_MINUTES || 5)) * 60000;
  const maxStopSpeed = Math.max(0, Number(CFG.MAPS_STOP_MAX_SPEED_KMH || 10));
  const radiusM = Math.max(1, Number(CFG.MAPS_STOP_RADIUS_M || 40));
  // CFG reads Script Properties; resolve it once, never once per GPS point.
  const maxNormalGapSec = Math.max(10, Number(CFG.TRACK_MAX_NORMAL_GAP_SEC || 120));
  const stops = [];

  let startIdx = -1;
  let sumLat = 0;
  let sumLon = 0;
  let pointCount = 0;
  for (let i = 0; i < clean.length; i++) {
    const p = clean[i];
    // speed_kmh is already the resolver's trusted QF/OBD/GPS value. Raw GPS speed is
    // intentionally not allowed to break an otherwise spatially stable stop.
    const speed = Math.max(0, Number(p.speed_kmh || 0));
    const previousDate = i > 0 ? parseUtc_(clean[i - 1].utc) : null;
    const currentDate = parseUtc_(p.utc);
    const gapSec = previousDate && currentDate ? (currentDate.getTime() - previousDate.getTime()) / 1000 : 0;
    if (gapSec > maxNormalGapSec) {
      pushStopCandidate_(stops, clean, startIdx, i - 1, minStopMs);
      startIdx = -1;
      sumLat = 0;
      sumLon = 0;
      pointCount = 0;
    }
    if (speed > maxStopSpeed) {
      pushStopCandidate_(stops, clean, startIdx, i - 1, minStopMs);
      startIdx = -1;
      sumLat = 0;
      sumLon = 0;
      pointCount = 0;
      continue;
    }
    if (startIdx < 0) {
      startIdx = i;
      sumLat = Number(p.lat);
      sumLon = Number(p.lon);
      pointCount = 1;
      continue;
    }
    const centroid = { lat: sumLat / pointCount, lon: sumLon / pointCount };
    const drift = latLonDistanceM_(centroid, p);
    if (drift > radiusM) {
      pushStopCandidate_(stops, clean, startIdx, i - 1, minStopMs);
      startIdx = i;
      sumLat = Number(p.lat);
      sumLon = Number(p.lon);
      pointCount = 1;
    } else {
      sumLat += Number(p.lat);
      sumLon += Number(p.lon);
      pointCount++;
    }
  }
  pushStopCandidate_(stops, clean, startIdx, clean.length - 1, minStopMs);
  return stops;
}

function pushStopCandidate_(out, track, startIdx, endIdx, minStopMs) {
  if (startIdx < 0 || endIdx <= startIdx) return;
  const startUtc = String(track[startIdx].utc || '');
  const endUtc = String(track[endIdx].utc || '');
  const start = parseUtc_(startUtc);
  const end = parseUtc_(endUtc);
  if (!start || !end) return;
  const durationMs = end.getTime() - start.getTime();
  if (!isFinite(durationMs) || durationMs < minStopMs) return;
  const midIndex = startIdx + Math.floor((endIdx - startIdx) / 2);
  const centroid = trackCentroid_(track, startIdx, endIdx);
  out.push({
    startIdx,
    endIdx,
    midIndex,
    durationSec: Math.floor(durationMs / 1000),
    utcStart: startUtc,
    utcEnd: endUtc,
    lat: centroid.lat,
    lon: centroid.lon,
  });
}

function trackCentroid_(track, startIdx, endIdx) {
  let lat = 0;
  let lon = 0;
  let count = 0;
  for (let i = Math.max(0, startIdx); i <= Math.min(track.length - 1, endIdx); i++) {
    lat += Number(track[i].lat);
    lon += Number(track[i].lon);
    count++;
  }
  return count > 0 ? { lat: lat / count, lon: lon / count } : { lat: 0, lon: 0 };
}

function latLonDistanceM_(a, b) {
  const R = 6371000;
  const lat1 = Number(a.lat) * Math.PI / 180;
  const lat2 = Number(b.lat) * Math.PI / 180;
  const dLat = lat2 - lat1;
  const dLon = (Number(b.lon) - Number(a.lon)) * Math.PI / 180;
  const x = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
  return R * c;
}

function bearingDeg_(from, to) {
  const lat1 = Number(from.lat) * Math.PI / 180;
  const lat2 = Number(to.lat) * Math.PI / 180;
  const dLon = (Number(to.lon) - Number(from.lon)) * Math.PI / 180;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  let brng = Math.atan2(y, x) * 180 / Math.PI;
  brng = (brng + 360) % 360;
  return brng;
}

function headingDeltaDeg_(a, b) {
  let d = Math.abs(Number(a) - Number(b));
  while (d > 360) d -= 360;
  if (d > 180) d = 360 - d;
  return d;
}

function telegramReady_() {
  if (!CFG.TELEGRAM_ENABLED) return false;
  const token = String(CFG.TELEGRAM_BOT_TOKEN || '').trim();
  const chats = parseTelegramChatIds_();
  if (!token || token.indexOf('CAMBIA') === 0) return false;
  if (!Array.isArray(chats) || chats.length === 0) return false;
  return true;
}

function parseTelegramChatIds_() {
  const raw = String(CFG.TELEGRAM_CHAT_ID || '').trim();
  if (!raw || raw.indexOf('CAMBIA') === 0) return [];
  const validChatIdRe = /^-?\d+$/;
  const out = [];
  const seen = new Set();
  for (const part of raw.split(',')) {
    const id = String(part || '').trim();
    if (!id) continue;
    if (!validChatIdRe.test(id)) {
      Logger.log(`Telegram chat_id invalido ignorado: ${id}`);
      continue;
    }
    if (seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

function testTelegram() {
  if (!telegramReady_()) {
    throw new Error('Configura TELEGRAM_ENABLED, TELEGRAM_BOT_TOKEN y TELEGRAM_CHAT_IDS');
  }
  if (!sendTelegramMessage_('Prueba del tracker Astra Launcher completada')) {
    throw new Error('Telegram no pudo entregar el mensaje');
  }
  return 'Mensaje enviado';
}

/**
 * Removes obsolete notification markers without touching credentials or configuration.
 * Legacy tg_notified:* keys are no longer read by V4. Current markers are bounded so
 * Script Properties cannot grow indefinitely (the store has a platform size limit).
 */
function cleanupTelegramNotificationProperties() {
  const result = pruneTelegramNotificationProperties_(PropertiesService.getScriptProperties(), '');
  const message = `Limpieza completada: ${result.deletedLegacy} marcas antiguas y ${result.deletedCurrent} marcas V4 sobrantes eliminadas. Se conservan ${result.keptCurrent} marcas V4 recientes.`;
  try {
    SpreadsheetApp.getUi().alert(message);
  } catch (_) {
    // Allows this function to be run from the Apps Script editor without an active UI.
  }
  return message;
}

function pruneTelegramNotificationProperties_(props, keepKey) {
  const all = props.getProperties();
  const selection = selectTelegramNotificationKeysToDelete_(
    all,
    keepKey,
    TELEGRAM_NOTIFICATION_MARKER_LIMIT
  );
  for (const key of selection.keys) {
    props.deleteProperty(key);
  }
  return selection;
}

function selectTelegramNotificationKeysToDelete_(allProperties, keepKey, limit) {
  const all = allProperties || {};
  const protectedKey = String(keepKey || '');
  const safeLimit = Math.max(0, Number(limit) || 0);
  const legacyKeys = [];
  const current = [];

  for (const key of Object.keys(all)) {
    if (key.indexOf(TELEGRAM_LEGACY_NOTIFICATION_PREFIX) === 0) {
      legacyKeys.push(key);
      continue;
    }
    if (key.indexOf(TELEGRAM_NOTIFICATION_PREFIX) !== 0 || key === protectedKey) continue;
    const rawTimestamp = Number(all[key]);
    current.push({
      key,
      timestamp: Number.isFinite(rawTimestamp) && rawTimestamp > 1 ? rawTimestamp : 0,
    });
  }

  current.sort((a, b) => a.timestamp - b.timestamp || a.key.localeCompare(b.key));
  const slotsForExisting = Math.max(0, safeLimit - (protectedKey ? 1 : 0));
  const currentKeys = current.slice(0, Math.max(0, current.length - slotsForExisting)).map((entry) => entry.key);
  return {
    keys: legacyKeys.concat(currentKeys),
    deletedLegacy: legacyKeys.length,
    deletedCurrent: currentKeys.length,
    keptCurrent: current.length - currentKeys.length + (protectedKey && all[protectedKey] ? 1 : 0),
  };
}

function notifyTelegramSessionEventIfNeeded_(ev) {
  if (!telegramReady_()) return false;
  const eventType = String(ev.eventType || '').trim().toLowerCase();
  if (eventType !== 'start' && eventType !== 'end') return false;

  const deviceId = String(ev.deviceId || '').trim();
  const sessionId = String(ev.sessionId || '').trim();
  if (!deviceId || !sessionId) return false;

  const props = PropertiesService.getScriptProperties();
  const key = `${TELEGRAM_NOTIFICATION_PREFIX}${eventType}:${deviceId}:${sessionId}`;
  if (String(props.getProperty(key) || '').trim()) return false;

  const sent = sendTelegramSessionReport_(ev);
  if (sent) {
    pruneTelegramNotificationProperties_(props, key);
    props.setProperty(key, String(Date.now()));
  }
  return sent;
}

/**
 * Sends the textual report first so a map-provider failure can never suppress the trip.
 * The exact-track image is an optional second Telegram message for closed sessions.
 */
function sendTelegramSessionReport_(ev, preparedMessage) {
  const message = preparedMessage || buildTelegramSessionMessage_(ev);
  const sent = sendTelegramMessage_(message);
  if (!sent) return false;

  const eventType = String(ev.eventType || '').trim().toLowerCase();
  const track = Array.isArray(ev.track) ? ev.track : [];
  if (eventType !== 'end' || track.length < 2 || !telegramMapReady_()) return true;

  const mapBlob = fetchGeoapifyTrackMap_(track, String(ev.sessionId || 'trayecto'));
  if (!mapBlob) return true;

  const caption = [
    '🗺 Recorrido GPS exacto',
    'Verde: inicio · Rojo: final',
    'La línea representa los puntos registrados; no es una ruta recalculada.',
  ].join('\n');
  sendTelegramPhoto_(mapBlob, caption);
  return true;
}

function telegramMapReady_() {
  if (!CFG.TELEGRAM_MAP_ENABLED) return false;
  const key = String(CFG.GEOAPIFY_API_KEY || '').trim();
  return !!key && key.indexOf('CAMBIA') !== 0;
}

function buildGpxStudioViewerUrl_(fileId) {
  const id = String(fileId || '').trim();
  if (!id) return '';
  const options = {
    ids: [id],
    basemap: 'libertyTopo',
    distanceMarkers: true,
    directionMarkers: true,
    distanceUnits: 'metric',
    theme: 'system',
  };
  return `https://gpx.studio/embed?options=${encodeURIComponent(JSON.stringify(options))}`;
}

/** Google Encoded Polyline with precision 5, accepted directly by Geoapify. */
function encodePolyline5_(track) {
  if (!Array.isArray(track) || track.length === 0) return '';
  let previousLat = 0;
  let previousLon = 0;
  let encoded = '';

  for (const point of track) {
    const lat = Math.round(Number(point.lat) * 1e5);
    const lon = Math.round(Number(point.lon) * 1e5);
    if (!isFinite(lat) || !isFinite(lon)) continue;
    encoded += encodePolylineSignedValue_(lat - previousLat);
    encoded += encodePolylineSignedValue_(lon - previousLon);
    previousLat = lat;
    previousLon = lon;
  }
  return encoded;
}

function encodePolylineSignedValue_(value) {
  let shifted = value < 0 ? ~(value << 1) : value << 1;
  let out = '';
  while (shifted >= 0x20) {
    out += String.fromCharCode((0x20 | (shifted & 0x1f)) + 63);
    shifted >>>= 5;
  }
  return out + String.fromCharCode(shifted + 63);
}

function buildGeoapifyStaticMapRequest_(track) {
  if (!Array.isArray(track) || track.length < 2) return null;
  const encoded = encodePolyline5_(track);
  if (!encoded) return null;
  const first = track[0];
  const last = track[track.length - 1];
  const width = Math.round(Math.min(1600, Math.max(320, Number(CFG.GEOAPIFY_MAP_WIDTH || 900))));
  const height = Math.round(Math.min(1200, Math.max(240, Number(CFG.GEOAPIFY_MAP_HEIGHT || 600))));

  return {
    url: `https://maps.geoapify.com/v1/staticmap?apiKey=${encodeURIComponent(String(CFG.GEOAPIFY_API_KEY || '').trim())}`,
    body: {
      style: String(CFG.GEOAPIFY_MAP_STYLE || 'osm-bright'),
      lang: 'es',
      width,
      height,
      format: 'png',
      geometries: [
        {
          type: 'polyline5',
          value: encoded,
          linecolor: '#0878d1',
          lineopacity: 0.95,
          linewidth: 5,
          linestyle: 'solid',
        },
        {
          type: 'circle',
          value: { lat: Number(first.lat), lon: Number(first.lon), radius: 9 },
          linecolor: '#ffffff',
          linewidth: 3,
          fillcolor: '#159447',
          fillopacity: 1,
        },
        {
          type: 'circle',
          value: { lat: Number(last.lat), lon: Number(last.lon), radius: 9 },
          linecolor: '#ffffff',
          linewidth: 3,
          fillcolor: '#d9363e',
          fillopacity: 1,
        },
      ],
    },
  };
}

function fetchGeoapifyTrackMap_(track, sessionId) {
  const request = buildGeoapifyStaticMapRequest_(track);
  if (!request) return null;
  try {
    const response = UrlFetchApp.fetch(request.url, {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify(request.body),
      muteHttpExceptions: true,
    });
    const code = response.getResponseCode();
    const headers = response.getHeaders ? response.getHeaders() : {};
    const contentType = String(headers['Content-Type'] || headers['content-type'] || '');
    if (code < 200 || code >= 300 || (contentType && contentType.indexOf('image/') !== 0)) {
      Logger.log(`Geoapify static map error HTTP ${code}: ${response.getContentText()}`);
      return null;
    }
    const safeSession = String(sessionId || 'trayecto').replace(/[^a-zA-Z0-9_.-]+/g, '_');
    return response.getBlob().setName(`recorrido_${safeSession}.png`);
  } catch (err) {
    Logger.log(`Geoapify static map exception: ${err}`);
    return null;
  }
}

function buildTelegramSessionMessage_(ev) {
  const eventType = String(ev.eventType || '').trim().toLowerCase();
  const deviceId = String(ev.deviceId || '').trim();
  const sessionId = String(ev.sessionId || '').trim();
  const track = Array.isArray(ev.track) ? ev.track : [];
  const analysis = ev.analysis || analyzeTrack_(track);
  const bounds = resolveSessionUtcBounds_(
    analysis.track,
    String(ev.startUtc || ''),
    String(ev.endUtc || ''),
    { distanceKm: analysis.distanceKm, preferTrackBounds: eventType === 'end', updatedUtc: String(ev.updatedUtc || '') }
  );
  const lines = [];

  if (eventType === 'start') {
    lines.push('🚗 Trayecto iniciado');
    lines.push(`Dispositivo: ${deviceId}`);
    lines.push(`Sesión: ${sessionId}`);
    if (bounds.startUtc) lines.push(`Inicio: ${formatLocalDateTime_(bounds.startUtc)}`);
    const startUrl = String(ev.startLocationUrl || '').trim();
    if (startUrl) lines.push(`Posición inicial: ${startUrl}`);
    return lines.join('\n');
  }

  lines.push('🏁 Trayecto finalizado');
  lines.push(`Dispositivo: ${deviceId}`);
  lines.push(`Sesión: ${sessionId}`);
  if (bounds.startUtc) lines.push(`Inicio: ${formatLocalDateTime_(bounds.startUtc)}`);
  if (bounds.endUtc) lines.push(`Fin: ${formatLocalDateTime_(bounds.endUtc)}`);
  const closeReason = formatCloseReason_(ev.closeReason);
  if (closeReason) lines.push(`Cierre: ${closeReason}`);
  lines.push('');
  lines.push(`Duración total: ${formatHumanDuration_(analysis.durationSec)}`);
  lines.push(`En movimiento: ${formatHumanDuration_(analysis.movingDurationSec)}`);
  lines.push(`Detenido: ${formatHumanDuration_(analysis.stoppedDurationSec)}`);
  if (analysis.unknownDurationSec > 0) {
    lines.push(`Sin clasificar por huecos GPS: ${formatHumanDuration_(analysis.unknownDurationSec)}`);
  }
  lines.push(`Distancia validada: ${formatNumberEs_(analysis.distanceKm, 2)} km`);
  lines.push(`Velocidad media: ${formatNumberEs_(analysis.averageSpeedKmh, 1)} km/h`);
  lines.push(`Media en movimiento: ${formatNumberEs_(analysis.movingAverageSpeedKmh, 1)} km/h`);
  lines.push(analysis.maxSpeedConfirmedKmh > 0
    ? `Máxima confirmada: ${formatNumberEs_(analysis.maxSpeedConfirmedKmh, 1)} km/h`
    : 'Máxima confirmada: no disponible');
  if (analysis.maxSpeedObservedKmh > analysis.maxSpeedConfirmedKmh + 0.1) {
    lines.push(`Pico aislado descartado: ${formatNumberEs_(analysis.maxSpeedObservedKmh, 1)} km/h`);
  }
  lines.push('');
  lines.push(`Puntos GPS: ${analysis.validPointsCount} válidos de ${analysis.rawPointsCount}`);
  lines.push(`Descartados: ${analysis.rejectedPointsCount} · Anomalías: ${analysis.anomalyCount} · Huecos: ${analysis.gapCount}`);
  lines.push(`Calidad del recorrido: ${analysis.quality}`);
  lines.push(`Paradas de ${Number(CFG.MAPS_STOP_MINUTES || 5)} min o más: ${analysis.stopCount}`);

  const stops = Array.isArray(analysis.stops) ? analysis.stops.slice(0, 3) : [];
  for (let i = 0; i < stops.length; i++) {
    const stop = stops[i];
    lines.push(`Parada ${i + 1}: ${formatHumanDuration_(stop.durationSec)} · ${buildGoogleMapsPointUrl_(stop.lat, stop.lon)}`);
  }

  const mapsUrl = String(ev.mapsUrl || '').trim();
  const gpxUrl = String(ev.gpxUrl || '').trim();
  const gpxFileId = String(ev.gpxFileId || '').trim();
  const gpxDownloadUrl = String(ev.gpxDownloadUrl || '').trim();
  const gpxStudioUrl = buildGpxStudioViewerUrl_(gpxFileId);
  const lastLocationUrl = String(ev.lastLocationUrl || '').trim();
  if (gpxStudioUrl) lines.push(`Mapa interactivo del recorrido exacto: ${gpxStudioUrl}`);
  if (gpxDownloadUrl) {
    lines.push(`Descargar recorrido GPS (GPX): ${gpxDownloadUrl}`);
  } else if (gpxUrl) {
    lines.push(`Recorrido GPS exacto (GPX): ${gpxUrl}`);
  }
  if (mapsUrl) lines.push(`Ruta orientativa de Google Maps (${countGoogleMapsRoutePoints_(mapsUrl)} hitos): ${mapsUrl}`);
  if (lastLocationUrl) lines.push(`Posición final: ${lastLocationUrl}`);

  if (
    analysis.durationSec > 0 &&
    analysis.maxSpeedConfirmedKmh > 0 &&
    analysis.averageSpeedKmh > analysis.maxSpeedConfirmedKmh * 1.10
  ) {
    lines.push('⚠️ Resumen inconsistente: revisa los puntos descartados antes de usar estas métricas.');
  }
  return lines.join('\n');
}

function countGoogleMapsRoutePoints_(url) {
  const marker = '/maps/dir/';
  const value = String(url || '');
  const index = value.indexOf(marker);
  if (index < 0) return value ? 1 : 0;
  return value.slice(index + marker.length).split('/').filter((part) => part.trim()).length;
}

function formatCloseReason_(reason) {
  const value = String(reason || '').trim().toLowerCase();
  const labels = {
    acc_off: 'contacto apagado',
    idle_timeout: 'inactividad',
    inactivity: 'inactividad',
    stale_timeout: 'sesión recuperada por mantenimiento',
    manual: 'cierre manual',
  };
  return labels[value] || (value ? value.replace(/_/g, ' ') : '');
}

function resolveSessionUtcBounds_(track, startUtcRaw, endUtcRaw, opts) {
  const options = opts || {};
  const startCandidate = String(startUtcRaw || '').trim();
  const endCandidate = String(endUtcRaw || '').trim();
  const startDate = parseUtc_(startCandidate);
  const endDate = parseUtc_(endCandidate);
  const updatedCandidate = String(options.updatedUtc || '').trim();
  const updatedDate = parseUtc_(updatedCandidate);
  const distanceKm = Number(options.distanceKm || 0);
  const trackBounds = getTrackUtcBounds_(track);
  const firstTrackUtc = trackBounds.startUtc;
  const lastTrackUtc = trackBounds.endUtc;

  if (options.preferTrackBounds && (firstTrackUtc || lastTrackUtc)) {
    const trackPreferred = pickPlausibleUtcBounds_(
      firstTrackUtc,
      lastTrackUtc,
      distanceKm
    );
    if (trackPreferred) return trackPreferred;
    return {
      startUtc: firstTrackUtc || startCandidate,
      endUtc: lastTrackUtc || endCandidate || updatedCandidate,
    };
  }

  let startUtc = startDate ? startCandidate : firstTrackUtc;
  let endUtc = endDate ? endCandidate : lastTrackUtc;
  if (!startUtc && firstTrackUtc) startUtc = firstTrackUtc;
  if (!endUtc && lastTrackUtc) endUtc = lastTrackUtc;

  if (startUtc && endUtc) {
    const s = parseUtc_(startUtc);
    const e = parseUtc_(endUtc);
    if (s && e && e.getTime() < s.getTime()) {
      const explicitBounds = pickPlausibleUtcBounds_(
        startCandidate,
        endCandidate,
        distanceKm
      );
      if (explicitBounds) return explicitBounds;

      const trackBounds = pickPlausibleUtcBounds_(
        firstTrackUtc,
        lastTrackUtc,
        distanceKm
      );
      if (trackBounds) return trackBounds;

      const hybridBounds = pickPlausibleUtcBounds_(
        startCandidate || firstTrackUtc,
        endCandidate || lastTrackUtc || updatedCandidate,
        distanceKm
      );
      if (hybridBounds) return hybridBounds;

      if (startDate && updatedDate && updatedDate.getTime() >= startDate.getTime()) {
        return { startUtc: startCandidate, endUtc: updatedCandidate };
      }
    }
  }
  return { startUtc, endUtc };
}

function pickPlausibleUtcBounds_(startUtcRaw, endUtcRaw, distanceKm) {
  const startUtc = String(startUtcRaw || '').trim();
  const endUtc = String(endUtcRaw || '').trim();
  const start = parseUtc_(startUtc);
  const end = parseUtc_(endUtc);
  if (!start || !end) return null;

  const deltaMs = end.getTime() - start.getTime();
  if (!isFinite(deltaMs) || deltaMs < 0) return null;
  if (deltaMs === 0) return { startUtc, endUtc };

  const km = Math.max(0, Number(distanceKm || 0));
  if (km <= 0) return { startUtc, endUtc };

  const avgSpeedKmh = km / (deltaMs / 3600000);
  const maxAvgKmh = Math.max(20, Number(CFG.TRACK_MAX_REPORTED_SPEED_KMH || 220));
  if (avgSpeedKmh > maxAvgKmh) return null;

  return { startUtc, endUtc };
}

function sendTelegramMessage_(text) {
  if (!telegramReady_()) return false;
  const token = String(CFG.TELEGRAM_BOT_TOKEN || '').trim();
  const chatIds = parseTelegramChatIds_();
  const url = `https://api.telegram.org/bot${token}/sendMessage`;

  let sentCount = 0;
  try {
    for (const chatId of chatIds) {
      const payload = {
        chat_id: chatId,
        text: String(text || ''),
        disable_web_page_preview: !!CFG.TELEGRAM_DISABLE_PREVIEW,
      };
      const resp = UrlFetchApp.fetch(url, {
        method: 'post',
        contentType: 'application/json',
        payload: JSON.stringify(payload),
        muteHttpExceptions: true,
      });
      const code = resp.getResponseCode();
      if (code >= 200 && code < 300) {
        sentCount++;
        continue;
      }
      Logger.log(`Telegram sendMessage error chat_id=${chatId} HTTP ${code}: ${resp.getContentText()}`);
    }
    return sentCount > 0;
  } catch (err) {
    Logger.log(`Telegram sendMessage exception: ${err}`);
    return sentCount > 0;
  }
}

function sendTelegramPhoto_(photoBlob, caption) {
  if (!telegramReady_() || !photoBlob) return false;
  const token = String(CFG.TELEGRAM_BOT_TOKEN || '').trim();
  const chatIds = parseTelegramChatIds_();
  const url = `https://api.telegram.org/bot${token}/sendPhoto`;
  let sentCount = 0;

  try {
    for (const chatId of chatIds) {
      const response = UrlFetchApp.fetch(url, {
        method: 'post',
        payload: {
          chat_id: chatId,
          photo: photoBlob,
          caption: String(caption || '').slice(0, 1024),
        },
        muteHttpExceptions: true,
      });
      const code = response.getResponseCode();
      if (code >= 200 && code < 300) {
        sentCount++;
      } else {
        Logger.log(`Telegram sendPhoto error chat_id=${chatId} HTTP ${code}: ${response.getContentText()}`);
      }
    }
    return sentCount > 0;
  } catch (err) {
    Logger.log(`Telegram sendPhoto exception: ${err}`);
    return sentCount > 0;
  }
}

function getHeaderMap_(sheet) {
  const width = Math.max(1, sheet.getLastColumn());
  const headers = sheet.getRange(1, 1, 1, width).getValues()[0];
  const map = {};
  for (let i = 0; i < headers.length; i++) {
    const h = String(headers[i] || '').trim();
    if (h) map[h] = i + 1;
  }
  return map;
}

function ensureSheetByHeaders_(spreadsheet, name, requiredHeaders) {
  let sheet = spreadsheet.getSheetByName(name);
  if (!sheet) sheet = spreadsheet.insertSheet(name);
  const map = getHeaderMap_(sheet);
  ensureHeaders_(sheet, requiredHeaders, map);
  sheet.setFrozenRows(1);
  return sheet;
}

function appendObjectRows_(sheet, headers, objects) {
  if (!objects || objects.length === 0) return;
  const map = getHeaderMap_(sheet);
  ensureHeaders_(sheet, headers, map);
  const updatedMap = getHeaderMap_(sheet);
  const width = sheet.getLastColumn();
  const rows = objects.map((obj) => {
    const row = new Array(width).fill('');
    Object.keys(obj).forEach((key) => {
      if (updatedMap[key]) row[updatedMap[key] - 1] = obj[key];
    });
    return row;
  });
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, width).setValues(rows);
}

function findBatchRecord_(sheet, batchKey) {
  const map = getHeaderMap_(sheet);
  if (!map.batch_key || sheet.getLastRow() < 2) return null;
  const found = sheet.getRange(2, map.batch_key, sheet.getLastRow() - 1, 1)
    .createTextFinder(batchKey)
    .matchEntireCell(true)
    .findNext();
  if (!found) return null;
  const row = sheet.getRange(found.getRow(), 1, 1, sheet.getLastColumn()).getValues()[0];
  const out = {};
  Object.keys(map).forEach((key) => { out[key] = row[map[key] - 1]; });
  return out;
}

function countBatchPoints_(sheet, deviceId, sessionId, batchUid) {
  const map = getHeaderMap_(sheet);
  if (!map.device_id || !map.session_id || !map.batch_uid || sheet.getLastRow() < 2) return 0;
  const matches = sheet.getRange(2, map.batch_uid, sheet.getLastRow() - 1, 1)
    .createTextFinder(batchUid)
    .matchEntireCell(true)
    .findAll();
  let count = 0;
  for (const match of matches) {
    const row = sheet.getRange(match.getRow(), 1, 1, sheet.getLastColumn()).getValues()[0];
    if (
      String(row[map.device_id - 1] || '') === deviceId &&
      String(row[map.session_id - 1] || '') === sessionId &&
      String(row[map.batch_uid - 1] || '') === batchUid
    ) count++;
  }
  return count;
}

function findPointRecord_(sheet, pointUid) {
  const map = getHeaderMap_(sheet);
  if (!map.point_uid || sheet.getLastRow() < 2) return null;
  const found = sheet.getRange(2, map.point_uid, sheet.getLastRow() - 1, 1)
    .createTextFinder(pointUid)
    .matchEntireCell(true)
    .findNext();
  if (!found) return null;
  const row = sheet.getRange(found.getRow(), 1, 1, sheet.getLastColumn()).getValues()[0];
  const out = {};
  Object.keys(map).forEach((key) => { out[key] = row[map[key] - 1]; });
  out.__row = found.getRow();
  return out;
}

function sameTrackingPoint_(existing, deviceId, sessionId, pointSeq, lat, lon) {
  const sameCoordinates =
    Math.abs(Number(existing.lat) - lat) <= 0.0000001 &&
    Math.abs(Number(existing.lon) - lon) <= 0.0000001;
  return String(existing.device_id || '') === deviceId &&
    String(existing.session_id || '') === sessionId &&
    Number(existing.point_seq) === pointSeq &&
    sameCoordinates;
}

function ensureHeaders_(sheet, required, map) {
  const missing = required.filter((h) => !map[h]);
  if (missing.length === 0) return;
  if (Object.keys(map).length === 0) {
    sheet.getRange(1, 1, 1, required.length).setValues([required]);
    return;
  }
  const width = Math.max(1, sheet.getLastColumn());
  const firstRow = sheet.getRange(1, 1, 1, width).getValues()[0];
  const updated = firstRow.slice();
  for (const h of missing) updated.push(h);
  sheet.getRange(1, 1, 1, updated.length).setValues([updated]);
}

function scriptProperty_(name, fallback) {
  const value = PropertiesService.getScriptProperties().getProperty(name);
  return value === null || value === undefined || String(value).trim() === ''
    ? fallback
    : String(value).trim();
}

function scriptBoolean_(name, fallback) {
  const raw = scriptProperty_(name, fallback ? 'true' : 'false').toLowerCase();
  if (raw === 'true' || raw === '1' || raw === 'yes' || raw === 'si') return true;
  if (raw === 'false' || raw === '0' || raw === 'no') return false;
  return fallback;
}

function scriptNumber_(name, fallback) {
  const parsed = Number(scriptProperty_(name, String(fallback)));
  return isFinite(parsed) ? parsed : fallback;
}

function finiteNumber_(value, fallback) {
  const parsed = Number(value);
  return isFinite(parsed) ? parsed : fallback;
}

function roundNumber_(value, decimals) {
  const safe = finiteNumber_(value, 0);
  const factor = Math.pow(10, Math.max(0, Math.trunc(decimals || 0)));
  return Math.round((safe + Number.EPSILON) * factor) / factor;
}

function formatNumberEs_(value, decimals) {
  return roundNumber_(value, decimals).toFixed(Math.max(0, decimals || 0)).replace('.', ',');
}

function xmlEscape_(s) {
  return String(s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function fmtUtc(dateObj) {
  const date = dateObj instanceof Date ? dateObj : new Date(dateObj);
  if (isNaN(date.getTime())) throw new Error('invalid_utc_date');
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function formatLocalDateTime_(utc) {
  const date = parseUtc_(utc);
  if (!date) return String(utc || '');
  if (typeof Utilities !== 'undefined' && Utilities.formatDate) {
    return `${Utilities.formatDate(date, CFG.TZ, 'dd/MM/yyyy HH:mm:ss')} (${CFG.TZ})`;
  }
  return fmtUtc(date);
}

function parseUtc_(s) {
  if (!s) return null;
  const raw = String(s).trim();
  if (!raw) return null;

  const modem = raw.match(/^(\d{4})[\/-](\d{2})[\/-](\d{2})[,\sT](\d{2}):(\d{2}):(\d{2})(?:\s*(Z|[+-]\d{2}:?\d{2}|[+-]\d{2}))?$/i);
  if (modem) {
    const year = Number(modem[1]);
    const month = Number(modem[2]) - 1;
    const day = Number(modem[3]);
    const hour = Number(modem[4]);
    const minute = Number(modem[5]);
    const second = Number(modem[6]);
    let offsetMin = 0;
    const tz = String(modem[7] || 'Z').toUpperCase();
    if (tz !== 'Z') {
      const m = tz.match(/^([+-])(\d{2}):?(\d{2})?$/);
      if (m) {
        const sign = m[1] === '-' ? -1 : 1;
        const hh = Number(m[2] || 0);
        const mm = Number(m[3] || 0);
        offsetMin = sign * (hh * 60 + mm);
      }
    }
    const utcMs = Date.UTC(year, month, day, hour, minute, second) - (offsetMin * 60000);
    const d0 = new Date(utcMs);
    return isNaN(d0.getTime()) ? null : d0;
  }

  const normalized = /z|[+-]\d{2}:?\d{2}$/i.test(raw) ? raw : `${raw}Z`;
  const d = new Date(normalized);
  return isNaN(d.getTime()) ? null : d;
}

function formatDurationBetweenUtc_(startUtc, endUtc) {
  const start = parseUtc_(startUtc);
  const end = parseUtc_(endUtc);
  if (!start || !end) return '';
  const deltaMs = end.getTime() - start.getTime();
  if (!isFinite(deltaMs) || deltaMs < 0) return '';

  const totalSec = Math.floor(deltaMs / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const hh = h < 10 ? `0${h}` : String(h);
  const mm = m < 10 ? `0${m}` : String(m);
  const ss = s < 10 ? `0${s}` : String(s);
  return `${hh}:${mm}:${ss}`;
}

function formatDurationFromSeconds_(totalSecIn) {
  const totalSec = Math.max(0, Math.floor(Number(totalSecIn || 0)));
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const hh = h < 10 ? `0${h}` : String(h);
  const mm = m < 10 ? `0${m}` : String(m);
  const ss = s < 10 ? `0${s}` : String(s);
  return `${hh}:${mm}:${ss}`;
}

function formatHumanDuration_(totalSecIn) {
  const totalSec = Math.max(0, Math.floor(Number(totalSecIn || 0)));
  const hours = Math.floor(totalSec / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  const parts = [];
  if (hours > 0) parts.push(`${hours} h`);
  if (minutes > 0 || hours > 0) parts.push(`${minutes} min`);
  parts.push(`${seconds} s`);
  return parts.join(' ');
}

function jsonOut(obj, code) {
  // Apps Script WebApp a veces devuelve HTTP 200 real.
  // Por eso incluimos http_code dentro del JSON.
  obj.http_code = code;
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

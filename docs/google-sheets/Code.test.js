'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const properties = {
  TZ: 'Europe/Madrid',
  MAPS_MAX_POINTS: '20',
  MAPS_MAX_URL_LENGTH: '1800',
  MAPS_STOP_MINUTES: '5',
  MAPS_STOP_MAX_SPEED_KMH: '10',
  MAPS_STOP_RADIUS_M: '40',
  MAPS_KEY_POINT_INTERVAL_KM: '10',
  TRACK_MAX_ACCURACY_M: '60',
  TRACK_MAX_SEGMENT_SPEED_KMH: '180',
  TRACK_MAX_REPORTED_SPEED_KMH: '220',
  TRACK_MOVING_MIN_SPEED_KMH: '3',
  TRACK_MAX_NORMAL_GAP_SEC: '120',
  GEOAPIFY_API_KEY: 'geoapify-test-key',
  GEOAPIFY_MAP_WIDTH: '900',
  GEOAPIFY_MAP_HEIGHT: '600',
  GEOAPIFY_MAP_STYLE: 'osm-bright',
};
let propertyReads = 0;

const context = {
  console,
  PropertiesService: {
    getScriptProperties: () => ({
      getProperty: (name) => {
        propertyReads++;
        return Object.prototype.hasOwnProperty.call(properties, name) ? properties[name] : null;
      },
    }),
  },
  Utilities: {
    formatDate: (date, timezone) => `${date.toISOString().replace('T', ' ').slice(0, 19)} [${timezone}]`,
  },
  Logger: { log: () => {} },
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('docs/google-sheets/Code.gs', 'utf8'), context, { filename: 'Code.gs' });

function point(seq, seconds, lat, lon, speed, source = 'GPS', accuracy = 5) {
  return {
    point_seq: seq,
    point_uid: `test:${seq}`,
    utc: new Date(Date.UTC(2026, 7, 10, 14, 0, seconds)).toISOString(),
    lat,
    lon,
    speed_kmh: speed,
    gps_speed_kmh: source === 'GPS' ? speed : 0,
    speed_source: source,
    accuracy_m: accuracy,
    provider: 'gps',
    satellites: 12,
    ele: 20,
  };
}

function test(name, fn) {
  try {
    fn();
    console.log(`OK ${name}`);
  } catch (error) {
    console.error(`FAIL ${name}`);
    throw error;
  }
}

test('rechaza un salto GPS y retoma desde el ultimo punto valido', () => {
  const track = [
    point(1, 0, 40.0000, -3.0800, 32),
    point(2, 10, 40.0000, -3.0790, 32),
    point(3, 20, 40.0000, -2.0000, 210),
    point(4, 30, 40.0000, -3.0770, 32),
  ];
  const result = context.analyzeTrack_(track);
  assert.strictEqual(result.rawPointsCount, 4);
  assert.strictEqual(result.validPointsCount, 3);
  assert.strictEqual(result.rejectedPointsCount, 1);
  assert.strictEqual(result.anomalyCount, 1);
  assert(result.distanceKm > 0.15 && result.distanceKm < 0.35, result.distanceKm);
});

test('deduplica un punto repetido sin inflar la distancia', () => {
  const first = point(1, 0, 40.0000, -3.0800, 30);
  const second = point(2, 10, 40.0000, -3.0790, 30);
  const result = context.analyzeTrack_([first, second, { ...second }]);
  assert.strictEqual(result.rawPointsCount, 3);
  assert.strictEqual(result.validPointsCount, 2);
  assert.strictEqual(result.rejectedPointsCount, 1);
  assert(result.distanceKm < 0.12);
});

test('no convierte un pico GPS aislado en velocidad maxima confirmada', () => {
  const track = [
    point(1, 0, 40.0000, -3.0800, 50),
    point(2, 10, 40.0000, -3.0790, 210),
    point(3, 20, 40.0000, -3.0780, 50),
    point(4, 30, 40.0000, -3.0770, 50),
  ];
  const result = context.analyzeTrack_(track);
  assert.strictEqual(result.maxSpeedObservedKmh, 210);
  assert.strictEqual(result.maxSpeedConfirmedKmh, 50);
});

test('confia en una maxima procedente de OBD aunque sea una sola muestra', () => {
  const track = [
    point(1, 0, 40.0000, -3.0800, 70, 'OBD2'),
    point(2, 10, 40.0000, -3.0780, 112, 'OBD2'),
    point(3, 20, 40.0000, -3.0760, 70, 'OBD2'),
  ];
  assert.strictEqual(context.analyzeTrack_(track).maxSpeedConfirmedKmh, 112);
});

test('detecta una parada sostenida sin sumar deriva GPS como distancia', () => {
  const track = [];
  for (let i = 0; i <= 6; i++) {
    track.push(point(i + 1, i * 60, 40.0000 + (i % 2) * 0.00001, -3.0800, 0));
  }
  const result = context.analyzeTrack_(track);
  assert.strictEqual(result.stopCount, 1);
  assert(result.stoppedDurationSec >= 300);
  assert(result.distanceKm < 0.01);
});

test('el resumen usa coma decimal y muestra trazabilidad de los puntos', () => {
  const track = [
    point(1, 0, 40.811027, -3.085003, 112, 'OBD2'),
    {
      ...point(2, 47, 40.752763, -3.151861, 112, 'OBD2'),
      utc: '2026-08-10T15:01:43Z',
    },
  ];
  track[0].utc = '2026-08-10T14:23:36Z';
  const analysis = context.analyzeTrack_(track);
  const message = context.buildTelegramSessionMessage_({
    eventType: 'end',
    deviceId: 'RADIO-TS10',
    sessionId: 'session_example',
    track: analysis.track,
    analysis,
    startUtc: track[0].utc,
    endUtc: track[1].utc,
    mapsUrl: context.buildGoogleMapsDirectionsUrl(analysis.track),
    gpxUrl: 'https://drive.google.com/file/d/test/view',
    gpxFileId: 'test-drive-file-id',
    gpxDownloadUrl: 'https://drive.google.com/uc?export=download&id=test-drive-file-id',
    lastLocationUrl: context.buildGoogleMapsPointUrl_(track[1].lat, track[1].lon),
  });
  assert(message.includes('Distancia validada:'));
  assert(/Distancia validada: \d+,\d{2} km/.test(message), message);
  assert(message.includes('Puntos GPS: 2 válidos de 2'));
  assert(message.includes('Velocidad media:'));
  assert(message.includes('Mapa interactivo del recorrido exacto: https://gpx.studio/embed?options='));
  assert(message.includes('Descargar recorrido GPS (GPX):'));
  assert(message.includes('Ruta orientativa de Google Maps'));
  assert(!message.includes('Distancia: 56.565 km'));
});

test('la ruta incluye hitos intermedios en un recorrido largo casi recto', () => {
  const track = [];
  for (let i = 0; i <= 12; i++) {
    track.push(point(i + 1, i * 10, 40.8, -3.08 - i * 0.02, 80, 'OBD2'));
  }
  const anchors = context.selectTrackAnchors_(track, 100);
  assert(anchors.length > 2, anchors.length);
  assert.strictEqual(anchors[0].point_seq, 1);
  assert.strictEqual(anchors[anchors.length - 1].point_seq, 13);
});

test('conserva la forma de una rotonda y no la reduce a cuatro hitos', () => {
  const track = [];
  const centerLat = 40.723;
  const centerLon = -4.480;
  for (let i = 0; i <= 20; i++) {
    const angle = Math.PI + (Math.PI * 1.5 * i / 20);
    track.push(point(
      i + 1,
      i * 3,
      centerLat + Math.sin(angle) * 0.00055,
      centerLon + Math.cos(angle) * 0.00068,
      20,
    ));
  }
  const anchors = context.selectTrackAnchors_(track, 20);
  assert(anchors.length >= 7, `Solo conservo ${anchors.length} hitos`);
});

test('genera GPX completo y UTC real', () => {
  const track = [point(1, 0, 40.8, -3.08, 0), point(2, 10, 40.8, -3.079, 20)];
  const gpx = context.buildGpx(track, 'Prueba');
  assert(gpx.startsWith('<?xml version="1.0" encoding="UTF-8"?>'));
  assert(gpx.includes('<trkpt'));
  assert(gpx.endsWith('</gpx>'));
  assert.strictEqual(context.fmtUtc(new Date('2026-08-10T14:23:36.789Z')), '2026-08-10T14:23:36Z');
});

test('genera enlace interactivo de gpx.studio con el fileId de Drive', () => {
  const url = context.buildGpxStudioViewerUrl_('drive-file-123');
  assert(url.startsWith('https://gpx.studio/embed?options='), url);
  const options = JSON.parse(decodeURIComponent(url.split('options=')[1]));
  assert.deepStrictEqual(JSON.parse(JSON.stringify(options.ids)), ['drive-file-123']);
  assert.strictEqual(options.distanceMarkers, true);
  assert.strictEqual(options.directionMarkers, true);
});

test('codifica la polilinea exacta en formato Google precision 5', () => {
  const encoded = context.encodePolyline5_([
    { lat: 38.5, lon: -120.2 },
    { lat: 40.7, lon: -120.95 },
    { lat: 43.252, lon: -126.453 },
  ]);
  assert.strictEqual(encoded, '_p~iF~ps|U_ulLnnqC_mqNvxq`@');
});

test('prepara Geoapify con trazado exacto y puntos de inicio y fin', () => {
  const track = [
    { lat: 40.0000, lon: -3.0800 },
    { lat: 40.0005, lon: -3.0790 },
    { lat: 40.0010, lon: -3.0780 },
  ];
  const request = context.buildGeoapifyStaticMapRequest_(track);
  assert(request.url.includes('apiKey=geoapify-test-key'));
  assert.strictEqual(request.body.width, 900);
  assert.strictEqual(request.body.height, 600);
  assert.strictEqual(request.body.geometries[0].type, 'polyline5');
  assert.strictEqual(request.body.geometries[0].value, context.encodePolyline5_(track));
  assert.strictEqual(request.body.geometries[1].fillcolor, '#159447');
  assert.strictEqual(request.body.geometries[2].fillcolor, '#d9363e');
  assert(!JSON.stringify(request.body).includes('route'));
});

test('agrupa la escritura de sesiones y conserva columnas ajenas', () => {
  const writes = [];
  const sheet = {
    getRange: (row, column, rowCount, columnCount) => ({
      setValues: (values) => writes.push({ row, column, rowCount, columnCount, values }),
    }),
  };
  const rows = [
    ['dev1', 'old-start', 'usuario-a', 1],
    ['dev2', 'old-start-2', 'usuario-b', 2],
    ['dev3', 'old-start-3', 'usuario-c', 3],
  ];
  const map = { device_id: 1, start_utc: 2, distance_km: 4 };
  const patches = [
    { start_utc: 'new-start', distance_km: 12.5 },
    null,
    { start_utc: 'new-start-3', distance_km: 33.5 },
  ];

  context.writeSessionPatchesInBatches_(sheet, rows, map, patches);
  assert.strictEqual(writes.length, 2);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(writes[0])), {
    row: 2,
    column: 2,
    rowCount: 3,
    columnCount: 1,
    values: [['new-start'], ['old-start-2'], ['new-start-3']],
  });
  assert.deepStrictEqual(JSON.parse(JSON.stringify(writes[1].values)), [[12.5], [2], [33.5]]);
  assert.strictEqual(rows[0][2], 'usuario-a');
});

test('analiza una parada larga en tiempo lineal practico', () => {
  const track = [];
  for (let i = 0; i < 10000; i++) {
    const p = point(i + 1, 0, 40.8 + (i % 2) * 0.000001, -3.08, 0);
    p.utc = new Date(Date.UTC(2026, 7, 10, 0, 0, i)).toISOString();
    track.push(p);
  }
  const started = Date.now();
  const readsBefore = propertyReads;
  const stops = context.extractLongStops_(track);
  assert.strictEqual(stops.length, 1);
  assert(Date.now() - started < 2000, `Tardo ${Date.now() - started} ms`);
  assert(propertyReads - readsBefore <= 5, `Consulto propiedades ${propertyReads - readsBefore} veces`);
});

test('carga solamente el trayecto solicitado al vaciar una cola antigua', () => {
  const headers = [
    'rx_utc', 'device_id', 'session_id', 'batch_uid', 'point_seq', 'point_uid',
    'utc', 'lat', 'lon', 'speed_kmh', 'alt_m', 'satellites', 'fix', 'fix_3d',
    'rssi_dbm', 'accuracy_m', 'provider', 'gps_speed_kmh', 'speed_source',
  ];
  const makeRow = (deviceId, sessionId, sequence) => headers.map((header) => ({
    device_id: deviceId,
    session_id: sessionId,
    point_seq: sequence,
    point_uid: `${deviceId}:${sessionId}:${sequence}`,
    utc: `2026-08-10T10:00:0${sequence}Z`,
    lat: 40.7 + sequence * 0.001,
    lon: -4.4,
    speed_kmh: 10,
    accuracy_m: 5,
  })[header] ?? '');
  const values = [
    makeRow('RADIO', 'old', 1),
    makeRow('RADIO', 'new', 1),
    makeRow('RADIO', 'old', 2),
  ];
  const sheet = {
    getLastRow: () => values.length + 1,
    getLastColumn: () => headers.length,
    getRange: (row) => ({
      getValues: () => row === 1 ? [headers] : values,
    }),
  };

  const track = context.getSessionTrackPoints(sheet, 'RADIO', 'old');

  assert.strictEqual(track.length, 2);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(track.map((item) => item.point_seq))), [1, 2]);
});

test('limpia marcas Telegram heredadas y limita las V4 sin tocar configuracion', () => {
  const all = {
    API_KEY: 'tracker-secret',
    GEOAPIFY_API_KEY: 'map-secret',
    'tg_notified:end:GPS-AstraH:session_00135': '1',
    'tg_notified:start:GPS-AstraH:session_00136': '1',
    'tg_v4_notified:end:GPS-AstraH:session_00137': '1000',
    'tg_v4_notified:end:GPS-AstraH:session_00138': '2000',
    'tg_v4_notified:end:GPS-AstraH:session_00139': '3000',
  };

  const result = context.selectTelegramNotificationKeysToDelete_(
    all,
    'tg_v4_notified:end:GPS-AstraH:session_00140',
    3
  );

  assert.strictEqual(result.deletedLegacy, 2);
  assert.strictEqual(result.deletedCurrent, 1);
  assert(result.keys.includes('tg_notified:end:GPS-AstraH:session_00135'));
  assert(result.keys.includes('tg_notified:start:GPS-AstraH:session_00136'));
  assert(result.keys.includes('tg_v4_notified:end:GPS-AstraH:session_00137'));
  assert(!result.keys.includes('API_KEY'));
  assert(!result.keys.includes('GEOAPIFY_API_KEY'));
});

console.log('Todas las pruebas de Code.gs han pasado');

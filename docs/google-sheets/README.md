# Trip tracking with Google Sheets

**English** · [Español](README.es.md)

This guide sets up a complete system to:

1. Record trips with the head unit's GPS.
2. Store points and sessions in Google Sheets.
3. Retry uploads when the head unit loses Internet access.
4. Send Telegram notifications when a trip starts and ends.

The code to paste into Google Apps Script is [`Code.gs`](Code.gs), in this folder.

The script works both for a new spreadsheet and for updating an installation of an earlier
version of the receiver. It keeps existing columns and data, appends any missing V4 fields at the
end and creates the `batches` journal.

## 1. Requirements

- The launcher installed on the head unit.
- Precise location permission granted to the launcher.
- GPS enabled in Android.
- Internet access on the head unit via Wi-Fi, hotspot or 4G modem.
- A Google account.
- Optionally, a Telegram account to receive notifications.

If another device also uploads to the same spreadsheet, each device must use a different
Device ID.

## 2. Create the spreadsheet

1. Open [Google Sheets](https://sheets.google.com).
2. Create a blank spreadsheet.
3. Give it a recognisable name, for example `Astra trips`.
4. In the spreadsheet, open `Extensions > Apps Script`.
5. Delete the initial contents of `Code.gs`.
6. Paste the whole contents of [`Code.gs`](Code.gs).
7. Save the project.

You don't need to create the tabs or their columns by hand. The script creates and prepares
`points`, `sessions` and `batches`.

## 3. Create the shared API key

The API key prevents anyone who discovers the URL from writing to the spreadsheet.

1. Generate a random string of at least 32 characters with a password manager.
2. In Apps Script open `Project Settings`.
3. Under `Script Properties`, add:

| Property | Value |
| --- | --- |
| `API_KEY` | The generated random string |
| `TZ` | Your time zone, for example `Europe/Madrid` |
| `TELEGRAM_ENABLED` | `false` for now |

The same `API_KEY` is entered later on the head unit. Do not publish it, show it in screenshots
or write it directly into `Code.gs`.

### Script properties

All configuration lives in `Project Settings > Script Properties`, one row per property; there
is no configuration block with private values inside the code.

Only `API_KEY` is required. Omitted properties use these defaults:

| Property | Default | Purpose |
| --- | ---: | --- |
| `API_KEY` | no value | Key shared with the head unit |
| `SHEET_POINTS` | `points` | Points tab |
| `SHEET_SESSIONS` | `sessions` | Sessions tab |
| `SHEET_BATCHES` | `batches` | Idempotent retry journal |
| `TZ` | `Europe/Madrid` | Time zone used for dates and summaries |
| `DRIVE_GPX_FOLDER_ID` | empty | Drive folder ID for GPX files |
| `GPX_UPDATE_ON_EVERY_POST` | `false` | Regenerate the GPX on every batch |
| `MAPS_MAX_POINTS` | `20` | Maximum Google Maps waypoints (internal limit 25) |
| `MAPS_MAX_URL_LENGTH` | `1800` | Maximum URL length |
| `MAPS_STOP_MINUTES` | `5` | Minimum duration of a stop |
| `MAPS_STOP_MAX_SPEED_KMH` | `10` | Maximum speed during a stop |
| `MAPS_STOP_RADIUS_M` | `40` | Radius used to group a stop |
| `MAPS_TURN_MIN_DEG` | `30` | Minimum turn that is kept |
| `MAPS_TURN_MIN_SEGMENT_M` | `60` | Minimum segment, against GPS noise |
| `MAPS_KEY_POINT_INTERVAL_KM` | `10` | Minimum waypoint spacing on straight routes |
| `TRACK_MAX_ACCURACY_M` | `60` | Rejects historical points with worse accuracy |
| `TRACK_MAX_SEGMENT_SPEED_KMH` | `180` | Rejects jumps with an impossible spatial speed |
| `TRACK_MAX_REPORTED_SPEED_KMH` | `220` | Ceiling for a received speed reading |
| `TRACK_MOVING_MIN_SPEED_KMH` | `3` | Threshold for counting time as moving |
| `TRACK_MAX_NORMAL_GAP_SEC` | `120` | Beyond this the time is left unclassified |
| `RECALC_SESSIONS_PER_RUN` | `25` | Maximum sessions processed per historical run |
| `RECALC_TIME_BUDGET_SEC` | `240` | Working margin before scheduling a continuation |
| `TELEGRAM_ENABLED` | `false` | Enables start and end notifications |
| `TELEGRAM_BOT_TOKEN` | empty | Private bot token |
| `TELEGRAM_CHAT_IDS` | empty | Chat ID, or several separated by commas |
| `TELEGRAM_DISABLE_PREVIEW` | `true` | Disables link previews |
| `TELEGRAM_MAP_ENABLED` | `true` | Sends an image of the exact track when the trip ends |
| `GEOAPIFY_API_KEY` | empty | Private key for the static map; without it only text is sent |
| `GEOAPIFY_MAP_WIDTH` | `900` | Width of the image sent to Telegram |
| `GEOAPIFY_MAP_HEIGHT` | `600` | Height of the image sent to Telegram |
| `GEOAPIFY_MAP_STYLE` | `osm-bright` | Geoapify static map style |
| `STALE_CLOSE_MINUTES` | `20` | Minutes before an abandoned session is closed |
| `STALE_SCAN_LOOKBACK_HOURS` | `72` | Window checked by maintenance |

`TELEGRAM_CHAT_ID` is still accepted for compatibility, but `TELEGRAM_CHAT_IDS` is recommended.
Booleans are written as `true` or `false`.

## 4. Prepare the sheets and grant permissions

1. In the Apps Script editor select the `setupTrackerSheets` function.
2. Click `Run`.
3. Google asks for authorisation to modify the spreadsheet.
4. Accept the permissions with the account that owns the spreadsheet.
5. Go back to Google Sheets and check that these tabs exist:

   - `points`: one row per GPS position.
   - `sessions`: one row per trip with validated distance, times, speeds, anomalies, quality
     and links.
   - `batches`: journal of received batches that prevents duplicates on retries.

After reloading the spreadsheet the `Tracker TS10` menu also appears. Its entries are in Spanish.

Run `Tracker TS10 > Instalar mantenimiento` once. It creates a trigger every five minutes that
closes sessions left open by an abrupt power-off or a long network outage. It never installs
duplicate triggers.

If you want GPX files, create a folder in Google Drive, copy the text after `/folders/` in its
URL and save it as `DRIVE_GPX_FOLDER_ID`. The first GPX generation also asks for Drive
permission. If the property is left empty, the rest of the tracking works normally without
creating files.

## 5. Create the Telegram bot

This step is optional. If you don't want notifications, keep `TELEGRAM_ENABLED=false`.

1. Open a chat with `@BotFather` in Telegram.
2. Send `/newbot`.
3. Choose the bot's name and username.
4. Keep the token BotFather gives you.
5. Open a chat with the new bot and send it any message.
6. Get the `chat_id` by opening this in a browser:

   ```text
   https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates
   ```

7. Look for `"chat":{"id":...}` in the response.

To notify a group, add the bot to the group, send a message and repeat `getUpdates`. Group IDs
are usually negative.

Now add these properties in Apps Script:

| Property | Value |
| --- | --- |
| `TELEGRAM_ENABLED` | `true` |
| `TELEGRAM_BOT_TOKEN` | Token from BotFather |
| `TELEGRAM_CHAT_IDS` | One `chat_id` or several separated by commas |

Example for two recipients:

```text
123456789,-1001234567890
```

Then reload Google Sheets and run `Tracker TS10 > Probar Telegram`. The test message should
arrive.

The bot token is only stored in the private Apps Script properties. It is never entered on the
head unit.

### Add the exact map to the summary

The Telegram map is generated with Geoapify from the polyline of validated GPS points. No route
is requested and nothing is snapped to roads, so the line shows the same track used for the GPX
and the metrics.

1. Create a free account at [Geoapify](https://myprojects.geoapify.com/).
2. Create a project and copy its API key.
3. Add these properties in Apps Script:

   | Property | Value |
   | --- | --- |
   | `TELEGRAM_MAP_ENABLED` | `true` |
   | `GEOAPIFY_API_KEY` | The Geoapify API key |

   If Apps Script refuses to create `GEOAPIFY_API_KEY` because there are many `tg_notified:*`
   properties, first replace the code with the current version and run
   `Tracker TS10 > Limpiar marcas antiguas de Telegram`. Those entries are legacy
   anti-duplicate markers, not configuration. The cleanup does not touch any key, token or
   non-Telegram property and keeps up to 200 recent V4 markers.

4. For the interactive gpx.studio link to read the GPX, share the folder set in
   `DRIVE_GPX_FOLDER_ID` as `Anyone with the link > Viewer`. This makes the tracks accessible to
   anyone who receives the link; don't use a folder that holds other private documents.
5. Run `Tracker TS10 > Enviar resumen V4 de la última sesión` to test it with the last closed
   trip. The summary should arrive together with an image showing a green start, a red end and
   the blue line of the track.

If Geoapify is not configured, exceeds its quota or returns an error, the text summary is still
sent. The API key stays in the private Apps Script properties and is not included in Telegram
or in the gpx.studio links.

## 6. Deploy the Web App

1. In Apps Script click `Deploy > New deployment`.
2. Select the `Web app` type.
3. Configure:

   - `Execute as`: **Me**.
   - `Who has access`: **Anyone**.

4. Click `Deploy` and accept the requested permissions.
5. Copy the URL ending in `/exec`.

The `/dev` URL is only for testing from the editor and must not be configured in the car.

Open the `/exec` URL in a browser. A correct installation returns JSON similar to:

```json
{
  "ok": true,
  "service": "Astra Launcher vehicle tracker V4",
  "server_version": 4,
  "sheets_ready": true,
  "api_key_configured": true,
  "telegram_enabled": true,
  "telegram_map_enabled": true,
  "geoapify_configured": true
}
```

Every time `Code.gs` changes, create a new version from
`Deploy > Manage deployments > Edit > New version`. Saving the code does not update the
published Web App.

## 7. Configure the head unit

In the launcher open:

```text
Settings > Trip recording
```

Enter:

| Field | Recommended value |
| --- | --- |
| Apps Script URL | HTTPS URL ending in `/exec` |
| API key | The same `API_KEY` property |
| Device ID | `RADIO-TS10` |
| Sampling | `5` seconds |
| Minimum distance | `5` metres |
| Upload | `15` seconds |
| Points per batch | `10` |
| Close after inactivity | `15` minutes |

Enable trip recording and tap `Save`.

The `Device ID` must be unique for each device that uploads to the spreadsheet, otherwise two
devices could generate the same `session_id`.

## 8. First test

1. Park outdoors and wait until Android has a GPS position.
2. Check that the head unit has Internet access.
3. Drive at least a few hundred metres.
4. The tracker settings should show a session and a growing number of points.
5. The start notification should arrive on Telegram.
6. Switch off the ignition or stay stopped for the inactivity time.
7. The end notification should arrive and, if Geoapify is configured, the image of the exact
   track.
8. Check Google Sheets:

   - `points` must contain coordinates without duplicates.
   - `sessions` must show the `closed` status, validated distance, confirmed maximum speed,
     valid/discarded points, quality, GPX and Google Maps link.
9. Open the gpx.studio link from Telegram and confirm that it draws the same track as the image
   and the GPX, without re-routing it along streets.

The `Upload now` button forces an immediate attempt if there are pending items.

## 9. Working without Internet

Points are first stored in a private SQLite database inside the launcher. With no network:

- The trip keeps being recorded.
- The `pending` counter increases.
- The interface is not blocked.
- Uploads are retried later with the same identifiers.

The Apps Script uses `batch_uid` as a receipt journal, but always reconciles each
`point_uid = device_id + session_id + point_seq`. A retry therefore never duplicates rows, and
no points are lost if the batch journal exists but a previous run was interrupted halfway.

### Updating from an earlier version

1. Replace the whole project contents with the new [`Code.gs`](Code.gs).
2. Run `setupTrackerSheets` again; it does not delete data and adds the V4 columns.
3. Run `Tracker TS10 > Recalcular todas las sesiones (V4)`. It recomputes historical metrics
   from the stored points and rebuilds their routes without sending Telegram messages again. It
   works in batches and, if it does not finish in the first run, schedules automatic
   continuations until every row is done. Don't run it again while continuations are pending.
   `Tracker TS10 > Cancelar recalculo V4` stops the process and clears its cursor if you need to
   interrupt it.
4. In `Deploy > Manage deployments`, edit the Web App and select `New version`.
5. Keep the `/exec` URL and the same `API_KEY`.
6. Open the `/exec` URL and check `server_version: 4`.
7. Tap `Upload now` and check that the diagnostics show `ACK`.
8. To check the new format with real data, use
   `Tracker TS10 > Enviar resumen V4 de la última sesión`. It is a manual action and may resend
   that session; recalculation on its own never sends messages.

## 10. Troubleshooting

### The tracker says the cloud is not configured

Check that:

- The URL starts with `https://`.
- It ends in `/exec`.
- The API key is not empty.
- The Device ID is not empty.

### Apps Script replies `unauthorized`

The `API_KEY` property in Apps Script and the API key entered on the head unit don't match.
Avoid leading or trailing spaces.

### No points appear

Check:

- Precise location permission.
- GPS enabled.
- That the car has actually moved.
- Sufficient GPS accuracy.
- That the tracker is enabled and saved.

The system does not open a session while the vehicle is stationary, to avoid recording GPS
drift.

### The pending counter keeps growing

The head unit is recording correctly but cannot complete the upload. Check the Internet
connection and open the `/exec` URL in a browser, then tap `Upload now`.

The queue is sent session by session, in order. After uploading the last batch of a closed
session, the head unit sends its closure before starting the points of the next one, so Apps
Script does not mistake it for an abandoned open session. An old queue may produce old
summaries while it drains, but the counter should go down and WorkManager schedules automatic
continuations without backing off unless there is a real HTTP error.

### Apps Script replies `partial_batch_detected`

A previous run wrote part of a batch but not its confirmation. Older versions then rejected
every retry of that batch and blocked the queue. Replace the script with the current
[`Code.gs`](Code.gs) and create a new version of the Web App deployment, keeping the same `/exec`
URL. The current version recognises the points already present, inserts only the missing ones
and confirms the whole batch.

### Apps Script replies `point_uid_conflict` or `batch_uid_conflict`

Version 4 of the receiver reconciles, in the same row, a historical point whose
`device_id + session_id + point_seq` already exists with different data. It also no longer
treats a change in batch size as a reason to block the whole queue. The head unit's SQLite
database remains the durable source, a single `point_uid` is kept and the ACK reports
`reconciled_points`.

Replace the whole Apps Script with the current [`Code.gs`](Code.gs) and publish a new version of
the existing deployment. Opening the `/exec` URL must show `server_version: 4`. Then tap
`Upload now`; don't clear the app data or the sheets.

### Telegram messages don't arrive

1. Run `Tracker TS10 > Probar Telegram`.
2. Check `TELEGRAM_ENABLED=true`.
3. Check the token and the chat IDs.
4. Make sure the user has started a chat with the bot.
5. In Apps Script open `Executions` to see the Telegram error.

If the script properties have filled up with `tg_notified:*` entries, run
`Tracker TS10 > Limpiar marcas antiguas de Telegram`. The current version also caps these
markers automatically so they don't grow indefinitely.

### The script was changed but the old behaviour continues

Create and publish a new version of the Web App. The URL on the head unit does not need to
change if you edit the existing deployment.

## 11. Security and privacy

- Coordinates make it possible to reconstruct personal movements.
- Share the spreadsheet only with authorised people.
- Never publish the URL together with the API key.
- Use an API key that differs from your other passwords.
- If it leaks, change `API_KEY` in Apps Script and on the head unit.
- If the Telegram token leaks, revoke it in BotFather.
- On a rooted head unit, a user with root access could read the private preferences; always use
  revocable credentials.

## 12. Backups and removal

Google Sheets keeps the received data. The head unit's SQLite database works as a queue and
keeps recent sessions, but it is not a substitute for a backup of the spreadsheet.

To stop recording:

1. Disable the tracker on the head unit and tap `Save`.
2. To revoke access permanently, change `API_KEY`.
3. To stop Telegram, set `TELEGRAM_ENABLED=false`.

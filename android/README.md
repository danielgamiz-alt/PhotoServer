# PhotoSync (Android app)

Auto-uploads new photos/videos to your PhotoServer when the phone is on WiFi
and the server is reachable. See the [main README](../README.md) for the full
setup guide.

## Building

Open this folder in Android Studio and press Run, or from the command line:

```
gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it via
USB (`adb install app-debug.apk`) or copy it to the phone and open it
(you'll need to allow "install from unknown sources").

## How the sync works

- `SyncWorker` is scheduled by WorkManager every 15 minutes with an
  *unmetered network* constraint — Android only wakes it on WiFi, so it costs
  essentially no battery and never uses mobile data.
- It probes `GET /api/health`; if the server is off, the pass ends quietly.
- `MediaScanner` lists the device's photos/videos; anything not yet recorded
  in the local `UploadLog` database is pending, processed oldest first.
- Each pending item is hashed (SHA-256); `POST /api/check` tells us which the
  server is missing; only those are uploaded (`PUT /api/upload`). Confirmed
  items are recorded in `UploadLog` — the same table that drives the status
  badges in the gallery.
- Interrupted syncs resume where they stopped, and the server dedups by
  content hash, so nothing is ever stored twice.
- Big backlogs run in chunks of 100 items, chaining one-time work requests
  until caught up.

## UI

The main screen is a photo grid (newest first). Each thumbnail carries a badge:
gray cloud-with-arrow = not backed up yet, spinning blue sync = uploading right
now, green cloud-check = safely on the server. Pull down to rescan + sync.
Server setup and sync toggles live under ⋮ → Settings.

## Code map

| File | Role |
|---|---|
| `MainActivity.kt` | Gallery grid with per-photo backup status badges |
| `GalleryAdapter.kt` | RecyclerView adapter (Glide thumbnails + status badge) |
| `SettingsActivity.kt` | Discover/test server, API key, auto-sync toggles, sync log |
| `SyncWorker.kt` | The background sync pass + WorkManager scheduling |
| `UploadLog.kt` | SQLite record of which media items are confirmed on the server |
| `SyncEvents.kt` | In-process StateFlows: sync running, item being uploaded |
| `MediaScanner.kt` | MediaStore queries + file hashing |
| `ServerApi.kt` | HTTP client for the three API endpoints |
| `ServerDiscovery.kt` | UDP broadcast discovery (finds the server, no IP typing) |
| `SyncPrefs.kt` | Persisted settings + last-sync summary |

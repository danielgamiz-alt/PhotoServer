# PhotoSync

Free, private photo backup from your phone to your own computer over home Wi‑Fi. No cloud, no subscription.

```
Phone (PhotoSync app)  ──WiFi──►  Home PC (PhotoSync Server)  ──►  E:\PhotoBackup\
```

## Download

**[danielgamiz-alt.github.io/PhotoSync](https://danielgamiz-alt.github.io/PhotoSync/)** — download page with step-by-step setup for both apps.

| Download | Platform |
|---|---|
| PhotoSync.apk | Android 8.0+ |
| PhotoSync-Server-Setup.exe | Windows 10/11 |

## How it works

- The phone wakes every ~15 min **on Wi‑Fi only** (WorkManager — no battery drain).
- Sends SHA-256 hashes of new photos; server replies with which ones are missing.
- Uploads only new files. No duplicates, even after interrupted syncs or renames.
- Files land in `<storage>/<year>/<month>/` by capture date.
- The **server gallery** tab in the app lets you browse, view, download, and delete photos stored on the server.

---

## Developer setup

### Server (Node.js)

```bash
cd server
node src/index.js
```

Requires Node.js 18+. Zero npm dependencies.

Config is written to `server/config.json` on first run:

```json
{
  "port": 8420,
  "discoveryPort": 38899,
  "storagePath": "E:/PhotoBackup",
  "serverName": "Living room PC",
  "apiKey": ""
}
```

| Setting | Description |
|---|---|
| `storagePath` | Where photos are saved. Forward slashes work on Windows. |
| `serverName` | Name shown on the phone during discovery. |
| `apiKey` | Optional password. Leave `""` for none. If set, phones must enter the same key in Settings. |
| `port` | HTTP port. Default `8420`. |

> When changing `storagePath`, also move the existing backup folder (including `index.json`) to keep deduplication history and avoid re-uploading everything.

**Windows Firewall:** first run prompts to allow Node.js — tick **Private networks**.

```bash
cd server && npm test
```

### Desktop tray app

```bash
cd desktop
npm install
npm start
```

Wraps the server with a Windows tray icon, browser dashboard, and autostart on login.

```bash
npm run package    # portable zip
npm run installer  # Inno Setup .exe (requires Inno Setup installed)
```

### Android app

Open `android/` in Android Studio → **Build → Build APK(s)**.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

---

## API

All endpoints except `/api/health` require `x-api-key` header if `apiKey` is configured.

| Endpoint | Description |
|---|---|
| `GET /api/health` | Server identity + file count. Used as the "is online?" probe. |
| `POST /api/check` | `{"hashes": ["<sha256>", ...]}` → `{"missing": [...]}` |
| `PUT /api/upload` | Raw bytes. Headers: `x-filename` (URL-encoded), `x-taken-at` (epoch ms), `x-hash` (sha256). Returns `201` stored, `200` duplicate. |
| `GET /api/stats` | File count and storage path. |

**Discovery:** broadcast `PHOTOSERVER_DISCOVER_V1` over UDP port `38899`; server replies with name, HTTP port, and id.

---

## Roadmap

- "Free up space" — delete local copies of safely backed-up photos
- Access from outside home via Tailscale
- iOS app

## License

MIT — see [LICENSE](LICENSE).

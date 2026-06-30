# PhotoSync Server Desktop (Windows tray app)

A friendly wrapper around the [PhotoSync Server backend](../server) so you don't have
to keep a terminal window open. It runs the server in the background, shows a
**system-tray icon**, and gives you a **dashboard** in your browser to pick the
backup folder, see status, and change settings.

![green tray icon = running](assets/app.png)

## Run it

**End users don't run it from source.** They download the portable
`PhotoSync-Server-Windows-*.zip` from the GitHub Release, extract it, and
double-click `PhotoSync Server.exe` — no Node, no install. See
[RELEASING.md](../RELEASING.md) and `npm run package` below.

For development:

```
cd desktop
npm install      # one time: installs the tray + notifier helpers
npm start
```

A green icon appears in your system tray (bottom-right, by the clock) and the
dashboard opens in your browser. Closing the browser tab does **not** stop
backups — only **Quit** (from the tray menu or the dashboard) does.

On the **first run**, "Start automatically when I log in" is turned **on by
default** (a per-user registry key, no admin) so backups resume on every
restart without the user touching anything. If they switch it off, it stays
off — it's only auto-enabled once.

## What you get

- **Tray icon** — green when the server is running, gray when stopped. Right-click
  for: Open dashboard · Start/Stop server · Open backup folder · Quit.
- **Dashboard** (`http://127.0.0.1:8421`) — the address to type into phones, a
  **Browse…** button to choose the backup folder (with free-space and
  "drive not connected" warnings), photo count, start/stop, server name + API key,
  **Start automatically when I log in** (on by default after first run),
  **desktop notifications**, and a live activity log.
- **Runs in the background** with no window of its own.
- **Desktop notifications** when photos are backed up or the drive goes missing.

## How it's built

It reuses the exact same server code as the headless CLI —
`../server/src/server.js` is imported as a module. There are two HTTP listeners:

| Listener | Bind | Purpose |
|---|---|---|
| Photo server | `0.0.0.0:8420` | Receives uploads from phones (LAN). |
| Dashboard | `127.0.0.1:8421` | Settings UI — **localhost only**, never exposed to the network, because it can change the storage folder and API key. |

"Start on login" is a per-user Windows registry `Run` key (no admin needed,
fully reversible). The tray icon uses [`systray2`](https://www.npmjs.com/package/systray2)
and notifications use [`node-notifier`](https://www.npmjs.com/package/node-notifier);
both are optional — if they're not installed the app still runs and the dashboard
still works.

## Code map

| File | Role |
|---|---|
| `src/main.js` | Wires server + dashboard + tray + notifications together; lifecycle. |
| `src/control-server.js` | The localhost dashboard HTTP API + static file serving. |
| `src/tray.js` | System-tray icon and menu (systray2). |
| `src/autostart.js` | Start-on-login via the Windows registry Run key. |
| `src/folder-dialog.js` | Native "choose folder" dialog (PowerShell). |
| `src/notifications.js` | Desktop toast notifications (node-notifier). |
| `src/activity-log.js` | In-memory recent-activity ring buffer. |
| `public/` | The dashboard page (HTML/CSS/JS). |
| `assets/generate-icons.js` | Generates the tray/app icons (`npm run build-icons`). |

## Tests

```
npm test
```

Headless integration test of the dashboard API + server (status, start/stop,
settings, moving the storage folder, toggles, activity log, path-traversal
protection). The tray icon and toasts need a real desktop session, so run
`npm start` to see those.

## Packaging for non-technical users

```
npm run package
```

Runs [`build-portable.ps1`](build-portable.ps1), which produces a self-contained
folder at `dist/PhotoSync Server/` — a windowed `PhotoSync Server.exe` launcher, a bundled
`node.exe`, the server + dashboard, and the tray/notifier helpers — plus a
zipped `dist/PhotoSync Server-Windows.zip` to hand out. Recipients extract it and
double-click `PhotoSync Server.exe`; **no Node install, no admin**. The release
workflow runs this on a Windows runner and attaches the zip to each GitHub
Release (see [RELEASING.md](../RELEASING.md)).

It isn't code-signed, so the first launch shows a one-time SmartScreen
"Windows protected your PC → More info → Run anyway". A signing certificate
would remove that, but costs money and isn't needed for friends & family.

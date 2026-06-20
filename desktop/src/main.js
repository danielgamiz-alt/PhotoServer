'use strict';

const path = require('path');
const fs = require('fs');
const fsp = require('fs/promises');
const { exec } = require('child_process');

const { load, save, CONFIG_FILE } = require('../../server/src/config');
const { PhotoServer } = require('../../server/src/server');
const { Storage } = require('../../server/src/storage');
const { ActivityLog } = require('./activity-log');
const { Notifier } = require('./notifications');
const { Thumbnailer } = require('./gallery-store');
const { startControlServer } = require('./control-server');
const { createTray } = require('./tray');
const { pickFolder } = require('./folder-dialog');
const autostart = require('./autostart');

const CONTROL_HOST = '127.0.0.1';
const CONTROL_PORT = 8421;
const CONTROL_URL = `http://${CONTROL_HOST}:${CONTROL_PORT}`;
const ASSETS = path.join(__dirname, '..', 'assets');
const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const PREFS_FILE = path.join(path.dirname(CONFIG_FILE), 'desktop-prefs.json');

const startMinimized = process.argv.includes('--minimized');

// Desktop-only prefs (not part of the shared server config.json).
function loadPrefs() {
  try {
    return JSON.parse(fs.readFileSync(PREFS_FILE, 'utf8'));
  } catch {
    return { notificationsEnabled: true };
  }
}
function savePrefs(prefs) {
  try {
    fs.writeFileSync(PREFS_FILE, JSON.stringify(prefs, null, 2) + '\n');
  } catch {
    /* prefs are best-effort */
  }
}

async function main() {
  const config = load([]); // load config.json (creates it on first run)
  const prefs = loadPrefs();

  // One Storage instance shared by the uploader and the gallery, so deleting a
  // photo updates counts and dedup consistently. Reassigned if the user
  // switches the backup folder.
  let storage = new Storage(config.storagePath);
  await storage.init();

  const photoServer = new PhotoServer(config, { storage });
  const activityLog = new ActivityLog();
  const notifier = new Notifier(prefs.notificationsEnabled !== false);
  const thumbnailer = new Thumbnailer(() => storage.root);

  let tray = null;
  let autostartEnabled = await autostart.isEnabled();

  // ---- wire server events → log, notifications, tray ----------------------
  photoServer.on('log', ({ level, message }) => activityLog.add(level, message));
  photoServer.on('started', () => syncTray());
  photoServer.on('stopped', () => syncTray());

  let storedBatch = 0;
  let storedTimer = null;
  photoServer.on('stored', () => {
    storedBatch++;
    syncTrayThrottled();
    clearTimeout(storedTimer);
    storedTimer = setTimeout(() => {
      if (storedBatch > 0) {
        notifier.notify('Backup', `Backed up ${storedBatch} photo${storedBatch > 1 ? 's' : ''}`);
        storedBatch = 0;
      }
    }, 4000);
  });

  // ---- tray helpers --------------------------------------------------------
  let lastTrayUpdate = 0;
  function syncTray() {
    if (!tray) return;
    const s = photoServer.stats();
    let tip = s.running
      ? `PhotoServer — running · ${s.fileCount} photos`
      : 'PhotoServer — stopped';
    if (tip.length > 120) tip = tip.slice(0, 117) + '…';
    tray.update({ running: s.running, tooltip: tip });
  }
  function syncTrayThrottled() {
    const now = Date.now();
    if (now - lastTrayUpdate > 1000) {
      lastTrayUpdate = now;
      syncTray();
    }
  }

  async function safeStart() {
    try {
      await photoServer.start();
    } catch (e) {
      const msg = e.code === 'EADDRINUSE'
        ? `Port ${config.port} is already in use — change it in Settings.`
        : `Could not start server: ${e.message}`;
      activityLog.add('error', msg);
      notifier.notify('PhotoServer', msg);
    }
    syncTray();
  }

  async function safeRestart() {
    try {
      await photoServer.restart();
    } catch (e) {
      activityLog.add('error', `Could not restart server: ${e.message}`);
      notifier.notify('PhotoServer', `Could not start: ${e.message}`);
    }
    syncTray();
  }

  // ---- status for the dashboard -------------------------------------------
  async function getStatus() {
    const s = photoServer.stats();
    const root = path.parse(s.storagePath).root;
    let disk = null;
    let driveConnected = true;
    if (root && !fs.existsSync(root)) {
      driveConnected = false;
    } else {
      try {
        const st = await fsp.statfs(s.storagePath);
        disk = { freeBytes: st.bsize * st.bavail, totalBytes: st.bsize * st.blocks };
      } catch {
        // folder may not exist yet though the drive is present
        try {
          const st = await fsp.statfs(root);
          disk = { freeBytes: st.bsize * st.bavail, totalBytes: st.bsize * st.blocks };
        } catch {
          driveConnected = false;
        }
      }
    }
    const primary = s.addresses[0] || 'localhost';
    return {
      ...s,
      phoneUrl: `http://${primary}:${s.port}`,
      controlUrl: CONTROL_URL,
      disk,
      driveConnected,
      autostart: autostartEnabled,
      notificationsEnabled: notifier.enabled,
      notificationsAvailable: notifier.available,
      hasTray: tray !== null,
    };
  }

  function persistConfig() {
    save(config);
  }

  function openFolder() {
    const target = fs.existsSync(config.storagePath) ? config.storagePath : path.parse(config.storagePath).root;
    if (target && fs.existsSync(target)) {
      exec(`explorer "${target}"`);
    } else {
      notifier.notify('PhotoServer', 'Backup drive is not connected.');
    }
  }

  function openDashboard() {
    exec(`cmd /c start "" "${CONTROL_URL}"`);
  }

  async function quit() {
    activityLog.add('info', 'Shutting down');
    try {
      await photoServer.stop();
    } catch {
      /* ignore */
    }
    if (tray) tray.kill();
    process.exit(0);
  }

  // ---- control server (also our single-instance lock) ---------------------
  const deps = {
    host: CONTROL_HOST,
    port: CONTROL_PORT,
    publicDir: PUBLIC_DIR,
    getStatus,
    recentActivity: (n) => activityLog.recent(n),
    async applySettings(patch) {
      Object.assign(config, patch);
      persistConfig();
      activityLog.add('info', 'Settings updated');
      if (photoServer.running) await safeRestart();
      return getStatus();
    },
    async setStorage(newPath) {
      config.storagePath = path.resolve(newPath);
      persistConfig();
      // Re-point the shared storage at the new folder (gallery + uploader).
      storage = new Storage(config.storagePath);
      await storage.init();
      photoServer.storage = storage;
      activityLog.add('info', `Storage folder set to ${config.storagePath}`);
      if (photoServer.running) await safeRestart();
      return getStatus();
    },
    pickFolder: () => pickFolder(config.storagePath),
    getStorage: () => storage,
    thumbnailer,
    async deleteMedia(hashes) {
      let deleted = 0;
      for (const h of hashes) {
        if (await storage.remove(h)) {
          await thumbnailer.forget(h);
          deleted++;
        }
      }
      if (deleted > 0) {
        activityLog.add('info', `Deleted ${deleted} item${deleted > 1 ? 's' : ''} from the backup`);
        syncTray();
      }
      return { deleted, fileCount: storage.count() };
    },
    async setServerRunning(shouldRun) {
      if (shouldRun && !photoServer.running) await safeStart();
      else if (!shouldRun && photoServer.running) await photoServer.stop();
      return getStatus();
    },
    async setAutostart(enabled) {
      const ok = await autostart.set(enabled);
      if (ok) autostartEnabled = enabled;
      activityLog.add('info', `Start on login ${enabled ? 'enabled' : 'disabled'}`);
      return getStatus();
    },
    async setNotifications(enabled) {
      notifier.enabled = enabled;
      prefs.notificationsEnabled = enabled;
      savePrefs(prefs);
      return getStatus();
    },
    openFolder,
    onQuit: quit,
  };

  let controlServer;
  try {
    controlServer = await startControlServer(deps);
  } catch (e) {
    if (e.code === 'EADDRINUSE') {
      // Another instance already owns the dashboard port — just surface it.
      console.log('PhotoServer is already running; opening its dashboard.');
      openDashboard();
      process.exit(0);
    }
    throw e;
  }
  activityLog.add('info', `Dashboard ready at ${CONTROL_URL}`);

  // ---- start the photo server + tray --------------------------------------
  await safeStart();

  tray = await createTray({
    runningIcoPath: path.join(ASSETS, 'running.ico'),
    stoppedIcoPath: path.join(ASSETS, 'stopped.ico'),
    running: photoServer.running,
    tooltip: 'PhotoServer',
    handlers: {
      onOpenDashboard: openDashboard,
      onToggleServer: () => deps.setServerRunning(!photoServer.running),
      onOpenFolder: openFolder,
      onQuit: quit,
    },
  });
  if (!tray) {
    activityLog.add('warn', 'Tray unavailable (systray2 not installed) — running headless. Open the dashboard manually.');
    console.log(`Tray not available. Dashboard: ${CONTROL_URL}`);
  }
  syncTray();

  if (!startMinimized) openDashboard();

  process.on('SIGINT', quit);
  process.on('SIGTERM', quit);

  console.log(`PhotoServer desktop running. Dashboard: ${CONTROL_URL}`);
}

main().catch((err) => {
  console.error('fatal:', err);
  process.exit(1);
});

'use strict';

const { execFile } = require('child_process');
const path = require('path');

// "Start on login" is implemented with the per-user Windows registry Run key.
// This is user-level (no admin needed) and fully reversible.
const RUN_KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run';
const VALUE_NAME = 'PhotoServer';

function run(args) {
  return new Promise((resolve) => {
    execFile('reg', args, (err, stdout) => {
      resolve({ ok: !err, stdout: stdout || '' });
    });
  });
}

/** The command Windows should run at login (start hidden in the tray). */
function launchCommand() {
  if (process.pkg) {
    // Packaged single-exe build: relaunch ourselves.
    return `"${process.execPath}" --minimized`;
  }
  // Dev: node + this app's entry point.
  const main = path.join(__dirname, 'main.js');
  return `"${process.execPath}" "${main}" --minimized`;
}

async function isEnabled() {
  const { ok } = await run(['query', RUN_KEY, '/v', VALUE_NAME]);
  return ok;
}

async function enable() {
  return (await run(['add', RUN_KEY, '/v', VALUE_NAME, '/t', 'REG_SZ', '/d', launchCommand(), '/f'])).ok;
}

async function disable() {
  return (await run(['delete', RUN_KEY, '/v', VALUE_NAME, '/f'])).ok;
}

async function set(enabled) {
  return enabled ? enable() : disable();
}

module.exports = { isEnabled, enable, disable, set, launchCommand };

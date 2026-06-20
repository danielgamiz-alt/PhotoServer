'use strict';

const $ = (id) => document.getElementById(id);

let lastStatus = null;
let connected = false;

async function api(path, method = 'GET', body) {
  const opts = { method };
  if (body !== undefined) {
    opts.headers = { 'content-type': 'application/json' };
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${res.status}`);
  }
  return res.json();
}

function fmtBytes(n) {
  if (!n && n !== 0) return '—';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n < 10 && i > 0 ? 1 : 0)} ${u[i]}`;
}

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

// Don't overwrite a field the user is currently editing.
function setInput(el, value) {
  if (document.activeElement !== el) el.value = value;
}

function setConnected(isConnected) {
  connected = isConnected;
  $('offlineBanner').classList.toggle('hidden', isConnected);
  // Disable controls when we can't reach the app.
  document.querySelectorAll('main button, main input').forEach((el) => {
    if (el.id === 'quit') return;
    el.disabled = !isConnected;
  });
  if (!isConnected) {
    const pill = $('statusPill');
    pill.textContent = 'App not running';
    pill.className = 'pill pill-gray';
  }
}

function render(s) {
  lastStatus = s;
  setConnected(true);

  const pill = $('statusPill');
  pill.textContent = s.running ? 'Running' : 'Stopped';
  pill.className = 'pill ' + (s.running ? 'pill-green' : 'pill-gray');

  $('phoneUrl').textContent = s.phoneUrl;
  $('storagePath').textContent = s.storagePath;
  $('photoCount').textContent = s.fileCount.toLocaleString();
  $('serverState').textContent = s.running ? 'Running' : 'Stopped';
  $('toggleServer').textContent = s.running ? 'Stop server' : 'Start server';

  $('driveWarning').classList.toggle('hidden', s.driveConnected);

  if (s.disk) {
    const used = s.disk.totalBytes - s.disk.freeBytes;
    const pct = s.disk.totalBytes ? (used / s.disk.totalBytes) * 100 : 0;
    $('diskUsed').style.width = pct.toFixed(1) + '%';
    $('diskText').textContent = `${fmtBytes(s.disk.freeBytes)} free of ${fmtBytes(s.disk.totalBytes)}`;
  } else {
    $('diskUsed').style.width = '0%';
    $('diskText').textContent = s.driveConnected ? '—' : 'Drive not connected';
  }

  setInput($('serverName'), s.name);
  setInput($('port'), s.port);
  $('apiKey').placeholder = s.requiresApiKey ? '•••••• (set — type to change)' : 'leave blank for none';

  $('autostart').checked = s.autostart;
  $('notifications').checked = s.notificationsEnabled;
  $('notifications').disabled = !s.notificationsAvailable;
  $('notifyUnavailable').classList.toggle('hidden', s.notificationsAvailable);
}

function renderActivity(entries) {
  const ul = $('activity');
  if (!entries.length) {
    ul.innerHTML = '<li class="muted small">No activity yet.</li>';
    return;
  }
  ul.innerHTML = entries
    .map((e) => `<li class="lvl-${e.level}"><span class="t">${fmtTime(e.time)}</span><span class="m"></span></li>`)
    .join('');
  ul.querySelectorAll('li').forEach((li, i) => {
    li.querySelector('.m').textContent = entries[i].message; // avoid HTML injection
  });
}

async function refresh() {
  try {
    render(await api('/api/status'));
  } catch {
    setConnected(false);
  }
}

async function refreshActivity() {
  try {
    const { entries } = await api('/api/activity?limit=60');
    renderActivity(entries);
  } catch {
    /* handled by refresh() */
  }
}

function flash(msg) {
  const hint = $('saveHint');
  hint.textContent = msg;
  setTimeout(() => { hint.textContent = ''; }, 2500);
}

// Wrap a click handler so failures are shown, not swallowed.
function action(fn) {
  return async (ev) => {
    try {
      await fn(ev);
    } catch (e) {
      flash(e.message || 'Something went wrong');
    }
  };
}

// ---- actions ---------------------------------------------------------------
$('toggleServer').onclick = action(async () => {
  const running = lastStatus && lastStatus.running;
  render(await api('/api/server', 'POST', { action: running ? 'stop' : 'start' }));
  refreshActivity();
});

$('changeFolder').onclick = action(async () => {
  const s = await api('/api/pick-folder', 'POST');
  if (!s.cancelled) { render(s); flash('Backup folder updated'); }
  refreshActivity();
});

$('openFolder').onclick = action(() => api('/api/open-folder', 'POST'));

$('saveSettings').onclick = action(async () => {
  const patch = { serverName: $('serverName').value, port: $('port').value };
  const apiKey = $('apiKey').value;
  if (apiKey !== '') patch.apiKey = apiKey;
  render(await api('/api/settings', 'POST', patch));
  $('apiKey').value = '';
  flash('Saved');
});

$('autostart').onchange = action(async (e) => {
  render(await api('/api/autostart', 'POST', { enabled: e.target.checked }));
});

$('notifications').onchange = action(async (e) => {
  render(await api('/api/notifications', 'POST', { enabled: e.target.checked }));
});

$('copyUrl').onclick = () => {
  if (lastStatus) navigator.clipboard?.writeText(lastStatus.phoneUrl).then(() => flash('Copied'));
};

$('quit').onclick = action(async () => {
  if (!confirm('Quit PhotoServer? Backups will stop until you start it again.')) return;
  await api('/api/quit', 'POST').catch(() => {});
  document.body.innerHTML = '<main><section class="card"><h2>PhotoServer has quit</h2>' +
    '<p class="muted">You can close this tab. Start the app again to resume backups.</p></section></main>';
});

// ---- polling ---------------------------------------------------------------
refresh();
refreshActivity();
setInterval(refresh, 2000);
setInterval(refreshActivity, 3000);

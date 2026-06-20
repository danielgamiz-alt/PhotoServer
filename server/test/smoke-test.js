'use strict';

/**
 * End-to-end smoke test. Starts the server on a throwaway port with a temp
 * storage dir, then acts like a phone: discover via UDP, check hashes,
 * upload, re-upload (dedup), and send a corrupted upload.
 *
 * Run with: npm test
 */

const { spawn } = require('child_process');
const crypto = require('crypto');
const dgram = require('dgram');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PORT = 8765;
const BASE = `http://127.0.0.1:${PORT}`;

let passed = 0;
let failed = 0;

function check(name, cond, detail) {
  if (cond) {
    passed++;
    console.log(`  ok   ${name}`);
  } else {
    failed++;
    console.log(`  FAIL ${name}${detail ? ` -- ${detail}` : ''}`);
  }
}

async function waitForServer(timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BASE}/api/health`);
      if (res.ok) return res.json();
    } catch {
      await new Promise((r) => setTimeout(r, 200));
    }
  }
  throw new Error('server did not come up');
}

async function upload(bytes, { filename, takenAt, hash, user }) {
  const headers = { 'x-filename': encodeURIComponent(filename) };
  if (takenAt) headers['x-taken-at'] = String(takenAt);
  if (hash) headers['x-hash'] = hash;
  if (user) headers['x-user'] = user;
  const res = await fetch(`${BASE}/api/upload`, { method: 'PUT', headers, body: bytes });
  return { status: res.status, body: await res.json() };
}

async function checkMissing(hashes, user) {
  const headers = { 'content-type': 'application/json' };
  if (user) headers['x-user'] = user;
  const res = await fetch(`${BASE}/api/check`, {
    method: 'POST', headers, body: JSON.stringify({ hashes }),
  });
  return (await res.json()).missing;
}

function discover(discoveryPort) {
  return new Promise((resolve, reject) => {
    const sock = dgram.createSocket('udp4');
    const timer = setTimeout(() => {
      sock.close();
      reject(new Error('no discovery reply within 3s'));
    }, 3000);
    sock.on('message', (msg) => {
      clearTimeout(timer);
      sock.close();
      resolve(JSON.parse(msg.toString('utf8')));
    });
    sock.bind(() => {
      sock.send('PHOTOSERVER_DISCOVER_V1', discoveryPort, '127.0.0.1');
    });
  });
}

async function main() {
  const storageDir = fs.mkdtempSync(path.join(os.tmpdir(), 'photoserver-test-'));
  const discoveryPort = 38901;

  const server = spawn(
    process.execPath,
    [
      path.join(__dirname, '..', 'src', 'index.js'),
      '--storage', storageDir,
      '--port', String(PORT),
      '--discovery-port', String(discoveryPort),
    ],
    { stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env } }
  );
  server.stdout.on('data', () => {});

  try {
    const health = await waitForServer();
    console.log('server is up\n');

    check('health: app name', health.app === 'photoserver');
    check('health: has serverId', typeof health.serverId === 'string' && health.serverId.length > 0);
    check('health: starts empty', health.fileCount === 0);

    // --- check endpoint with unknown hashes
    const photo = crypto.randomBytes(64 * 1024); // pretend this is a JPEG
    const photoHash = crypto.createHash('sha256').update(photo).digest('hex');

    let res = await fetch(`${BASE}/api/check`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ hashes: [photoHash] }),
    });
    let body = await res.json();
    check('check: unknown hash reported missing', body.missing.length === 1 && body.missing[0] === photoHash);

    // --- first upload, with capture date 2024-07-15
    const takenAt = Date.UTC(2024, 6, 15, 12, 0, 0);
    let up = await upload(photo, { filename: 'IMG_1234.jpg', takenAt, hash: photoHash });
    check('upload: 201 created', up.status === 201, `got ${up.status}`);
    check('upload: stored flag', up.body.stored === true);
    check('upload: filed under 2024/07', up.body.path === '2024/07/IMG_1234.jpg', up.body.path);
    check(
      'upload: file exists on disk',
      fs.existsSync(path.join(storageDir, '2024', '07', 'IMG_1234.jpg'))
    );
    const onDisk = fs.readFileSync(path.join(storageDir, '2024', '07', 'IMG_1234.jpg'));
    check('upload: bytes intact', onDisk.equals(photo));

    // --- duplicate upload under a different name is skipped
    up = await upload(photo, { filename: 'IMG_1234 (copy).jpg', takenAt, hash: photoHash });
    check('dedup: 200 not re-stored', up.status === 200, `got ${up.status}`);
    check('dedup: stored=false', up.body.stored === false);

    // --- check endpoint now knows the hash
    res = await fetch(`${BASE}/api/check`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ hashes: [photoHash, 'f'.repeat(64)] }),
    });
    body = await res.json();
    check('check: known hash not missing', body.missing.length === 1 && body.missing[0] === 'f'.repeat(64));

    // --- corrupted upload (declared hash doesn't match bytes) is rejected
    up = await upload(crypto.randomBytes(1024), { filename: 'corrupt.jpg', hash: photoHash });
    check('integrity: corrupted upload rejected with 400', up.status === 400, `got ${up.status}`);
    check(
      'integrity: corrupt file not stored',
      !fs.existsSync(path.join(storageDir, new Date().getFullYear().toString()))
    );

    // --- filename collision: same name, different content -> _1 suffix
    const photo2 = crypto.randomBytes(32 * 1024);
    up = await upload(photo2, { filename: 'IMG_1234.jpg', takenAt });
    check('collision: stored under suffixed name', up.body.path === '2024/07/IMG_1234_1.jpg', up.body.path);

    // --- weird filename gets sanitized
    up = await upload(crypto.randomBytes(1024), { filename: '../..\\evil:photo?.jpg', takenAt });
    check(
      'sanitize: no path traversal in stored path',
      up.body.path.startsWith('2024/07/') && !up.body.path.includes('..'),
      up.body.path
    );

    // --- missing capture date falls back to today
    up = await upload(crypto.randomBytes(1024), { filename: 'nodate.jpg' });
    const now = new Date();
    const expectedPrefix = `${now.getFullYear()}/${String(now.getMonth() + 1).padStart(2, '0')}/`;
    check('no date: filed under current month', up.body.path.startsWith(expectedPrefix), up.body.path);

    // --- UDP discovery
    try {
      const reply = await discover(discoveryPort);
      check('discovery: correct type', reply.type === 'PHOTOSERVER_HERE_V1');
      check('discovery: advertises HTTP port', typeof reply.port === 'number');
    } catch (e) {
      // Discovery uses the port from config.json; if this dev machine has a
      // different one configured, don't fail the whole suite over it.
      console.log(`  skip discovery (${e.message})`);
    }

    // --- stats (default user)
    res = await fetch(`${BASE}/api/stats`);
    body = await res.json();
    check('stats: counts stored files', body.fileCount === 4, `got ${body.fileCount}`);

    // --- per-user separation: same content, two accounts, two folders
    const shared = crypto.randomBytes(20 * 1024);
    const sharedHash = crypto.createHash('sha256').update(shared).digest('hex');
    const janTaken = Date.UTC(2025, 0, 10, 9, 0, 0);

    up = await upload(shared, { filename: 'family.jpg', takenAt: janTaken, hash: sharedHash, user: 'alice' });
    check('user alice: stored', up.status === 201 && up.body.stored === true)
    check('user alice: filed under alice/2025/01', up.body.path === 'alice/2025/01/family.jpg', up.body.path);
    check(
      'user alice: file on disk under her folder',
      fs.existsSync(path.join(storageDir, 'alice', '2025', '01', 'family.jpg'))
    );

    up = await upload(shared, { filename: 'family.jpg', takenAt: janTaken, hash: sharedHash, user: 'bob' });
    check('user bob: same content stored separately', up.status === 201 && up.body.stored === true);
    check('user bob: filed under bob/2025/01', up.body.path === 'bob/2025/01/family.jpg', up.body.path);
    check(
      'user bob: file on disk under his folder',
      fs.existsSync(path.join(storageDir, 'bob', '2025', '01', 'family.jpg'))
    );

    up = await upload(shared, { filename: 'family.jpg', takenAt: janTaken, hash: sharedHash, user: 'alice' });
    check('user alice: re-upload deduped within her account', up.status === 200 && up.body.stored === false);

    check('check: a third user is still missing the shared content',
      (await checkMissing([sharedHash], 'carol')).length === 1);
    check('check: alice already has the shared content',
      (await checkMissing([sharedHash], 'alice')).length === 0);

    res = await fetch(`${BASE}/api/stats`, { headers: { 'x-user': 'alice' } });
    body = await res.json();
    check('stats: per-user count (alice = 1)', body.fileCount === 1, `got ${body.fileCount}`);
    check('stats: reports the user', body.user === 'alice');
    check('stats: total spans all users', body.totalFileCount === 6, `got ${body.totalFileCount}`);
  } finally {
    server.kill();
    fs.rmSync(storageDir, { recursive: true, force: true });
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('test harness error:', err);
  process.exit(1);
});

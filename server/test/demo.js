'use strict';

/**
 * Live demo: pretends to be a phone. Creates two tiny real PNG images,
 * then runs the exact protocol the Android app uses:
 *   1. GET  /api/health  - is the server online?
 *   2. POST /api/check   - which of my photos is it missing?
 *   3. PUT  /api/upload  - upload those
 * and finally re-uploads one to show deduplication.
 *
 * Usage: node test/demo.js [serverUrl]   (default http://127.0.0.1:8420)
 */

const crypto = require('crypto');
const zlib = require('zlib');

const BASE = process.argv[2] || 'http://127.0.0.1:8420';

// --- build a real, viewable PNG of a solid color (8x8) ---------------------
function makePng(r, g, b) {
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
  };

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(8, 0); // width
  ihdr.writeUInt32BE(8, 4); // height
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 2;  // color type: truecolor

  // 8 rows, each: filter byte 0 + 8 RGB pixels
  const raw = Buffer.alloc(8 * (1 + 8 * 3));
  for (let y = 0; y < 8; y++) {
    const row = y * 25;
    for (let x = 0; x < 8; x++) {
      raw[row + 1 + x * 3] = r;
      raw[row + 2 + x * 3] = g;
      raw[row + 3 + x * 3] = b;
    }
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

let crcTable;
function crc32(buf) {
  if (!crcTable) {
    crcTable = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c;
    }
  }
  let crc = 0xffffffff;
  for (const byte of buf) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

// --- the phone's protocol ---------------------------------------------------
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

async function upload(bytes, filename, takenAt) {
  const res = await fetch(`${BASE}/api/upload`, {
    method: 'PUT',
    headers: {
      'x-filename': encodeURIComponent(filename),
      'x-taken-at': String(takenAt),
      'x-hash': sha256(bytes),
    },
    body: bytes,
  });
  return { status: res.status, ...(await res.json()) };
}

async function main() {
  console.log(`\n=== PhotoSync demo (pretending to be your phone) ===\n`);

  // 1. Is the server online?
  const health = await (await fetch(`${BASE}/api/health`)).json();
  console.log(`1. Server online: "${health.name}" (${health.fileCount} files stored)\n`);

  // Two "photos from the camera"
  const photos = [
    { name: 'IMG_20260608_beach.png', bytes: makePng(30, 144, 255), takenAt: Date.UTC(2026, 5, 8, 15, 30) },
    { name: 'IMG_20251224_xmas.png', bytes: makePng(220, 40, 40), takenAt: Date.UTC(2025, 11, 24, 19, 0) },
  ];

  // 2. Which ones does the server not have yet?
  const checkRes = await fetch(`${BASE}/api/check`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ hashes: photos.map((p) => sha256(p.bytes)) }),
  });
  const { missing } = await checkRes.json();
  console.log(`2. Server is missing ${missing.length} of my ${photos.length} photos\n`);

  // 3. Upload the missing ones
  for (const p of photos) {
    if (!missing.includes(sha256(p.bytes))) {
      console.log(`3. ${p.name}: already on server, skipping`);
      continue;
    }
    const r = await upload(p.bytes, p.name, p.takenAt);
    console.log(`3. ${p.name}: HTTP ${r.status} -> stored as ${r.path}`);
  }

  // 4. Re-upload the first one under a different name: dedup should kick in
  const again = await upload(photos[0].bytes, 'IMG_copy_of_beach.png', photos[0].takenAt);
  console.log(`\n4. Re-upload same photo as "IMG_copy_of_beach.png": HTTP ${again.status}, stored=${again.stored}`);
  console.log(`   (server recognized the content and did NOT store a duplicate)\n`);

  const stats = await (await fetch(`${BASE}/api/stats`)).json();
  console.log(`Server now holds ${stats.fileCount} files in ${stats.storagePath}`);
}

main().catch((err) => {
  console.error('Demo failed:', err.message);
  console.error(`Is the server running? Start it with: node src/index.js`);
  process.exit(1);
});

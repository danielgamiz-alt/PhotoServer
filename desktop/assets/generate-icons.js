'use strict';

// Generates the tray/app icons from code (no binary assets to ship in git):
//   running.ico  - green camera dot (server running)
//   stopped.ico  - gray camera dot  (server stopped)
//   app.png      - app icon for notification toasts
//
// The .ico files embed classic 32-bit BMP/DIB images (NOT PNG-in-ICO), which
// is what the Windows tray (Shell_NotifyIcon) reliably renders at 16/32 px.
//
// Run with: npm run build-icons

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------------------------------------------------------------------------
// Artwork: a colored disc with a white "lens" ring (a little camera).
// Returns a top-down RGBA buffer.
// ---------------------------------------------------------------------------
function drawIcon(size, accent) {
  const rgba = Buffer.alloc(size * size * 4);
  const c = (size - 1) / 2;
  const rOuter = size * 0.46;
  const rLensOuter = size * 0.27;
  const rLensInner = size * 0.15;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x - c, y - c);
      const cover = Math.max(0, Math.min(1, rOuter - d + 0.5)); // soft edge
      const i = (y * size + x) * 4;
      if (cover <= 0) {
        rgba[i] = rgba[i + 1] = rgba[i + 2] = rgba[i + 3] = 0;
        continue;
      }
      const inLensRing = d <= rLensOuter && d >= rLensInner;
      const [r, g, b] = inLensRing ? [255, 255, 255] : accent;
      rgba[i] = r;
      rgba[i + 1] = g;
      rgba[i + 2] = b;
      rgba[i + 3] = Math.round(255 * cover);
    }
  }
  return rgba;
}

// ---------------------------------------------------------------------------
// One ICO image as a 32-bit BMP/DIB (BITMAPINFOHEADER + BGRA + AND mask).
// ---------------------------------------------------------------------------
function bmpIconImage(size, rgba) {
  const header = Buffer.alloc(40);
  header.writeUInt32LE(40, 0); // biSize
  header.writeInt32LE(size, 4); // biWidth
  header.writeInt32LE(size * 2, 8); // biHeight (XOR image + AND mask)
  header.writeUInt16LE(1, 12); // biPlanes
  header.writeUInt16LE(32, 14); // biBitCount
  header.writeUInt32LE(0, 16); // biCompression = BI_RGB

  // XOR bitmap: 32bpp BGRA, bottom-up.
  const xor = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    const dstY = size - 1 - y;
    for (let x = 0; x < size; x++) {
      const s = (y * size + x) * 4;
      const d = (dstY * size + x) * 4;
      xor[d] = rgba[s + 2]; // B
      xor[d + 1] = rgba[s + 1]; // G
      xor[d + 2] = rgba[s]; // R
      xor[d + 3] = rgba[s + 3]; // A
    }
  }

  // AND mask: 1 bit/pixel, rows padded to 4 bytes. 1 = transparent.
  const maskRowBytes = Math.ceil(size / 32) * 4;
  const and = Buffer.alloc(maskRowBytes * size, 0);
  for (let y = 0; y < size; y++) {
    const dstY = size - 1 - y;
    for (let x = 0; x < size; x++) {
      const a = rgba[(y * size + x) * 4 + 3];
      if (a === 0) and[dstY * maskRowBytes + (x >> 3)] |= 0x80 >> (x & 7);
    }
  }

  return Buffer.concat([header, xor, and]);
}

function buildIco(images) {
  const count = images.length;
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // type: icon
  header.writeUInt16LE(count, 4);

  const entries = [];
  let offset = 6 + count * 16;
  for (const img of images) {
    const e = Buffer.alloc(16);
    e[0] = img.size >= 256 ? 0 : img.size;
    e[1] = img.size >= 256 ? 0 : img.size;
    e[2] = 0; // palette
    e[3] = 0; // reserved
    e.writeUInt16LE(1, 4); // planes
    e.writeUInt16LE(32, 6); // bpp
    e.writeUInt32LE(img.data.length, 8);
    e.writeUInt32LE(offset, 12);
    offset += img.data.length;
    entries.push(e);
  }
  return Buffer.concat([header, ...entries, ...images.map((i) => i.data)]);
}

function ico(accent) {
  // Include 16 and 32 px so the tray picks a crisp native size.
  return buildIco(
    [16, 32].map((size) => ({ size, data: bmpIconImage(size, drawIcon(size, accent)) }))
  );
}

// ---------------------------------------------------------------------------
// PNG (RGBA) for the notification toast icon.
// ---------------------------------------------------------------------------
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
  for (const b of buf) crc = crcTable[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function encodePng(size, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 6; // RGBA
  const stride = size * 4;
  const raw = Buffer.alloc(size * (1 + stride));
  for (let y = 0; y < size; y++) {
    rgba.copy(raw, y * (1 + stride) + 1, y * stride, y * stride + stride);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

const GREEN = [46, 160, 67];
const GRAY = [140, 146, 153];
const out = __dirname;

fs.writeFileSync(path.join(out, 'running.ico'), ico(GREEN));
fs.writeFileSync(path.join(out, 'stopped.ico'), ico(GRAY));
fs.writeFileSync(path.join(out, 'app.png'), encodePng(64, drawIcon(64, GREEN)));

// Multi-size launcher/app icon (used as the PhotoServer.exe icon).
fs.writeFileSync(
  path.join(out, 'app.ico'),
  buildIco([16, 32, 48].map((size) => ({ size, data: bmpIconImage(size, drawIcon(size, GREEN)) })))
);

console.log('Wrote running.ico, stopped.ico, app.ico, app.png to', out);

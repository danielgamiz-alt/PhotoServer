'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');

// Optional fast thumbnails. If `sharp` isn't installed, the gallery falls back
// to serving (browser-scaled) originals — so this is a pure enhancement.
let sharp = null;
try {
  sharp = require('sharp');
} catch {
  sharp = null;
}

const THUMB_DIR = '.thumbs';
const THUMB_SIZE = 400;

class Thumbnailer {
  /** getRoot() returns the current storage root (changes when the user
   *  switches the backup folder). */
  constructor(getRoot) {
    this.getRoot = getRoot;
  }

  get available() {
    return sharp !== null;
  }

  _thumbPath(hash) {
    return path.join(this.getRoot(), THUMB_DIR, `${hash}.jpg`);
  }

  /**
   * Returns the path to a cached JPEG thumbnail for an image, generating it on
   * first request. Returns null when thumbnails aren't possible (no sharp, a
   * video, or a decode failure) so the caller can serve the original instead.
   */
  async thumb(hash, absSource, type) {
    if (!sharp || type === 'video') return null;
    const out = this._thumbPath(hash);
    try {
      if (fs.existsSync(out)) return out;
      await fsp.mkdir(path.dirname(out), { recursive: true });
      await sharp(absSource)
        .rotate() // honour EXIF orientation
        .resize(THUMB_SIZE, THUMB_SIZE, { fit: 'cover', position: 'attention' })
        .jpeg({ quality: 72 })
        .toFile(out);
      return out;
    } catch {
      return null;
    }
  }

  async forget(hash) {
    await fsp.unlink(this._thumbPath(hash)).catch(() => {});
  }
}

const MIME = {
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.heic': 'image/heic',
  '.heif': 'image/heif',
  '.bmp': 'image/bmp',
  '.mp4': 'video/mp4',
  '.mov': 'video/quicktime',
  '.m4v': 'video/x-m4v',
  '.webm': 'video/webm',
  '.mkv': 'video/x-matroska',
  '.3gp': 'video/3gpp',
  '.avi': 'video/x-msvideo',
};

function mimeFor(name) {
  return MIME[path.extname(name).toLowerCase()] || 'application/octet-stream';
}

module.exports = { Thumbnailer, mimeFor, THUMB_DIR };

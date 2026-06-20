'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const crypto = require('crypto');

// A recycle bin for the backup. Deleting a photo MOVES it into <root>/.trash/
// instead of erasing it; it can be restored, or it's purged automatically after
// the retention window. This lives entirely on the desktop side and only uses
// Storage's public methods, so it never touches the (multi-user) index format.

const TRASH_DIR = '.trash';
const RETENTION_MS = 30 * 24 * 60 * 60 * 1000; // 30 days, Google-Photos-style

class TrashStore {
  constructor(getRoot) {
    this.getRoot = getRoot;
    this.index = {}; // id -> { id, hash, user, name, size, takenAt, type, deletedAt, file }
    this.loadedRoot = null;
  }

  _dir() {
    return path.join(this.getRoot(), TRASH_DIR);
  }
  _indexFile() {
    return path.join(this._dir(), 'index.json');
  }

  async _ensureLoaded() {
    const root = this.getRoot();
    if (this.loadedRoot === root) return;
    await fsp.mkdir(this._dir(), { recursive: true });
    try {
      this.index = JSON.parse(await fsp.readFile(this._indexFile(), 'utf8'));
    } catch {
      this.index = {};
    }
    this.loadedRoot = root;
    await this.purge(); // drop anything past the retention window
  }

  async init() {
    await this._ensureLoaded();
  }

  async _save() {
    const tmp = this._indexFile() + '.tmp';
    await fsp.writeFile(tmp, JSON.stringify(this.index, null, 1));
    await fsp.rename(tmp, this._indexFile());
  }

  /** Moves one stored file (one user's copy) into the trash. */
  async add({ hash, user, relPath, name, size, takenAt, type }) {
    await this._ensureLoaded();
    const id = crypto.randomUUID();
    const ext = path.extname(name) || '';
    const src = path.join(this.getRoot(), relPath);
    const file = id + ext;
    const dest = path.join(this._dir(), file);
    try {
      await fsp.rename(src, dest);
    } catch {
      // e.g. cross-device — fall back to copy+unlink.
      await fsp.copyFile(src, dest);
      await fsp.unlink(src).catch(() => {});
    }
    this.index[id] = { id, hash, user, name, size, takenAt, type, deletedAt: Date.now(), file };
    await this._save();
    return id;
  }

  list() {
    return Object.values(this.index).sort((a, b) => b.deletedAt - a.deletedAt);
  }
  get(id) {
    return this.index[id] || null;
  }
  absFile(id) {
    const e = this.index[id];
    return e ? path.join(this._dir(), e.file) : null;
  }
  count() {
    return Object.keys(this.index).length;
  }

  /** Permanently removes a trashed item (file + record). */
  async deleteForever(id) {
    await this._ensureLoaded();
    const e = this.index[id];
    if (!e) return false;
    await fsp.unlink(path.join(this._dir(), e.file)).catch(() => {});
    delete this.index[id];
    await this._save();
    return true;
  }

  async emptyAll() {
    await this._ensureLoaded();
    for (const id of Object.keys(this.index)) await this.deleteForever(id);
  }

  /** Drops items older than the retention window. */
  async purge(maxAgeMs = RETENTION_MS) {
    const now = Date.now();
    let changed = false;
    for (const [id, e] of Object.entries(this.index)) {
      if (now - e.deletedAt > maxAgeMs) {
        await fsp.unlink(path.join(this._dir(), e.file)).catch(() => {});
        delete this.index[id];
        changed = true;
      }
    }
    if (changed) await this._save();
  }
}

module.exports = { TrashStore, RETENTION_MS };

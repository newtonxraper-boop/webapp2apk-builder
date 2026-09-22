'use strict';

/**
 * Tiny JSON key/value store for settings (desktop counterpart of the
 * Android build's SharedPreferences). Writes are atomic (temp file + rename)
 * so a crash mid-write can never leave a half-written settings file.
 */

const fs = require('fs');
const path = require('path');

class Store {
  constructor(file) {
    this.file = file;
    this.data = {};
    this._load();
  }

  _load() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      this.data = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (e) {
      this.data = {};
    }
  }

  get(key, fallback) {
    return Object.prototype.hasOwnProperty.call(this.data, key) ? this.data[key] : fallback;
  }

  has(key) {
    return Object.prototype.hasOwnProperty.call(this.data, key);
  }

  set(key, value) {
    this.data[key] = value;
    this._save();
  }

  delete(key) {
    if (this.has(key)) {
      delete this.data[key];
      this._save();
    }
  }

  _save() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(this.data), 'utf8');
      fs.renameSync(tmp, this.file);
    } catch (e) {
      // Best-effort, same as SharedPreferences.apply(): never crash over a
      // failed settings write.
    }
  }
}

module.exports = { Store };

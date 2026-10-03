'use strict';

/**
 * In-app updater (Electron-free so it can be unit-tested).
 *
 * Downloads the newer installer INSIDE the app (no browser, so no "isn't
 * commonly downloaded" warning), checks it, then installs it:
 *
 *   Windows  silent NSIS install ("/S"). Happens either when the user clicks
 *            "Restart to update", or automatically the next time the app is
 *            closed. The installer keeps the same install folder and settings.
 *   macOS    the downloaded .dmg / .zip is opened for the user (Mac apps that
 *            aren't signed by Apple can't replace themselves silently).
 *
 * Statuses: 'idle' -> 'downloading' -> 'ready'   (or 'error')
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PREFIX = 'update-';

function safeName(url, platform) {
  let base = 'app';
  try { base = path.basename(new URL(url).pathname) || base; } catch (e) { /* keep default */ }
  base = base.replace(/[^A-Za-z0-9._-]/g, '_').slice(-80);
  const ext = platform === 'win32' ? '.exe' : (/\.zip$/i.test(base) ? '.zip' : '.dmg');
  if (!base.toLowerCase().endsWith(ext)) base += ext;
  return PREFIX + base;
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    const s = fs.createReadStream(file);
    s.on('data', (c) => h.update(c));
    s.on('error', reject);
    s.on('end', () => resolve(h.digest('hex')));
  });
}

function startsWith(file, bytes) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(bytes.length);
    fs.readSync(fd, buf, 0, bytes.length, 0);
    return buf.equals(Buffer.from(bytes));
  } finally { fs.closeSync(fd); }
}

class Updater {
  /**
   * @param {object} o
   * @param {string} o.platform   'win32' | 'darwin'
   * @param {string} o.dir        folder for downloaded installers
   * @param {function} o.download (url, destPath, onProgress(0..1)) => Promise
   * @param {function} o.spawn    (file, args) => void   (detached, fire and forget)
   * @param {function} o.openPath (file) => Promise<string>   (macOS)
   * @param {function} o.quit     () => void
   * @param {function} [o.onChange]  called whenever status/progress changes
   * @param {function} [o.onInstallLaunched] (url) => void
   */
  constructor(o) {
    this.platform = o.platform;
    this.dir = o.dir;
    this.downloadFn = o.download;
    this.spawnFn = o.spawn;
    this.openPathFn = o.openPath;
    this.quitFn = o.quit;
    this.onChange = o.onChange || (() => {});
    this.onInstallLaunched = o.onInstallLaunched || (() => {});
    this.status = 'idle';
    this.progress = 0;
    this.file = null;
    this.url = null;
    this.launched = false;
    this.error = null;
    this._job = null;
  }

  _set(status, extra) {
    this.status = status;
    if (extra) Object.assign(this, extra);
    try { this.onChange(); } catch (e) { /* ignore */ }
  }

  /** Remove installers left over from earlier updates (keeps `keep`). */
  cleanup(keep) {
    try {
      for (const f of fs.readdirSync(this.dir)) {
        if (!f.startsWith(PREFIX)) continue;
        const full = path.join(this.dir, f);
        if (keep && full === keep) continue;
        try { fs.unlinkSync(full); } catch (e) { /* in use - leave it */ }
      }
    } catch (e) { /* folder not there yet */ }
  }

  /** Download + verify. Safe to call repeatedly for the same url. */
  start(info) {
    if (this.url === info.url && (this.status === 'downloading' || this.status === 'ready')) {
      return this._job || Promise.resolve(this.status === 'ready');
    }
    this.url = info.url;
    this.launched = false;
    this._job = this._run(info).catch((e) => {
      this._set('error', { error: String(e && e.message || e), progress: 0 });
      return false;
    });
    return this._job;
  }

  async _run(info) {
    fs.mkdirSync(this.dir, { recursive: true });
    const finalPath = path.join(this.dir, safeName(info.url, this.platform));
    const tmpPath = finalPath + '.part';
    this.cleanup(null);
    this._set('downloading', { progress: 0, error: null, file: null });

    await this.downloadFn(info.url, tmpPath, (p) => {
      this.progress = Math.max(0, Math.min(1, p));
      try { this.onChange(); } catch (e) { /* ignore */ }
    });

    try {
      const size = fs.statSync(tmpPath).size;
      if (!(size > 0)) throw new Error('Empty download');
      if (info.size && Number(info.size) !== size) throw new Error('Download is incomplete');
      if (info.sha256) {
        const got = await sha256File(tmpPath);
        if (got.toLowerCase() !== String(info.sha256).toLowerCase()) throw new Error('Download failed its integrity check');
      }
      if (this.platform === 'win32' && !startsWith(tmpPath, [0x4d, 0x5a])) throw new Error('Not a Windows installer');
      fs.renameSync(tmpPath, finalPath);
    } catch (e) {
      try { fs.unlinkSync(tmpPath); } catch (e2) { /* ignore */ }
      throw e;
    }
    this._set('ready', { progress: 1, file: finalPath });
    return true;
  }

  _runInstaller(args) {
    if (this.launched || !this.file) return false;
    this.launched = true;
    try { this.onInstallLaunched(this.url); } catch (e) { /* ignore */ }
    this.spawnFn(this.file, args);
    return true;
  }

  /** "Restart to update" - Windows installs and relaunches, macOS opens the file. */
  async installNow() {
    if (this.status !== 'ready' || !this.file) return false;
    if (this.platform === 'win32') {
      if (!this._runInstaller(['/S', '--updated', '--force-run'])) return false;
      this.quitFn();
      return true;
    }
    const err = await this.openPathFn(this.file);
    return !err;
  }

  /** Called as the app closes: finish a pending Windows update quietly. */
  installOnQuit() {
    if (this.platform !== 'win32' || this.status !== 'ready') return false;
    return this._runInstaller(['/S', '--updated']);
  }
}

module.exports = { Updater, safeName, sha256File };

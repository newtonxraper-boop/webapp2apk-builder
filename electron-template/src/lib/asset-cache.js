'use strict';

/**
 * On-disk cache of the small same-host resources a cached page needs to look
 * right offline: stylesheets, scripts, images, fonts. Paired with
 * inline-assets.js, which rewrites a cached HTML page to embed these directly
 * instead of requesting them over the network.
 *
 * Deliberately simple compared to PageCache: no ETag revalidation (a CSS/JS
 * file that changes gets naturally refreshed the next time the page it
 * belongs to is re-cached online - see PageCache's afterPageCached hook in
 * main.js), just "do we have it, is it still fresh enough to trust".
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');
const { sameHost, isHttpUrl } = require('./config');
const { MAX_ASSET_BYTES } = require('./inline-assets');

const MAX_CACHE_BYTES = 40 * 1024 * 1024;
const MAX_CACHE_FILE_COUNT = 800;
const MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000; // stale assets are worse than none - drop after 2 weeks

const CACHEABLE_MIME_PREFIXES = ['text/css', 'application/javascript', 'text/javascript', 'image/', 'font/', 'application/font', 'application/x-font'];

function sha256(input) {
  return crypto.createHash('sha256').update(input, 'utf8').digest('hex');
}

function isCacheableMime(mime) {
  const m = (mime || '').toLowerCase();
  return CACHEABLE_MIME_PREFIXES.some((p) => m.startsWith(p));
}

class AssetCache {
  /**
   * @param {object} o
   * @param {string} o.dir
   * @param {string} o.homeUrl
   * @param {function} o.fetcher  async (url) => {status, headers, body:Buffer, finalUrl} | null
   * @param {number} [o.maxBytes] override the default size cap (for tests)
   * @param {number} [o.maxFiles] override the default file-count cap (for tests)
   */
  constructor(o) {
    this.dir = o.dir;
    this.homeUrl = o.homeUrl;
    this.fetcher = o.fetcher;
    this.maxBytes = o.maxBytes || MAX_CACHE_BYTES;
    this.maxFiles = o.maxFiles || MAX_CACHE_FILE_COUNT;
    this._writesSinceScan = 0;
    this._inflight = new Map();
    try { fs.mkdirSync(this.dir, { recursive: true }); } catch (e) { /* surfaced on first write */ }
  }

  isCacheable(url) {
    return isHttpUrl(url) && sameHost(url, this.homeUrl);
  }

  _paths(url) {
    const key = sha256(url);
    return { body: path.join(this.dir, key + '.a.gz'), meta: path.join(this.dir, key + '.a.meta') };
  }

  has(url) {
    const p = this._paths(url);
    return fs.existsSync(p.body) && fs.existsSync(p.meta);
  }

  /** Returns {mime, body:Buffer} or null. Never touches the network. */
  read(url) {
    const p = this._paths(url);
    let meta;
    try { meta = JSON.parse(fs.readFileSync(p.meta, 'utf8')); } catch (e) { return null; }
    if (!meta || Date.now() - (meta.at || 0) > MAX_AGE_MS) return null;
    try {
      return { mime: meta.mime, body: zlib.gunzipSync(fs.readFileSync(p.body)) };
    } catch (e) {
      return null;
    }
  }

  /** Fetch one asset and store it if it's a kind we cache. Never throws. */
  fetchAndCache(url) {
    if (!this.isCacheable(url)) return Promise.resolve({ stored: false, reason: 'not-cacheable' });
    if (this._inflight.has(url)) return this._inflight.get(url);
    const job = this._fetchOne(url).finally(() => this._inflight.delete(url));
    this._inflight.set(url, job);
    return job;
  }

  async _fetchOne(url) {
    let res;
    try {
      res = await this.fetcher(url);
    } catch (e) {
      return { stored: false, reason: 'network' };
    }
    if (!res || res.status < 200 || res.status >= 300) return { stored: false, reason: 'status' };
    if (res.finalUrl && !sameHost(res.finalUrl, this.homeUrl)) return { stored: false, reason: 'off-site-redirect' };
    const mime = String((res.headers && res.headers['content-type']) || '').split(';')[0].trim().toLowerCase() || 'application/octet-stream';
    if (!isCacheableMime(mime)) return { stored: false, reason: 'not-cacheable-mime' };
    const body = Buffer.isBuffer(res.body) ? res.body : Buffer.from(res.body || '');
    if (body.length === 0 || body.length > MAX_ASSET_BYTES) return { stored: false, reason: body.length === 0 ? 'empty' : 'too-large' };

    try {
      const p = this._paths(url);
      const tmp = p.body + '.tmp';
      fs.writeFileSync(tmp, zlib.gzipSync(body));
      fs.renameSync(tmp, p.body);
      fs.writeFileSync(p.meta, JSON.stringify({ mime, at: Date.now() }), 'utf8');
    } catch (e) {
      return { stored: false, reason: 'disk' };
    }
    this._writesSinceScan++;
    if (this._writesSinceScan >= 10) { this._writesSinceScan = 0; this.enforceLimits(); }
    return { stored: true };
  }

  /** Fetch + cache several assets, a few at a time. Never throws or rejects. */
  async fetchAll(urls) {
    const list = (urls || []).filter((u) => this.isCacheable(u));
    for (let i = 0; i < list.length; i += 4) {
      await Promise.all(list.slice(i, i + 4).map((u) => this.fetchAndCache(u).catch(() => {})));
    }
  }

  /** A ready-to-use lookup(url) function for inline-assets.js. Synchronous, disk-only. */
  lookup() {
    return (url) => this.read(url);
  }

  _listFiles() {
    let names;
    try { names = fs.readdirSync(this.dir); } catch (e) { return []; }
    const out = [];
    for (const n of names) {
      const full = path.join(this.dir, n);
      try {
        const st = fs.statSync(full);
        if (st.isFile()) out.push({ full, size: st.size, mtime: st.mtimeMs });
      } catch (e) { /* vanished */ }
    }
    return out;
  }

  enforceLimits() {
    const files = this._listFiles();
    let total = files.reduce((s, f) => s + f.size, 0);
    let count = files.length;
    if (total <= this.maxBytes && count <= this.maxFiles) return 0;
    files.sort((a, b) => a.mtime - b.mtime);
    let removed = 0;
    for (const f of files) {
      if (total <= this.maxBytes && count <= this.maxFiles) break;
      try { fs.unlinkSync(f.full); } catch (e) { continue; }
      total -= f.size; count--; removed++;
    }
    return removed;
  }

  stats() {
    const files = this._listFiles();
    return { files: files.length, bytes: files.reduce((s, f) => s + f.size, 0) };
  }

  clear() {
    let n = 0;
    for (const f of this._listFiles()) { try { fs.unlinkSync(f.full); n++; } catch (e) { /* ignore */ } }
    return n;
  }
}

module.exports = { AssetCache, isCacheableMime, MAX_CACHE_BYTES, MAX_CACHE_FILE_COUNT, MAX_AGE_MS };

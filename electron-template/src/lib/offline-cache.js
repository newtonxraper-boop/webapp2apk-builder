'use strict';

/**
 * On-disk cache of the app's own HTML pages - the desktop counterpart of the
 * Android build's "webcache" (MainActivity.fetchAndCache / serveFromCacheFile).
 *
 * Same rules as the APK:
 *  - only pages on the app's own host are cached;
 *  - pages whose path looks sensitive (login, checkout, payment...) never are;
 *  - each page is stored gzip-compressed next to a small .meta file holding
 *    its content type, charset, ETag and Last-Modified, and is revalidated
 *    with If-None-Match / If-Modified-Since on later fetches;
 *  - a response that ends up on a different site after redirects (a hotel
 *    Wi-Fi or carrier "top up" walled garden) is refused, never cached;
 *  - total size and file count are capped; the oldest files are evicted first.
 *
 * The network itself is injected as `fetcher` (see net.js) so this module has
 * no Electron dependency and can be unit-tested with a fake.
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');
const { hostOf, sameHost, isSameSiteHost, isHttpUrl } = require('./config');

const SENSITIVE_URL_KEYWORDS = ['login', 'logout', 'signin', 'signup', 'checkout', 'payment', 'cart'];

const MAX_CACHE_BYTES_CAP = 100 * 1024 * 1024;
const MAX_CACHE_BYTES_FLOOR = 10 * 1024 * 1024;
const MAX_CACHE_FILE_COUNT = 500;

// A cached page is shown offline by loading it as a data: URL (see main.js),
// and Chromium refuses data URLs above ~2 MB once base64-encoded. Anything
// larger than this simply isn't cached, rather than being cached and then
// unusable.
const MAX_PAGE_BYTES = 1400000;

function sha256(input) {
  return crypto.createHash('sha256').update(input, 'utf8').digest('hex');
}

function stripFragment(url) {
  const i = url.indexOf('#');
  return i === -1 ? url : url.slice(0, i);
}

function isSensitiveUrl(url) {
  try {
    const p = new URL(url).pathname.toLowerCase();
    return SENSITIVE_URL_KEYWORDS.some((k) => p.includes(k));
  } catch (e) {
    return false;
  }
}

function isHtmlType(mime) {
  const m = (mime || '').toLowerCase();
  return m === 'text/html' || m === 'application/xhtml+xml';
}

function parseContentType(contentType) {
  let mime = 'text/html';
  let encoding = 'UTF-8';
  if (contentType) {
    const parts = String(contentType).split(';');
    if (parts[0].trim()) mime = parts[0].trim().toLowerCase();
    for (const p of parts.slice(1)) {
      const t = p.trim();
      if (t.toLowerCase().startsWith('charset=')) encoding = t.slice(8).trim().replace(/^"|"$/g, '') || 'UTF-8';
    }
  }
  return { mime, encoding };
}

class PageCache {
  /**
   * @param {object} opts
   * @param {string} opts.dir        cache directory (created if missing)
   * @param {string} opts.homeUrl    the app's URL - only this host is cached
   * @param {function} opts.fetcher  async (url, {etag, lastModified}) =>
   *                                 {status, finalUrl, headers, body:Buffer}
   * @param {number} [opts.maxBytes] override the disk-derived size cap
   * @param {number} [opts.maxFiles]
   */
  constructor(opts) {
    this.dir = opts.dir;
    this.homeUrl = opts.homeUrl;
    this.fetcher = opts.fetcher;
    this.maxFiles = opts.maxFiles || MAX_CACHE_FILE_COUNT;
    this.maxBytes = opts.maxBytes || this._computeSizeLimit();
    this._writesSinceScan = 0;
    this._inflight = new Map();
    try { fs.mkdirSync(this.dir, { recursive: true }); } catch (e) { /* surfaced on first write */ }
  }

  _computeSizeLimit() {
    try {
      const st = fs.statfsSync(this.dir);
      const free = Number(st.bavail) * Number(st.bsize);
      return Math.max(MAX_CACHE_BYTES_FLOOR, Math.min(MAX_CACHE_BYTES_CAP, Math.floor(free / 20)));
    } catch (e) {
      return MAX_CACHE_BYTES_FLOOR;
    }
  }

  isCacheable(url) {
    if (!url || !isHttpUrl(url)) return false;
    if (!sameHost(url, this.homeUrl)) return false;
    if (isSensitiveUrl(url)) return false;
    return true;
  }

  _paths(url) {
    const key = sha256(stripFragment(url));
    return {
      body: path.join(this.dir, key + '.body.gz'),
      meta: path.join(this.dir, key + '.meta'),
    };
  }

  _readMeta(metaFile) {
    try {
      const parts = fs.readFileSync(metaFile, 'utf8').split('|');
      return {
        mime: parts[0] || 'text/html',
        encoding: parts[1] || 'UTF-8',
        etag: parts[2] || '',
        lastModified: parts[3] || '',
      };
    } catch (e) {
      return null;
    }
  }

  has(url) {
    const p = this._paths(url);
    return fs.existsSync(p.body) && fs.existsSync(p.meta);
  }

  /** Returns {body: Buffer, mime, encoding, etag, lastModified} or null. No network. */
  read(url) {
    const p = this._paths(url);
    const meta = this._readMeta(p.meta);
    if (!meta || !fs.existsSync(p.body)) return null;
    try {
      const body = zlib.gunzipSync(fs.readFileSync(p.body));
      const now = new Date();
      try { fs.utimesSync(p.body, now, now); } catch (e) { /* LRU touch is best-effort */ }
      return Object.assign({ body }, meta);
    } catch (e) {
      return null;
    }
  }

  /** data: URL for a cached entry, or null if there's nothing usable. */
  toDataUrl(entry) {
    if (!entry || !isHtmlType(entry.mime)) return null;
    const charset = /^[A-Za-z0-9_\-:.]+$/.test(entry.encoding) ? entry.encoding : 'UTF-8';
    return 'data:' + entry.mime + ';charset=' + charset + ';base64,' + entry.body.toString('base64');
  }

  _write(url, { mime, encoding, etag, lastModified, body }) {
    const p = this._paths(url);
    const tmpBody = p.body + '.tmp';
    fs.writeFileSync(tmpBody, zlib.gzipSync(body));
    fs.renameSync(tmpBody, p.body);
    const meta = [mime, encoding, etag || '', lastModified || ''].map((s) => String(s).replace(/\|/g, '')).join('|');
    fs.writeFileSync(p.meta, meta, 'utf8');
    this._writesSinceScan++;
    if (this._writesSinceScan >= 5) {
      this._writesSinceScan = 0;
      this.enforceLimits();
    }
  }

  /**
   * Fetch a page and store it (revalidating with ETag/Last-Modified when we
   * already hold a copy). Never throws. Concurrent calls for the same URL share
   * one request.
   * @returns {Promise<{stored:boolean, notModified?:boolean, reason?:string, status?:number}>}
   */
  fetchAndCache(url) {
    if (!this.isCacheable(url)) return Promise.resolve({ stored: false, reason: 'not-cacheable' });
    const key = stripFragment(url);
    if (this._inflight.has(key)) return this._inflight.get(key);
    const job = this._fetchAndCache(key).finally(() => this._inflight.delete(key));
    this._inflight.set(key, job);
    return job;
  }

  async _fetchAndCache(url) {
    const p = this._paths(url);
    const existing = fs.existsSync(p.body) ? this._readMeta(p.meta) : null;
    let res;
    try {
      res = await this.fetcher(url, {
        etag: existing ? existing.etag : '',
        lastModified: existing ? existing.lastModified : '',
      });
    } catch (e) {
      return { stored: false, reason: 'network', error: e && e.message };
    }
    if (!res) return { stored: false, reason: 'no-response' };

    // If we ended up on a completely different site than we asked for, this
    // almost certainly isn't the app's real server - it's a walled-garden page
    // masquerading as a normal 200. Refuse it.
    if (!isSameSiteHost(hostOf(url), hostOf(res.finalUrl || url))) {
      return { stored: false, reason: 'off-site-redirect' };
    }

    if (res.status === 304 && existing) {
      try { const now = new Date(); fs.utimesSync(p.body, now, now); } catch (e) { /* best-effort */ }
      return { stored: true, notModified: true };
    }

    if (res.status >= 200 && res.status < 300) {
      const headers = res.headers || {};
      const disposition = String(headers['content-disposition'] || '');
      const { mime, encoding } = parseContentType(headers['content-type']);
      if (/attachment/i.test(disposition) || !isHtmlType(mime)) {
        return { stored: false, reason: 'not-html', status: res.status };
      }
      const body = Buffer.isBuffer(res.body) ? res.body : Buffer.from(res.body || '');
      if (body.length === 0 || body.length > MAX_PAGE_BYTES) {
        return { stored: false, reason: body.length === 0 ? 'empty' : 'too-large', status: res.status };
      }
      try {
        this._write(url, {
          mime, encoding,
          etag: headers.etag || '',
          lastModified: headers['last-modified'] || '',
          body,
        });
      } catch (e) {
        return { stored: false, reason: 'disk', error: e && e.message };
      }
      return { stored: true, status: res.status };
    }
    return { stored: false, reason: 'status', status: res.status };
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

  /** Evict oldest files until under both the size cap and the file-count cap. */
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
      total -= f.size;
      count--;
      removed++;
    }
    return removed;
  }

  stats() {
    const files = this._listFiles();
    return { files: files.length, bytes: files.reduce((s, f) => s + f.size, 0) };
  }

  /** Delete everything ("Clear offline cache" in Settings). Returns number of files removed. */
  clear() {
    let n = 0;
    for (const f of this._listFiles()) {
      try { fs.unlinkSync(f.full); n++; } catch (e) { /* ignore */ }
    }
    return n;
  }
}

module.exports = {
  PageCache, isSensitiveUrl, isHtmlType, parseContentType, stripFragment, sha256,
  SENSITIVE_URL_KEYWORDS, MAX_PAGE_BYTES, MAX_CACHE_FILE_COUNT,
};

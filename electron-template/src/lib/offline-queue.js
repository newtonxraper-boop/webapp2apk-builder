'use strict';

/**
 * Offline submission queue - the desktop counterpart of OfflineQueueSync.java.
 *
 * While the app is offline, the page-side hooks (see page-script.js) catch
 * form submits and fetch/XHR writes and hand them here instead of letting
 * them fail. They're appended to a JSONL file in the app's data folder and
 * replayed as real HTTP requests - using whatever login session is current
 * at replay time - once the app is back online.
 *
 * Same behavior as the APK: 20 MB hard cap (oldest evicted first), a
 * double-tap on a Save button within 2 s isn't queued twice, items that can
 * never succeed (bad URL, corrupt JSON) are dropped instead of failing
 * forever, and anything the server merely rejects/couldn't be reached for
 * stays queued for the next attempt.
 *
 * Queued item shape:
 *   { url, method, enctype, ts,
 *     fields: [ {key,type:'text',value} |
 *               {key,type:'file',name,mime,data:<base64>} |
 *               {key,type:'file_too_large',name} ],
 *     headers?: { 'content-type': ..., ... }   // small whitelist, see below
 *   }
 *
 * `sender` and `allowUrl` are injected, so this file has no Electron dependency:
 *   sender({url, method, headers, body:Buffer}) -> Promise<{status:number}>
 *     (throw an Error with .permanent = true for failures that can never succeed)
 */

const fs = require('fs');
const path = require('path');

const MAX_QUEUE_BYTES = 20 * 1024 * 1024;
const DUPLICATE_WINDOW_MS = 2000;

// Headers worth replaying. Content-Type matters most: a JSON fetch() replayed
// without it is unreadable to most servers. The rest are the usual
// "this is an AJAX call / here's the CSRF token" markers.
const REPLAY_HEADER_WHITELIST = ['content-type', 'accept', 'x-requested-with', 'x-csrf-token', 'x-xsrf-token', 'csrf-token'];

function pickReplayHeaders(headers) {
  const out = {};
  if (!headers || typeof headers !== 'object') return out;
  for (const [k, v] of Object.entries(headers)) {
    const key = String(k).toLowerCase();
    if (REPLAY_HEADER_WHITELIST.includes(key) && typeof v === 'string' && v.length < 512 && !/[\r\n]/.test(v)) {
      out[key] = v;
    }
  }
  return out;
}

function permanentError(msg) {
  const e = new Error(msg);
  e.permanent = true;
  return e;
}

function escapeFieldName(s) {
  return String(s).replace(/[\r\n]/g, ' ').replace(/"/g, '%22');
}

/** Turn a queued item into the concrete HTTP request to send. Pure. */
function buildRequest(item) {
  const urlStr = item && typeof item.url === 'string' ? item.url : '';
  if (!urlStr || !(urlStr.startsWith('http://') || urlStr.startsWith('https://'))) {
    throw permanentError('Invalid queued URL: ' + urlStr);
  }
  try { new URL(urlStr); } catch (e) { throw permanentError('Invalid queued URL: ' + urlStr); }

  const method = String(item.method || 'POST').toUpperCase();
  const enctype = String(item.enctype || 'application/x-www-form-urlencoded');
  const fields = Array.isArray(item.fields) ? item.fields : [];
  const extra = pickReplayHeaders(item.headers);
  const hasFile = fields.some((f) => f && f.type === 'file');
  const headers = {};
  for (const k of ['accept', 'x-requested-with', 'x-csrf-token', 'x-xsrf-token', 'csrf-token']) {
    if (extra[k]) headers[k] = extra[k];
  }

  let body;
  if (hasFile || enctype.toLowerCase().includes('multipart')) {
    const boundary = '----w2aBoundary' + Date.now().toString(16) + Math.random().toString(16).slice(2, 10);
    const chunks = [];
    for (const f of fields) {
      if (!f) continue;
      const key = escapeFieldName(f.key);
      if (f.type === 'file') {
        const name = escapeFieldName(f.name || 'upload');
        const mime = String(f.mime || 'application/octet-stream').replace(/[\r\n]/g, '');
        chunks.push(Buffer.from(
          '--' + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"; filename="' + name + '"\r\nContent-Type: ' + mime + '\r\n\r\n', 'utf8'));
        chunks.push(Buffer.from(String(f.data || ''), 'base64'));
        chunks.push(Buffer.from('\r\n', 'utf8'));
      } else if (f.type === 'text') {
        chunks.push(Buffer.from(
          '--' + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"\r\n\r\n' + String(f.value == null ? '' : f.value) + '\r\n', 'utf8'));
      }
      // "file_too_large" entries are intentionally skipped on replay.
    }
    chunks.push(Buffer.from('--' + boundary + '--\r\n', 'utf8'));
    body = Buffer.concat(chunks);
    headers['content-type'] = 'multipart/form-data; boundary=' + boundary;
  } else if (enctype === 'raw') {
    const f = fields.find((x) => x && x.key === 'body');
    body = Buffer.from(f && f.value != null ? String(f.value) : '', 'utf8');
    headers['content-type'] = extra['content-type'] || 'text/plain;charset=UTF-8';
  } else {
    const parts = [];
    for (const f of fields) {
      if (!f || f.type !== 'text') continue;
      parts.push(encodeURIComponent(String(f.key)) + '=' + encodeURIComponent(f.value == null ? '' : String(f.value)));
    }
    body = Buffer.from(parts.join('&'), 'utf8');
    headers['content-type'] = 'application/x-www-form-urlencoded';
  }

  return {
    url: urlStr,
    method: method === 'GET' ? 'POST' : method, // never replay as GET
    headers,
    body,
  };
}

class OfflineQueue {
  /**
   * @param {object} o
   * @param {string} o.file        path of the .jsonl queue file
   * @param {function} o.sender    see file header
   * @param {function} [o.allowUrl] (url) => boolean, checked when enqueuing
   * @param {number} [o.maxBytes]
   */
  constructor(o) {
    this.file = o.file;
    this.sender = o.sender;
    this.allowUrl = o.allowUrl || (() => true);
    this.maxBytes = o.maxBytes || MAX_QUEUE_BYTES;
    this._flushing = null;
    try { fs.mkdirSync(path.dirname(this.file), { recursive: true }); } catch (e) { /* surfaced on write */ }
  }

  _readLines() {
    let text;
    try { text = fs.readFileSync(this.file, 'utf8'); } catch (e) { return []; }
    return text.split('\n').filter((l) => l.trim().length > 0);
  }

  _writeLines(lines) {
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, lines.length ? lines.join('\n') + '\n' : '', 'utf8');
    fs.renameSync(tmp, this.file);
  }

  size() {
    return this._readLines().length;
  }

  _isDuplicateOfLast(lines, incoming) {
    if (!lines.length) return false;
    let last;
    try { last = JSON.parse(lines[lines.length - 1]); } catch (e) { return false; }
    if (Math.abs((incoming.ts || 0) - (last.ts || 0)) > DUPLICATE_WINDOW_MS) return false;
    return incoming.url === last.url
      && incoming.method === last.method
      && incoming.enctype === last.enctype
      && JSON.stringify(incoming.fields) === JSON.stringify(last.fields);
  }

  /**
   * Add a submission. Returns {ok:boolean, duplicate?:boolean, size:number}.
   * `payload` is the JSON string (or object) built by the page-side hook.
   */
  enqueue(payload) {
    let item;
    try {
      item = typeof payload === 'string' ? JSON.parse(payload) : payload;
    } catch (e) {
      return { ok: false, size: this.size() };
    }
    if (!item || typeof item.url !== 'string' || !item.url
        || item.url.charAt(0) === '{' || item.url.charAt(0) === '['
        || !(item.url.startsWith('http://') || item.url.startsWith('https://'))
        || !this.allowUrl(item.url)) {
      return { ok: false, size: this.size() };
    }
    item.method = String(item.method || 'POST').toUpperCase();
    item.enctype = String(item.enctype || 'application/x-www-form-urlencoded');
    item.fields = Array.isArray(item.fields) ? item.fields : [];
    item.headers = pickReplayHeaders(item.headers);
    item.ts = Number(item.ts) || Date.now();

    try {
      const lines = this._readLines();
      if (this._isDuplicateOfLast(lines, item)) return { ok: true, duplicate: true, size: lines.length };
      const line = JSON.stringify(item).replace(/\n/g, ' ');
      if (Buffer.byteLength(line) + 1 > this.maxBytes) return { ok: false, size: lines.length };
      // Evict the oldest entries (rather than refusing the new one) if this
      // would push the file over its cap - the newest action is usually the
      // one most worth keeping.
      let total = lines.reduce((s, l) => s + Buffer.byteLength(l) + 1, 0) + Buffer.byteLength(line) + 1;
      while (lines.length && total > this.maxBytes) {
        total -= Buffer.byteLength(lines.shift()) + 1;
      }
      lines.push(line);
      this._writeLines(lines);
      return { ok: true, size: lines.length };
    } catch (e) {
      return { ok: false, size: this.size() };
    }
  }

  /**
   * Try to send everything queued. Items that still fail stay queued; items
   * that can never succeed are dropped. Only one flush runs at a time - a
   * second call while one is in flight just waits for that one's result.
   * @returns {Promise<{succeeded:number, remaining:number, dropped:number, lastError:string|null}>}
   */
  flush() {
    if (this._flushing) return this._flushing;
    this._flushing = this._flush().finally(() => { this._flushing = null; });
    return this._flushing;
  }

  async _flush() {
    const lines = this._readLines();
    if (!lines.length) return { succeeded: 0, remaining: 0, dropped: 0, lastError: null };

    const remaining = [];
    let succeeded = 0;
    let dropped = 0;
    let lastError = null;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const r = await this._trySend(line);
      if (r.success) succeeded++;
      else if (r.permanent) { dropped++; lastError = r.error; }
      else {
        remaining.push(line);
        lastError = r.error;
        if (r.network) {
          // The server couldn't be reached at all - no point waiting out a
          // timeout for every remaining item, and stopping here also keeps
          // the original order (a "create" must still go out before the
          // "edit" that follows it).
          for (let j = i + 1; j < lines.length; j++) remaining.push(lines[j]);
          break;
        }
      }
    }

    // Entries enqueued while this flush was running were appended after we read
    // `lines`; keep them.
    const seen = new Set(lines);
    const added = this._readLines().filter((l) => !seen.has(l));
    const finalLines = remaining.concat(added);
    try { this._writeLines(finalLines); } catch (e) { /* keep going */ }

    return { succeeded, remaining: finalLines.length, dropped, lastError };
  }

  async _trySend(line) {
    let req;
    try {
      req = buildRequest(JSON.parse(line));
    } catch (e) {
      const permanent = e.permanent === true || e instanceof SyntaxError;
      return { success: false, permanent, error: e.message };
    }
    try {
      const res = await this.sender(req);
      const status = res && res.status;
      if (status >= 200 && status < 400) return { success: true };
      // A 4xx/5xx is the server's call, not something to give up on locally -
      // a 401 from a stale session can still succeed after a fresh login.
      return { success: false, permanent: false, error: 'HTTP ' + status + ' from server for ' + req.url };
    } catch (e) {
      const permanent = !!(e && e.permanent === true);
      return { success: false, permanent, network: !permanent, error: (e && e.message) || 'Network error' };
    }
  }
}

module.exports = { OfflineQueue, buildRequest, pickReplayHeaders, MAX_QUEUE_BYTES, DUPLICATE_WINDOW_MS };

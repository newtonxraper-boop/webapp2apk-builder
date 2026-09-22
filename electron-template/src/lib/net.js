'use strict';

/**
 * Thin wrappers over Electron's net module (Chromium's network stack) that
 * plug into the Electron-free modules: the page cache's `fetcher`, the offline
 * queue's `sender`, the reachability probe's `requester`, and the update check.
 * Everything here is best-effort and resolves/rejects - it never throws
 * synchronously.
 */

const { net } = require('electron');
const { hostOf, isSameSiteHost } = require('./config');

function normalizeHeaders(raw) {
  const out = {};
  if (!raw) return out;
  for (const [k, v] of Object.entries(raw)) {
    out[String(k).toLowerCase()] = Array.isArray(v) ? v.join(', ') : String(v);
  }
  return out;
}

/**
 * Low-level request.
 * @param {object} o
 * @param {string} o.url
 * @param {string} [o.method]
 * @param {object} [o.headers]
 * @param {Buffer} [o.body]
 * @param {Electron.Session} [o.session]  session whose cookies/UA to use
 * @param {boolean} [o.useSessionCookies] send + store that session's cookies
 * @param {function} [o.onRedirect] (status, location) => boolean; true = follow.
 *        If it returns false the request is stopped and the promise resolves
 *        with {redirected:true, status, location} (no body).
 * @param {boolean} [o.readBody=true]
 * @param {number} [o.timeoutMs=15000]
 * @returns {Promise<{status:number, headers:object, body:Buffer, finalUrl:string, redirected?:boolean, location?:string}>}
 */
function request(o) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let timer = null;
    let req;
    const done = (fn, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(value);
    };

    try {
      req = net.request({
        method: o.method || 'GET',
        url: o.url,
        session: o.session,
        useSessionCookies: !!o.useSessionCookies,
        redirect: 'manual',
      });
    } catch (e) {
      reject(e);
      return;
    }

    let finalUrl = o.url;
    for (const [k, v] of Object.entries(o.headers || {})) {
      try { req.setHeader(k, v); } catch (e) { /* skip forbidden header */ }
    }

    timer = setTimeout(() => {
      try { req.abort(); } catch (e) { /* ignore */ }
      done(reject, new Error('Timed out'));
    }, o.timeoutMs || 15000);

    req.on('redirect', (status, method, redirectUrl) => {
      let follow = true;
      try { follow = o.onRedirect ? !!o.onRedirect(status, redirectUrl) : true; } catch (e) { follow = false; }
      if (follow) {
        finalUrl = redirectUrl;
        try { req.followRedirect(); } catch (e) { done(reject, e); }
      } else {
        try { req.abort(); } catch (e) { /* ignore */ }
        done(resolve, { status, headers: {}, body: Buffer.alloc(0), finalUrl, redirected: true, location: redirectUrl });
      }
    });

    req.on('response', (res) => {
      const headers = normalizeHeaders(res.headers);
      if (o.readBody === false) {
        try { req.abort(); } catch (e) { /* ignore */ }
        done(resolve, { status: res.statusCode, headers, body: Buffer.alloc(0), finalUrl });
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => done(resolve, { status: res.statusCode, headers, body: Buffer.concat(chunks), finalUrl }));
      res.on('error', (e) => done(reject, e));
      res.on('aborted', () => done(reject, new Error('Aborted')));
    });
    req.on('error', (e) => done(reject, e));
    req.on('abort', () => done(reject, new Error('Aborted')));

    try {
      if (o.body && o.body.length) req.write(o.body);
      req.end();
    } catch (e) {
      done(reject, e);
    }
  });
}

/** fetcher for PageCache: same-site redirects only, conditional GET. */
function makePageFetcher(session) {
  return async (url, { etag, lastModified }) => {
    const requestHost = hostOf(url);
    const headers = { Accept: 'text/html,application/xhtml+xml' };
    if (etag) headers['If-None-Match'] = etag;
    if (lastModified) headers['If-Modified-Since'] = lastModified;
    const res = await request({
      url,
      headers,
      session,
      useSessionCookies: true,
      timeoutMs: 8000,
      onRedirect: (status, location) => isSameSiteHost(requestHost, hostOf(location)),
    });
    if (res.redirected) {
      // Bounced to another site (captive portal): report the landing host so
      // the cache refuses it.
      return { status: res.status, headers: {}, body: Buffer.alloc(0), finalUrl: res.location };
    }
    return res;
  };
}

/** sender for OfflineQueue: replays a queued request with the live session cookies. */
function makeQueueSender(session) {
  return async (req) => {
    const res = await request({
      url: req.url,
      method: req.method,
      headers: req.headers,
      body: req.body,
      session,
      useSessionCookies: true,
      timeoutMs: 20000,
      readBody: false,
    });
    return { status: res.status };
  };
}

/** requester for reachability.probe: never follows redirects itself. */
function makeProbeRequester() {
  return async (url, method, timeoutMs) => {
    const res = await request({
      url,
      method,
      timeoutMs,
      readBody: false,
      onRedirect: () => false,
    });
    return { status: res.status, location: res.location };
  };
}

async function fetchJson(url, timeoutMs) {
  const res = await request({ url, timeoutMs: timeoutMs || 5000 });
  if (res.status !== 200) return null;
  return JSON.parse(res.body.toString('utf8'));
}

module.exports = { request, makePageFetcher, makeQueueSender, makeProbeRequester, fetchJson };

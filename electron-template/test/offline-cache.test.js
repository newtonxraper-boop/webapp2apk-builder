'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { tmpDir } = require('./helpers');
const { PageCache, isSensitiveUrl, MAX_PAGE_BYTES } = require('../src/lib/offline-cache');

const HOME = 'https://shop.example.com/';

function html(body) { return Buffer.from('<html><body>' + body + '</body></html>'); }
function ok(url, body, extra) {
  return { status: 200, finalUrl: url, headers: Object.assign({ 'content-type': 'text/html; charset=utf-8', etag: '"v1"' }, extra || {}), body: html(body) };
}

function makeCache(fetcher, opts) {
  return new PageCache(Object.assign({ dir: path.join(tmpDir(), 'webcache'), homeUrl: HOME, fetcher, maxBytes: 10 * 1024 * 1024 }, opts || {}));
}

test('only same-host, non-sensitive http(s) pages are cacheable', () => {
  const c = makeCache(async () => null);
  assert.ok(c.isCacheable('https://shop.example.com/orders'));
  assert.ok(!c.isCacheable('https://other.example.com/orders'));
  assert.ok(!c.isCacheable('https://shop.example.com/login'));
  assert.ok(!c.isCacheable('https://shop.example.com/Checkout/step1'));
  assert.ok(!c.isCacheable('https://shop.example.com/api/cart'));
  assert.ok(!c.isCacheable('ftp://shop.example.com/'));
  assert.ok(!c.isCacheable('data:text/html,hi'));
  assert.ok(isSensitiveUrl('https://x.com/user/signin'));
  assert.ok(!isSensitiveUrl('https://x.com/products'));
});

test('stores a page and reads it back (gzip round-trip, charset kept)', async () => {
  const url = 'https://shop.example.com/orders';
  const c = makeCache(async (u) => ok(u, 'hello orders'));
  const r = await c.fetchAndCache(url);
  assert.strictEqual(r.stored, true);
  assert.ok(c.has(url));
  const e = c.read(url);
  assert.match(e.body.toString(), /hello orders/);
  assert.strictEqual(e.mime, 'text/html');
  assert.strictEqual(e.encoding, 'utf-8');
  assert.strictEqual(e.etag, '"v1"');
  const du = c.toDataUrl(e);
  assert.match(du, /^data:text\/html;charset=utf-8;base64,/);
  assert.match(Buffer.from(du.split(',')[1], 'base64').toString(), /hello orders/);
});

test('fragment is ignored in the cache key', async () => {
  const c = makeCache(async (u) => ok(u, 'x'));
  await c.fetchAndCache('https://shop.example.com/a#top');
  assert.ok(c.has('https://shop.example.com/a'));
  assert.ok(c.has('https://shop.example.com/a#other'));
});

test('revalidates with ETag and keeps the copy on 304', async () => {
  const url = 'https://shop.example.com/p';
  let seen = null;
  let mode = 'first';
  const c = makeCache(async (u, cond) => {
    seen = cond;
    if (mode === 'first') return ok(u, 'v1');
    return { status: 304, finalUrl: u, headers: {}, body: Buffer.alloc(0) };
  });
  await c.fetchAndCache(url);
  mode = 'second';
  const r = await c.fetchAndCache(url);
  assert.strictEqual(seen.etag, '"v1"');
  assert.strictEqual(r.notModified, true);
  assert.match(c.read(url).body.toString(), /v1/);
});

test('refuses a response that landed on a different site (captive portal)', async () => {
  const url = 'https://shop.example.com/home';
  const c = makeCache(async () => ok('https://wifi-portal.hotel.net/login', 'PAY FOR WIFI'));
  const r = await c.fetchAndCache(url);
  assert.strictEqual(r.stored, false);
  assert.strictEqual(r.reason, 'off-site-redirect');
  assert.ok(!c.has(url));
});

test('accepts redirects within the same site (subdomain / www)', async () => {
  const url = 'https://shop.example.com/home';
  const c = makeCache(async () => ok('https://www.shop.example.com/home', 'fine'));
  assert.strictEqual((await c.fetchAndCache(url)).stored, true);
});

test('does not cache non-HTML, attachments, empty or oversized bodies, or errors', async () => {
  const url = 'https://shop.example.com/x';
  let res;
  const c = makeCache(async () => res);
  res = { status: 200, finalUrl: url, headers: { 'content-type': 'application/pdf' }, body: Buffer.from('%PDF') };
  assert.strictEqual((await c.fetchAndCache(url)).reason, 'not-html');
  res = ok(url, 'x', { 'content-disposition': 'attachment; filename=a.html' });
  assert.strictEqual((await c.fetchAndCache(url)).reason, 'not-html');
  res = { status: 200, finalUrl: url, headers: { 'content-type': 'text/html' }, body: Buffer.alloc(0) };
  assert.strictEqual((await c.fetchAndCache(url)).reason, 'empty');
  res = { status: 200, finalUrl: url, headers: { 'content-type': 'text/html' }, body: Buffer.alloc(MAX_PAGE_BYTES + 1, 97) };
  assert.strictEqual((await c.fetchAndCache(url)).reason, 'too-large');
  res = { status: 500, finalUrl: url, headers: { 'content-type': 'text/html' }, body: html('err') };
  assert.strictEqual((await c.fetchAndCache(url)).reason, 'status');
  assert.ok(!c.has(url));
});

test('network failure never throws and keeps an existing copy', async () => {
  const url = 'https://shop.example.com/keep';
  let fail = false;
  const c = makeCache(async (u) => { if (fail) throw new Error('offline'); return ok(u, 'kept'); });
  await c.fetchAndCache(url);
  fail = true;
  const r = await c.fetchAndCache(url);
  assert.strictEqual(r.stored, false);
  assert.match(c.read(url).body.toString(), /kept/);
});

test('concurrent fetches of one URL share a single request', async () => {
  let calls = 0;
  const c = makeCache(async (u) => { calls++; await new Promise((r) => setTimeout(r, 20)); return ok(u, 'x'); });
  await Promise.all([1, 2, 3].map(() => c.fetchAndCache('https://shop.example.com/same')));
  assert.strictEqual(calls, 1);
});

test('evicts the oldest files when over the file-count cap', async () => {
  const c = makeCache(async (u) => ok(u, 'page ' + u), { maxFiles: 6 });
  for (let i = 0; i < 8; i++) {
    await c.fetchAndCache('https://shop.example.com/p' + i);
    // stagger mtimes so "oldest" is well-defined
    const d = c.dir;
    const t = new Date(Date.now() - (100 - i) * 1000);
    for (const f of fs.readdirSync(d)) fs.utimesSync(path.join(d, f), t, t);
  }
  c.enforceLimits();
  assert.ok(c.stats().files <= 6);
});

test('clear() removes everything', async () => {
  const c = makeCache(async (u) => ok(u, 'x'));
  await c.fetchAndCache('https://shop.example.com/a');
  await c.fetchAndCache('https://shop.example.com/b');
  assert.ok(c.stats().files >= 4);
  c.clear();
  assert.strictEqual(c.stats().files, 0);
  assert.ok(!c.has('https://shop.example.com/a'));
});

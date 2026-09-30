'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { tmpDir } = require('./helpers');
const { AssetCache } = require('../src/lib/asset-cache');

const HOME = 'https://shop.example.com/';

function mk(fetcher) {
  return new AssetCache({ dir: path.join(tmpDir(), 'assetcache'), homeUrl: HOME, fetcher });
}
const okRes = (mime, body, finalUrl) => ({ status: 200, headers: { 'content-type': mime }, body: Buffer.from(body), finalUrl });

test('isCacheable requires same host', () => {
  const c = mk(async () => null);
  assert.ok(c.isCacheable('https://shop.example.com/app.css'));
  assert.ok(!c.isCacheable('https://cdn.other.com/app.css'));
});

test('caches CSS/JS/images/fonts but not other mime types', async () => {
  const c = mk(async (u) => {
    if (u.endsWith('.css')) return okRes('text/css', 'body{color:red}');
    if (u.endsWith('.js')) return okRes('application/javascript', 'console.log(1)');
    if (u.endsWith('.png')) return okRes('image/png', 'PNGDATA');
    if (u.endsWith('.woff2')) return okRes('font/woff2', 'FONTDATA');
    return okRes('application/pdf', '%PDF');
  });
  for (const f of ['a.css', 'a.js', 'a.png', 'a.woff2']) {
    const r = await c.fetchAndCache(HOME + f);
    assert.strictEqual(r.stored, true, f);
  }
  const pdf = await c.fetchAndCache(HOME + 'a.pdf');
  assert.strictEqual(pdf.stored, false);
  assert.strictEqual(pdf.reason, 'not-cacheable-mime');
});

test('read() round-trips mime + bytes through gzip', async () => {
  const c = mk(async () => okRes('text/css', '.a{color:blue}'));
  await c.fetchAndCache(HOME + 'x.css');
  const e = c.read(HOME + 'x.css');
  assert.strictEqual(e.mime, 'text/css');
  assert.strictEqual(e.body.toString(), '.a{color:blue}');
});

test('lookup() is a synchronous disk-only function suitable for inline-assets', async () => {
  const c = mk(async () => okRes('image/png', 'PNGBYTES'));
  await c.fetchAndCache(HOME + 'logo.png');
  const lookup = c.lookup();
  assert.strictEqual(lookup(HOME + 'logo.png').body.toString(), 'PNGBYTES');
  assert.strictEqual(lookup(HOME + 'missing.png'), null);
});

test('refuses an off-site redirect and a foreign host outright', async () => {
  const c = mk(async () => okRes('text/css', 'x', 'https://cdn.attacker.net/x.css'));
  const r = await c.fetchAndCache(HOME + 'x.css');
  assert.strictEqual(r.stored, false);
  assert.strictEqual(r.reason, 'off-site-redirect');
  const r2 = await c.fetchAndCache('https://other.com/x.css');
  assert.strictEqual(r2.reason, 'not-cacheable');
});

test('empty and error responses are not stored; network errors never throw', async () => {
  let mode = 'empty';
  const c = mk(async () => {
    if (mode === 'empty') return okRes('text/css', '');
    if (mode === 'error') return { status: 500, headers: { 'content-type': 'text/css' }, body: Buffer.from('x') };
    throw new Error('offline');
  });
  assert.strictEqual((await c.fetchAndCache(HOME + 'x.css')).reason, 'empty');
  mode = 'error';
  assert.strictEqual((await c.fetchAndCache(HOME + 'y.css')).reason, 'status');
  mode = 'throw';
  const r = await c.fetchAndCache(HOME + 'z.css');
  assert.strictEqual(r.stored, false);
  assert.strictEqual(r.reason, 'network');
});

test('concurrent fetchAndCache for the same URL share one request', async () => {
  let calls = 0;
  const c = mk(async (u) => { calls++; await new Promise((r) => setTimeout(r, 15)); return okRes('text/css', 'x'); });
  await Promise.all([1, 2, 3].map(() => c.fetchAndCache(HOME + 'shared.css')));
  assert.strictEqual(calls, 1);
});

test('fetchAll fetches a batch and filters out foreign hosts first', async () => {
  const seen = [];
  const c = mk(async (u) => { seen.push(u); return okRes('text/css', 'x'); });
  await c.fetchAll([HOME + 'a.css', 'https://other.com/b.css', HOME + 'c.css']);
  assert.deepStrictEqual(seen.sort(), [HOME + 'a.css', HOME + 'c.css']);
});

test('enforceLimits evicts the oldest files once over the file-count cap; clear() empties everything', async () => {
  const fs = require('fs');
  const c = new AssetCache({ dir: path.join(tmpDir(), 'assetcache'), homeUrl: HOME, fetcher: async () => okRes('text/css', 'x'.repeat(50)), maxFiles: 6 });
  for (let i = 0; i < 5; i++) {
    const before = new Set(fs.readdirSync(c.dir));
    await c.fetchAndCache(HOME + 'f' + i + '.css');
    const t = new Date(Date.now() - (100 - i) * 1000); // stagger strictly by insertion order
    for (const f of fs.readdirSync(c.dir)) {
      if (!before.has(f)) fs.utimesSync(path.join(c.dir, f), t, t);
    }
  }
  assert.strictEqual(c.stats().files, 10); // 5 assets x (.a.gz + .a.meta)
  c.enforceLimits();
  assert.ok(c.stats().files <= 6);
  assert.strictEqual(c.read(HOME + 'f0.css'), null); // oldest was evicted
  c.clear();
  assert.strictEqual(c.stats().files, 0);
});

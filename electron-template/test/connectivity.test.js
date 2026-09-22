'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { probe } = require('../src/lib/reachability');
const { Connectivity } = require('../src/lib/connectivity');

const U = 'https://shop.example.com/';
const seq = (...responses) => { const calls = []; const fn = async (url, method) => { calls.push([url, method]); const r = responses.shift(); if (r instanceof Error) throw r; return r; }; fn.calls = calls; return fn; };

test('probe: HEAD 200 is reachable', async () => {
  const rq = seq({ status: 200 });
  assert.strictEqual(await probe(rq, U), true);
  assert.deepStrictEqual(rq.calls[0], [U, 'HEAD']);
});
test('probe: 405 on HEAD falls back to GET', async () => {
  const rq = seq({ status: 405 }, { status: 200 });
  assert.strictEqual(await probe(rq, U), true);
  assert.deepStrictEqual(rq.calls.map((c) => c[1]), ['HEAD', 'GET']);
});
test('probe: 405 on GET too is not reachable', async () => {
  assert.strictEqual(await probe(seq({ status: 405 }, { status: 405 }), U), false);
});
test('probe: 5xx from the app itself still counts as reachable', async () => {
  assert.strictEqual(await probe(seq({ status: 503 }), U), true);
});
test('probe: redirect to a different host (captive portal) is NOT reachable', async () => {
  assert.strictEqual(await probe(seq({ status: 302, location: 'http://portal.hotelwifi.net/login' }), U), false);
});
test('probe: same-host redirect (http->https, /->/login) is followed', async () => {
  const rq = seq({ status: 301, location: '/login' }, { status: 200 });
  assert.strictEqual(await probe(rq, U), true);
  assert.strictEqual(rq.calls[1][0], 'https://shop.example.com/login');
});
test('probe: redirect loops give up', async () => {
  const rq = seq(...Array(6).fill({ status: 302, location: '/again' }));
  assert.strictEqual(await probe(rq, U), false);
});
test('probe: connection failure is not reachable, never throws', async () => {
  assert.strictEqual(await probe(seq(new Error('ECONNREFUSED')), U), false);
});

function mkConn(o) {
  const os = { online: true };
  const results = o.results || [];
  let n = 0;
  const c = new Connectivity({
    isOsOnline: () => os.online,
    probe: async () => (results.length ? results[Math.min(n++, results.length - 1)] : true),
    sleep: async () => {},
    pollMs: 1000000,
  });
  c.start();
  c.stop();
  return { c, os, probes: () => n };
}

test('online by default; OS offline -> offline; OS back -> online after probe', async () => {
  const { c, os } = mkConn({ results: [true] });
  const changes = [];
  c.on('change', (v) => changes.push(v));
  assert.strictEqual(c.online, true);
  os.online = false; c._poll();
  assert.strictEqual(c.online, false);
  os.online = true; c._poll();
  await c.verify();
  assert.deepStrictEqual(changes, [false, true]);
});

test('needs two failed probes before flipping offline (one slow response is ignored)', async () => {
  const { c } = mkConn({ results: [false, true] });
  await c.verify();
  assert.strictEqual(c.online, true);
});

test('two failed probes = unreachable (captive portal); recovers when probe passes', async () => {
  const { c } = mkConn({ results: [false, false, true] });
  const changes = [];
  c.on('change', (v) => changes.push(v));
  await c.verify();
  assert.strictEqual(c.online, false);
  await c.verify();
  assert.strictEqual(c.online, true);
  assert.deepStrictEqual(changes, [false, true]);
  c.stop();
});

test('verify() calls are de-duplicated while one is in flight', async () => {
  const { c, probes } = mkConn({ results: [true] });
  const a = c.verify(); const b = c.verify();
  assert.strictEqual(a, b);
  await a;
  assert.strictEqual(probes(), 1);
});

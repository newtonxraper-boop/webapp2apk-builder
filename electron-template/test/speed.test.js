'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { NetQuality } = require('../src/lib/netquality');
const { shouldBlockRequest, shouldBlockByHeaders } = require('../src/lib/datasaver');
const { UsageTracker } = require('../src/lib/usage');

test('NetQuality needs two bad samples to go slow, and recovers', () => {
  const q = new NetQuality({ slowProbeMs: 1000 });
  const changes = [];
  q.on('change', (v) => changes.push(v));
  q.record('probe', 200);
  q.record('probe', 4000); // one spike: not enough
  assert.strictEqual(q.slow, false);
  q.record('probe', 4000);
  q.record('probe', 4000);
  assert.strictEqual(q.slow, true);
  for (let i = 0; i < 8; i++) q.record('probe', 100);
  assert.strictEqual(q.slow, false);
  assert.deepStrictEqual(changes, [true, false]);
});

test('NetQuality ignores junk', () => {
  const q = new NetQuality();
  q.record('probe', NaN); q.record('nope', 5); q.record('load', -1);
  assert.strictEqual(q.ema.probe, null);
});

test('data saver blocks media, foreign video frames and big images only', () => {
  assert.strictEqual(shouldBlockRequest({ resourceType: 'media', url: 'https://a.test/v.mp4' }, 'a.test'), true);
  assert.strictEqual(shouldBlockRequest({ resourceType: 'subFrame', url: 'https://www.youtube.com/embed/x' }, 'a.test'), true);
  assert.strictEqual(shouldBlockRequest({ resourceType: 'subFrame', url: 'https://a.test/page' }, 'a.test'), false);
  assert.strictEqual(shouldBlockRequest({ resourceType: 'image', url: 'https://a.test/i.png' }, 'a.test'), false);
  assert.strictEqual(shouldBlockByHeaders({ resourceType: 'image', responseHeaders: { 'Content-Length': ['900000'] } }), true);
  assert.strictEqual(shouldBlockByHeaders({ resourceType: 'image', responseHeaders: { 'content-length': '1000' } }), false);
  assert.strictEqual(shouldBlockByHeaders({ resourceType: 'script', responseHeaders: { 'content-length': '9000000' } }), false);
});

test('UsageTracker ranks by visits and prunes', () => {
  const data = {};
  const store = { get: (k, f) => (k in data ? data[k] : f), set: (k, v) => { data[k] = v; } };
  let t = 1e12;
  const u = new UsageTracker(store, { max: 3, now: () => t });
  for (let i = 0; i < 5; i++) u.record('https://a.test/books#x');
  u.record('https://a.test/vote');
  u.record('https://a.test/vote');
  u.record('https://a.test/rare1');
  u.record('https://a.test/rare2');
  assert.deepStrictEqual(u.top(2), ['https://a.test/books', 'https://a.test/vote']);
  assert.ok(Object.keys(data.page_usage).length <= 3);
  assert.deepStrictEqual(u.top(5, (x) => x.endsWith('vote')), ['https://a.test/vote']);
  u.record('mailto:x@y.z'); // ignored
});

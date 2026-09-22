'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { tmpDir } = require('./helpers');
const { OfflineQueue, buildRequest } = require('../src/lib/offline-queue');

const U = 'https://shop.example.com/api/save';
function mk(sender, opts) {
  return new OfflineQueue(Object.assign({ file: path.join(tmpDir(), 'q.jsonl'), sender, allowUrl: (u) => u.startsWith('https://shop.example.com') }, opts || {}));
}
const item = (over) => JSON.stringify(Object.assign({ url: U, method: 'POST', enctype: 'application/x-www-form-urlencoded', fields: [{ key: 'a', type: 'text', value: '1 2&3' }], ts: Date.now() }, over || {}));

test('buildRequest: urlencoded body', () => {
  const r = buildRequest(JSON.parse(item()));
  assert.strictEqual(r.method, 'POST');
  assert.strictEqual(r.body.toString(), 'a=1%202%263');
  assert.strictEqual(r.headers['content-type'], 'application/x-www-form-urlencoded');
});

test('buildRequest: GET is never replayed as GET', () => {
  assert.strictEqual(buildRequest(JSON.parse(item({ method: 'GET' }))).method, 'POST');
});

test('buildRequest: raw JSON body keeps its Content-Type', () => {
  const r = buildRequest({ url: U, method: 'PUT', enctype: 'raw', fields: [{ key: 'body', type: 'text', value: '{"x":1}' }], headers: { 'content-type': 'application/json', 'x-evil': 'no' } });
  assert.strictEqual(r.body.toString(), '{"x":1}');
  assert.strictEqual(r.headers['content-type'], 'application/json');
  assert.ok(!('x-evil' in r.headers));
  assert.strictEqual(r.method, 'PUT');
});

test('buildRequest: multipart with a file, file_too_large skipped', () => {
  const data = Buffer.from('PNGDATA').toString('base64');
  const r = buildRequest({ url: U, method: 'POST', enctype: 'multipart/form-data', fields: [
    { key: 'title', type: 'text', value: 'hi' },
    { key: 'photo', type: 'file', name: 'a"b.png', mime: 'image/png', data },
    { key: 'big', type: 'file_too_large', name: 'huge.jpg' },
  ] });
  const m = /boundary=(.+)$/.exec(r.headers['content-type']);
  assert.ok(m);
  const body = r.body.toString('latin1');
  assert.ok(body.includes('--' + m[1] + '\r\nContent-Disposition: form-data; name="title"\r\n\r\nhi\r\n'));
  assert.ok(body.includes('name="photo"; filename="a%22b.png"'));
  assert.ok(body.includes('Content-Type: image/png\r\n\r\nPNGDATA\r\n'));
  assert.ok(!body.includes('huge.jpg'));
  assert.ok(body.endsWith('--' + m[1] + '--\r\n'));
});

test('buildRequest: invalid URLs are permanent errors', () => {
  for (const bad of ['', '{}', 'javascript:alert(1)', 'ftp://x']) {
    assert.throws(() => buildRequest({ url: bad }), (e) => e.permanent === true);
  }
});

test('enqueue rejects garbage URLs, foreign hosts and bad JSON', () => {
  const q = mk(async () => ({ status: 200 }));
  assert.strictEqual(q.enqueue(item({ url: '{}' })).ok, false);
  assert.strictEqual(q.enqueue(item({ url: '[object Object]' })).ok, false);
  assert.strictEqual(q.enqueue(item({ url: 'https://evil.com/x' })).ok, false);
  assert.strictEqual(q.enqueue('not json').ok, false);
  assert.strictEqual(q.size(), 0);
  assert.strictEqual(q.enqueue(item()).ok, true);
  assert.strictEqual(q.size(), 1);
});

test('a double-tap within 2s is queued once; a later identical one is queued again', () => {
  const q = mk(async () => ({ status: 200 }));
  const t = Date.now();
  q.enqueue(item({ ts: t }));
  const dup = q.enqueue(item({ ts: t + 500 }));
  assert.strictEqual(dup.duplicate, true);
  assert.strictEqual(q.size(), 1);
  q.enqueue(item({ ts: t + 5000 }));
  assert.strictEqual(q.size(), 2);
});

test('size cap evicts the oldest entries, keeps the newest', () => {
  const q = mk(async () => ({ status: 200 }), { maxBytes: 2500 });
  for (let i = 0; i < 10; i++) q.enqueue(item({ ts: 1000 + i * 10000, fields: [{ key: 'n', type: 'text', value: 'x'.repeat(400) + i }] }));
  const lines = q._readLines().map((l) => JSON.parse(l));
  assert.ok(lines.length < 10 && lines.length >= 1);
  assert.ok(lines[lines.length - 1].fields[0].value.endsWith('9'));
  assert.strictEqual(q.enqueue(item({ fields: [{ key: 'n', type: 'text', value: 'y'.repeat(5000) }] })).ok, false);
});

test('flush: sends everything in order and empties the queue', async () => {
  const sent = [];
  const q = mk(async (req) => { sent.push(req.body.toString()); return { status: 200 }; });
  q.enqueue(item({ ts: 1, fields: [{ key: 'n', type: 'text', value: 'first' }] }));
  q.enqueue(item({ ts: 100000, fields: [{ key: 'n', type: 'text', value: 'second' }] }));
  const r = await q.flush();
  assert.deepStrictEqual(sent, ['n=first', 'n=second']);
  assert.strictEqual(r.succeeded, 2);
  assert.strictEqual(r.remaining, 0);
  assert.strictEqual(q.size(), 0);
});

test('flush: a network error stops early, keeps order and everything queued', async () => {
  let calls = 0;
  const q = mk(async () => { calls++; throw new Error('ENOTFOUND'); });
  for (let i = 0; i < 3; i++) q.enqueue(item({ ts: i * 100000, fields: [{ key: 'n', type: 'text', value: String(i) }] }));
  const r = await q.flush();
  assert.strictEqual(calls, 1);
  assert.strictEqual(r.succeeded, 0);
  assert.strictEqual(r.remaining, 3);
  assert.match(r.lastError, /ENOTFOUND/);
});

test('flush: HTTP errors stay queued (server decides), others still sent', async () => {
  const q = mk(async (req) => ({ status: req.body.toString().includes('bad') ? 500 : 200 }));
  q.enqueue(item({ ts: 1, fields: [{ key: 'n', type: 'text', value: 'bad' }] }));
  q.enqueue(item({ ts: 100000, fields: [{ key: 'n', type: 'text', value: 'good' }] }));
  const r = await q.flush();
  assert.strictEqual(r.succeeded, 1);
  assert.strictEqual(r.remaining, 1);
  assert.match(r.lastError, /HTTP 500/);
});

test('flush: corrupt items are dropped, not retried forever', async () => {
  const q = mk(async () => ({ status: 200 }));
  require('fs').writeFileSync(q.file, 'this is not json\n' + JSON.stringify({ url: '{}', fields: [] }) + '\n', 'utf8');
  const r = await q.flush();
  assert.strictEqual(r.dropped, 2);
  assert.strictEqual(r.remaining, 0);
});

test('flush: 3xx counts as success (login redirect after POST)', async () => {
  const q = mk(async () => ({ status: 302 }));
  q.enqueue(item());
  assert.strictEqual((await q.flush()).succeeded, 1);
});

test('concurrent flushes run once; items queued mid-flush are kept', async () => {
  let calls = 0;
  let release;
  const gate = new Promise((r) => { release = r; });
  const q = mk(async () => { calls++; await gate; return { status: 200 }; });
  q.enqueue(item({ ts: 1 }));
  const a = q.flush();
  const b = q.flush();
  assert.strictEqual(a, b);
  q.enqueue(item({ ts: 900000, fields: [{ key: 'n', type: 'text', value: 'late' }] }));
  release();
  const r = await a;
  assert.strictEqual(calls, 1);
  assert.strictEqual(r.succeeded, 1);
  assert.strictEqual(r.remaining, 1);
  assert.strictEqual(q.size(), 1);
});

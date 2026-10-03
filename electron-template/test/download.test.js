'use strict';
const test = require('node:test');
const assert = require('node:assert');
const Module = require('module');
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');
const { tmpDir } = require('./helpers');

let behavior = null;
const electronMock = {
  net: {
    request: (o) => {
      const req = new EventEmitter();
      req.followRedirect = () => { req.followed = true; };
      req.abort = () => { req.aborted = true; };
      req.end = () => setImmediate(() => behavior(req, o));
      return req;
    },
  },
};
const realLoad = Module._load;
Module._load = function (request, ...rest) { return request === 'electron' ? electronMock : realLoad.call(this, request, ...rest); };
const { downloadToFile } = require('../src/lib/net');

function respond(req, status, headers, chunks) {
  const res = new EventEmitter();
  res.statusCode = status; res.headers = headers;
  req.emit('response', res);
  let i = 0;
  const next = () => {
    if (i < chunks.length) { res.emit('data', Buffer.from(chunks[i++])); setImmediate(next); } else res.emit('end');
  };
  setImmediate(next);
}

test('streams the body to disk and reports progress', async () => {
  behavior = (req) => respond(req, 200, { 'content-length': '10' }, ['hello', 'world']);
  const dest = path.join(tmpDir('w2a-dl-'), 'f.bin');
  const seen = [];
  await downloadToFile('https://b.test/f', dest, { onProgress: (p) => seen.push(p) });
  assert.strictEqual(fs.readFileSync(dest, 'utf8'), 'helloworld');
  assert.deepStrictEqual(seen, [0.5, 1]);
});

test('non-200 fails and leaves nothing behind', async () => {
  behavior = (req) => respond(req, 404, {}, []);
  const dest = path.join(tmpDir('w2a-dl-'), 'f.bin');
  await assert.rejects(downloadToFile('https://b.test/f', dest), /HTTP 404/);
  assert.ok(!fs.existsSync(dest));
});

test('a redirect to an untrusted host is refused, a trusted one is followed', async () => {
  behavior = (req) => req.emit('redirect', 302, 'GET', 'https://evil.test/x');
  const dir = tmpDir('w2a-dl-');
  await assert.rejects(downloadToFile('https://b.test/f', path.join(dir, 'a'), { onRedirect: (l) => l.startsWith('https://b.test') }), /untrusted/);

  let followed = false;
  behavior = (req) => {
    if (!followed) { followed = true; req.emit('redirect', 302, 'GET', 'https://b.test/real'); setImmediate(() => respond(req, 200, {}, ['ok'])); }
  };
  await downloadToFile('https://b.test/f', path.join(dir, 'b'), { onRedirect: (l) => l.startsWith('https://b.test') });
  assert.strictEqual(fs.readFileSync(path.join(dir, 'b'), 'utf8'), 'ok');
});

test('gives up when the server stalls', async () => {
  behavior = () => {};
  const dest = path.join(tmpDir('w2a-dl-'), 'f.bin');
  await assert.rejects(downloadToFile('https://b.test/f', dest, { stallMs: 30 }), /stalled/);
});

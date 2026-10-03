'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { tmpDir } = require('./helpers');
const { Updater, safeName } = require('../src/lib/updater');

const exe = Buffer.concat([Buffer.from('MZ'), Buffer.from('fake installer body')]);
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');

function make(platform, content, extra) {
  const dir = path.join(tmpDir('w2a-upd-'), 'updates');
  const calls = { spawn: [], open: [], quit: 0, launched: [], changes: 0 };
  const u = new Updater(Object.assign({
    platform, dir,
    download: async (url, dest, onProgress) => { fs.writeFileSync(dest, content); onProgress(0.5); onProgress(1); },
    spawn: (f, a) => calls.spawn.push([f, a]),
    openPath: async (f) => { calls.open.push(f); return ''; },
    quit: () => { calls.quit++; },
    onChange: () => { calls.changes++; },
    onInstallLaunched: (url) => calls.launched.push(url),
  }, extra));
  return { u, calls, dir };
}

test('safeName keeps a sane .exe / .dmg name', () => {
  assert.strictEqual(safeName('https://x.test/builds/35fbb62df464987a.exe', 'win32'), 'update-35fbb62df464987a.exe');
  assert.strictEqual(safeName('https://x.test/dl?id=1', 'win32'), 'update-dl.exe');
  assert.strictEqual(safeName('https://x.test/a/../b%20c.dmg', 'darwin'), 'update-b_20c.dmg');
  assert.ok(!safeName('https://x.test/..%2F..%2Fevil.exe', 'win32').includes('/'));
});

test('Windows: downloads, verifies, restarts into a silent install', async () => {
  const { u, calls } = make('win32', exe);
  assert.strictEqual(await u.start({ url: 'https://x.test/b/app.exe', sha256: sha(exe), size: exe.length }), true);
  assert.strictEqual(u.status, 'ready');
  assert.ok(fs.existsSync(u.file));
  assert.ok(!fs.existsSync(u.file + '.part'));
  assert.strictEqual(await u.installNow(), true);
  assert.deepStrictEqual(calls.spawn[0][1], ['/S', '--updated', '--force-run']);
  assert.strictEqual(calls.quit, 1);
  // closing afterwards must not launch a second installer
  assert.strictEqual(u.installOnQuit(), false);
  assert.strictEqual(calls.spawn.length, 1);
  assert.deepStrictEqual(calls.launched, ['https://x.test/b/app.exe']);
});

test('Windows: a ready update installs by itself when the app is closed', async () => {
  const { u, calls } = make('win32', exe);
  assert.strictEqual(u.installOnQuit(), false, 'nothing downloaded yet');
  await u.start({ url: 'https://x.test/b/app.exe' });
  assert.strictEqual(u.installOnQuit(), true);
  assert.deepStrictEqual(calls.spawn[0][1], ['/S', '--updated']);
  assert.strictEqual(calls.quit, 0);
});

test('rejects a wrong hash, wrong size and a non-installer; leaves no files behind', async () => {
  for (const info of [
    { url: 'https://x.test/a.exe', sha256: 'f'.repeat(64) },
    { url: 'https://x.test/a.exe', size: exe.length + 1 },
  ]) {
    const { u, dir } = make('win32', exe);
    assert.strictEqual(await u.start(info), false);
    assert.strictEqual(u.status, 'error');
    assert.deepStrictEqual(fs.readdirSync(dir), []);
    assert.strictEqual(await u.installNow(), false);
  }
  const { u, dir } = make('win32', Buffer.from('<html>not found</html>'));
  assert.strictEqual(await u.start({ url: 'https://x.test/a.exe' }), false);
  assert.match(u.error, /Not a Windows installer/);
  assert.deepStrictEqual(fs.readdirSync(dir), []);
});

test('a failed download is reported as an error, not thrown', async () => {
  const { u } = make('win32', exe, { download: async () => { throw new Error('boom'); } });
  assert.strictEqual(await u.start({ url: 'https://x.test/a.exe' }), false);
  assert.strictEqual(u.status, 'error');
  assert.strictEqual(u.error, 'boom');
});

test('asking twice for the same update downloads it once', async () => {
  let n = 0;
  const { u } = make('win32', exe, { download: async (url, dest) => { n++; fs.writeFileSync(dest, exe); } });
  await Promise.all([u.start({ url: 'https://x.test/a.exe' }), u.start({ url: 'https://x.test/a.exe' })]);
  await u.start({ url: 'https://x.test/a.exe' });
  assert.strictEqual(n, 1);
});

test('macOS: downloads then opens the file, never spawns or quits', async () => {
  const dmg = Buffer.from('dmg bytes');
  const { u, calls } = make('darwin', dmg);
  await u.start({ url: 'https://x.test/b/app.dmg', sha256: sha(dmg) });
  assert.strictEqual(u.status, 'ready');
  assert.strictEqual(await u.installNow(), true);
  assert.strictEqual(calls.open.length, 1);
  assert.strictEqual(u.installOnQuit(), false);
  assert.strictEqual(calls.spawn.length, 0);
  assert.strictEqual(calls.quit, 0);
});

test('cleanup removes old installers only', async () => {
  const { u, dir } = make('win32', exe);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'update-old.exe'), 'x');
  fs.writeFileSync(path.join(dir, 'keepme.txt'), 'x');
  u.cleanup(null);
  assert.deepStrictEqual(fs.readdirSync(dir), ['keepme.txt']);
});

'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { tmpDir } = require('./helpers');
const { Store } = require('../src/lib/store');
const { AppLock, LOCKOUT_MS } = require('../src/lib/applock');
const { Notifier } = require('../src/lib/notifier');
const { CredentialVault } = require('../src/lib/vault');
const cfg = require('../src/lib/config');

const mkStore = () => new Store(path.join(tmpDir(), 's.json'));

test('store persists and survives a corrupt file', () => {
  const f = path.join(tmpDir(), 's.json');
  const a = new Store(f); a.set('k', { x: 1 });
  assert.deepStrictEqual(new Store(f).get('k'), { x: 1 });
  require('fs').writeFileSync(f, '{bad');
  assert.strictEqual(new Store(f).get('k', 'dflt'), 'dflt');
});

test('app lock: PIN is hashed, verified, throttled, and needs the PIN to disable', () => {
  let t = 1000;
  const s = mkStore();
  const l = new AppLock(s, () => t);
  assert.strictEqual(l.isEnabled(), false);
  assert.strictEqual(l.setPin('12'), false);
  assert.strictEqual(l.setPin('4821'), true);
  assert.ok(!JSON.stringify(s.data).includes('4821'));
  assert.strictEqual(l.isEnabled(), true);
  assert.strictEqual(l.verify('4821').ok, true);
  for (let i = 0; i < 4; i++) assert.strictEqual(l.verify('0000').ok, false);
  const fifth = l.verify('0000');
  assert.strictEqual(fifth.retryInMs, LOCKOUT_MS);
  assert.strictEqual(l.verify('4821').ok, false); // still locked out
  t += LOCKOUT_MS + 1;
  assert.strictEqual(l.disable('9999'), false);
  assert.strictEqual(l.disable('4821'), true);
  assert.strictEqual(l.isEnabled(), false);
});

test('app lock: required-by-build mode forces setup and blocks disabling', () => {
  const s = mkStore();
  const l = new AppLock(s, undefined, true);
  assert.strictEqual(l.isEnabled(), false);
  assert.strictEqual(l.needsSetup(), true); // required, no PIN yet -> the app must force setup
  assert.strictEqual(l.setPin('4821'), true);
  assert.strictEqual(l.needsSetup(), false); // PIN now exists, nothing left to force
  assert.strictEqual(l.verify('4821').ok, true);
  assert.strictEqual(l.disable('4821'), false); // correct PIN, but required installations can't turn it off
  assert.strictEqual(l.isEnabled(), true);
});

test('app lock: not required -> needsSetup is always false, and disable works normally', () => {
  const s = mkStore();
  const l = new AppLock(s, undefined, false);
  assert.strictEqual(l.needsSetup(), false);
  l.setPin('1234');
  assert.strictEqual(l.needsSetup(), false);
  assert.strictEqual(l.disable('1234'), true);
});

test('notifier: fires after the delay, cancel works, overdue restored on start', () => {
  let now = 0; const timers = []; const shown = [];
  const t = { now: () => now, setTimeout: (fn, ms) => { const h = { fn, at: now + ms }; timers.push(h); return h; }, clearTimeout: (h) => { h.dead = true; } };
  const s = mkStore();
  const n = new Notifier(s, (title, body) => shown.push([title, body]), t);
  n.schedule(1, 'Cart', 'Come back', 60);
  n.schedule(2, 'Other', 'x', 30);
  n.cancel(2);
  now = 60000; timers.filter((h) => !h.dead).forEach((h) => h.fn());
  assert.deepStrictEqual(shown, [['Cart', 'Come back']]);
  assert.deepStrictEqual(s.get('scheduled_notifications'), {});

  // restore: one due while closed (delivered), one in the future (re-armed), one ancient (dropped)
  s.set('scheduled_notifications', {
    a: { at: now - 1000, title: 'Missed', body: 'b' },
    b: { at: now + 5000, title: 'Later', body: 'b' },
    c: { at: now - 3 * 24 * 3600 * 1000, title: 'Ancient', body: 'b' },
  });
  shown.length = 0;
  const n2 = new Notifier(s, (title) => shown.push(title), t);
  n2.restore();
  assert.deepStrictEqual(shown, ['Missed']);
  assert.deepStrictEqual(Object.keys(s.get('scheduled_notifications')), ['b']);
});

test('vault: encrypts at rest, round-trips, clears; unavailable encryption saves nothing', () => {
  const fake = { isEncryptionAvailable: () => true, encryptString: (s) => Buffer.from('ENC:' + s.split('').reverse().join('')), decryptString: (b) => b.toString().slice(4).split('').reverse().join('') };
  const s = mkStore();
  const v = new CredentialVault(s, fake);
  assert.strictEqual(v.save('amina@example.com', 'hunter2'), true);
  assert.ok(!JSON.stringify(s.data).includes('hunter2'));
  assert.deepStrictEqual(v.get(), { u: 'amina@example.com', p: 'hunter2' });
  v.clear();
  assert.strictEqual(v.get(), null);
  const none = new CredentialVault(mkStore(), { isEncryptionAvailable: () => false });
  assert.strictEqual(none.save('a', 'b'), false);
  assert.strictEqual(none.get(), null);
});

test('config: normalises values and never throws on a missing file', () => {
  const c = cfg.normalize({ app_url: 'not a url', primary_color: 'red', kiosk_enabled: 'true', nav_items: [{ label: 'A', url: 'https://x.com' }, { label: '', url: 'https://y.com' }, { label: 'B', url: 'javascript:1' }] });
  assert.strictEqual(c.app_url, 'https://example.com/'.replace(/\/$/, '') + '');
  assert.strictEqual(c.primary_color, '#3DDC84');
  assert.strictEqual(c.kiosk_enabled, true);
  assert.strictEqual(c.nav_items.length, 1);
  assert.strictEqual(cfg.loadConfig('/nonexistent/app_config.json').app_name, 'WebApp');
  assert.ok(cfg.sameHost('https://a.com/x', 'http://a.com/y'));
  assert.ok(!cfg.sameHost('https://a.com', 'https://b.com'));
  assert.ok(cfg.isSameSiteHost('example.com', 'cdn.example.com'));
  assert.ok(cfg.isSameSiteHost('www.example.com', 'example.com'));
  assert.ok(!cfg.isSameSiteHost('example.com', 'notexample.com'));
});

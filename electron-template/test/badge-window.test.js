'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { EventEmitter } = require('events');
const { badgePng, badgeLabel, normalizeCount } = require('../src/lib/badge');
const { restoreBounds, trackWindow } = require('../src/lib/window-state');

test('badge label / count normalising', () => {
  assert.strictEqual(normalizeCount(-3), 0);
  assert.strictEqual(normalizeCount('7'), 7);
  assert.strictEqual(normalizeCount(NaN), 0);
  assert.strictEqual(badgeLabel(0), '');
  assert.strictEqual(badgeLabel(42), '42');
  assert.strictEqual(badgeLabel(250), '99+');
});

test('badgePng produces a valid 32x32 PNG, null for zero', () => {
  assert.strictEqual(badgePng(0), null);
  for (const n of [1, 12, 150]) {
    const b = badgePng(n);
    assert.strictEqual(b.slice(1, 4).toString(), 'PNG');
    assert.strictEqual(b.readUInt32BE(16), 32);
    assert.strictEqual(b.readUInt32BE(20), 32);
  }
});

test('restoreBounds ignores bad data and off-screen positions', () => {
  const areas = [{ x: 0, y: 0, width: 1920, height: 1080 }];
  assert.deepStrictEqual(restoreBounds(null, areas), { width: 1200, height: 800, maximized: false });
  assert.deepStrictEqual(restoreBounds({ x: 50, y: 60, width: 900, height: 700, maximized: true }, areas), { width: 900, height: 700, x: 50, y: 60, maximized: true });
  const off = restoreBounds({ x: 5000, y: 60, width: 900, height: 700 }, areas);
  assert.strictEqual(off.x, undefined);
  assert.strictEqual(off.width, 900);
  assert.strictEqual(restoreBounds({ width: 10, height: 10 }, areas).width, 1200);
});

test('trackWindow saves bounds (debounced) and on close', () => {
  const saved = [];
  const store = { set: (k, v) => saved.push([k, v]) };
  const win = Object.assign(new EventEmitter(), {
    isDestroyed: () => false, isMinimized: () => false, isFullScreen: () => false, isMaximized: () => false,
    getBounds: () => ({ x: 1, y: 2, width: 800, height: 600 }),
  });
  let fn = null;
  trackWindow(win, store, 'wb', { setTimeout: (f) => { fn = f; return 1; }, clearTimeout() {} });
  win.emit('resize');
  assert.strictEqual(saved.length, 0);
  fn();
  assert.deepStrictEqual(saved[0], ['wb', { x: 1, y: 2, width: 800, height: 600, maximized: false }]);
  win.emit('close');
  assert.strictEqual(saved.length, 2);
});

'use strict';

/**
 * Remembers the main window's size, position and maximised state between runs.
 * A saved position is only reused if it is still (mostly) on a connected
 * screen - e.g. an unplugged monitor must not leave the window off-screen.
 */

const DEFAULTS = { width: 1200, height: 800 };
const MIN_W = 480;
const MIN_H = 360;

function isNum(v) { return typeof v === 'number' && Number.isFinite(v); }

function overlapsAny(b, areas) {
  return areas.some((a) => {
    const w = Math.min(b.x + b.width, a.x + a.width) - Math.max(b.x, a.x);
    const h = Math.min(b.y + b.height, a.y + a.height) - Math.max(b.y, a.y);
    return w >= 100 && h >= 60; // enough visible to grab the title bar
  });
}

/** -> { width, height, x?, y?, maximized } using only valid saved values. */
function restoreBounds(saved, workAreas) {
  const out = { width: DEFAULTS.width, height: DEFAULTS.height, maximized: false };
  if (!saved || typeof saved !== 'object') return out;
  if (isNum(saved.width) && isNum(saved.height) && saved.width >= MIN_W && saved.height >= MIN_H) {
    out.width = Math.round(saved.width);
    out.height = Math.round(saved.height);
    if (isNum(saved.x) && isNum(saved.y)) {
      const b = { x: Math.round(saved.x), y: Math.round(saved.y), width: out.width, height: out.height };
      if (!workAreas || !workAreas.length || overlapsAny(b, workAreas)) { out.x = b.x; out.y = b.y; }
    }
  }
  out.maximized = saved.maximized === true;
  return out;
}

/** Debounced saver. `getState()` -> { bounds, maximized, fullscreen, minimized } for the window. */
function trackWindow(win, store, key, timers) {
  const setT = (timers && timers.setTimeout) || setTimeout;
  const clearT = (timers && timers.clearTimeout) || clearTimeout;
  let t = null;
  const save = () => {
    try {
      if (win.isDestroyed() || win.isMinimized() || win.isFullScreen() || (win.isKiosk && win.isKiosk())) return;
      const maximized = win.isMaximized();
      // while maximised keep the previous normal bounds
      const b = maximized ? (win.getNormalBounds ? win.getNormalBounds() : win.getBounds()) : win.getBounds();
      store.set(key, { x: b.x, y: b.y, width: b.width, height: b.height, maximized });
    } catch (e) { /* best-effort */ }
  };
  const later = () => { clearT(t); t = setT(save, 400); if (t && t.unref) t.unref(); };
  for (const ev of ['resize', 'move', 'maximize', 'unmaximize']) win.on(ev, later);
  win.on('close', () => { clearT(t); save(); });
  return { saveNow: save };
}

module.exports = { restoreBounds, trackWindow, DEFAULTS };

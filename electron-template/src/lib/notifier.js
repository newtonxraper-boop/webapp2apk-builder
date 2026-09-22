'use strict';

/**
 * Device-only scheduled reminders - the desktop counterpart of
 * LocalNotifyBridge (window.AndroidNotify.schedule / cancel). They fire from
 * the app itself on a timer: no server, no internet needed at delivery time.
 *
 * Unlike the Android alarms, pending reminders are also saved to disk, so one
 * that came due while the app was closed is delivered the next time it opens
 * (if it's less than a day overdue) instead of being lost.
 *
 * `show(title, body)` and the timer functions are injected for testing.
 */

const MAX_TIMEOUT_MS = 2147483647; // setTimeout's hard limit (~24.8 days)
const MAX_OVERDUE_MS = 24 * 60 * 60 * 1000;

class Notifier {
  constructor(store, show, timers) {
    this.store = store;
    this.show = show;
    this.now = (timers && timers.now) || Date.now;
    this.setTimeoutFn = (timers && timers.setTimeout) || setTimeout;
    this.clearTimeoutFn = (timers && timers.clearTimeout) || clearTimeout;
    this.timers = new Map();
  }

  _pending() {
    const p = this.store.get('scheduled_notifications', {});
    return p && typeof p === 'object' ? p : {};
  }

  _savePending(p) {
    this.store.set('scheduled_notifications', p);
  }

  schedule(id, title, body, delaySeconds) {
    id = String(id);
    const delay = Math.max(0, Number(delaySeconds) || 0) * 1000;
    const at = this.now() + delay;
    const pending = this._pending();
    pending[id] = { at, title: String(title || ''), body: String(body || '') };
    this._savePending(pending);
    this._arm(id, at);
  }

  cancel(id) {
    id = String(id);
    if (this.timers.has(id)) {
      this.clearTimeoutFn(this.timers.get(id));
      this.timers.delete(id);
    }
    const pending = this._pending();
    if (pending[id]) {
      delete pending[id];
      this._savePending(pending);
    }
  }

  _arm(id, at) {
    if (this.timers.has(id)) this.clearTimeoutFn(this.timers.get(id));
    const wait = Math.max(0, at - this.now());
    const t = this.setTimeoutFn(() => {
      // setTimeout can't wait longer than ~24.8 days; re-arm for the rest.
      if (this.now() < at - 50) return this._arm(id, at);
      this.timers.delete(id);
      const pending = this._pending();
      const rec = pending[id];
      if (rec) {
        delete pending[id];
        this._savePending(pending);
        try { this.show(rec.title, rec.body); } catch (e) { /* never crash over a reminder */ }
      }
    }, Math.min(wait, MAX_TIMEOUT_MS));
    if (t && t.unref) t.unref();
    this.timers.set(id, t);
  }

  /** Call once at startup: re-arm what's still in the future, deliver what came due while closed. */
  restore() {
    const pending = this._pending();
    const t = this.now();
    let changed = false;
    for (const id of Object.keys(pending)) {
      const rec = pending[id];
      if (!rec || typeof rec.at !== 'number') { delete pending[id]; changed = true; continue; }
      if (rec.at > t) {
        this._arm(id, rec.at);
      } else {
        delete pending[id];
        changed = true;
        if (t - rec.at <= MAX_OVERDUE_MS) {
          try { this.show(rec.title, rec.body); } catch (e) { /* ignore */ }
        }
      }
    }
    if (changed) this._savePending(pending);
  }
}

module.exports = { Notifier, MAX_TIMEOUT_MS, MAX_OVERDUE_MS };

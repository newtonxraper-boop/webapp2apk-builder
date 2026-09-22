'use strict';

/**
 * Effective online/offline state for the app - the desktop counterpart of
 * MainActivity's isCurrentlyOnline + banner logic.
 *
 *   online = the OS reports a network AND the app's own server is reachable
 *
 * The OS flag is polled cheaply. The reachability probe (see reachability.js)
 * is run only when something suggests it might matter - the OS just regained
 * a network, a page failed to load, a navigation got redirected off-site - and
 * then re-run periodically while the app is flagged unreachable, so a captive
 * portal that gets logged into, or a server that comes back, is noticed
 * without the user doing anything. Two failed probes in a row are required
 * before flipping to offline, so one slow response never puts the app into
 * offline mode by accident.
 */

const { EventEmitter } = require('events');

class Connectivity extends EventEmitter {
  /**
   * @param {object} o
   * @param {function} o.isOsOnline  () => boolean
   * @param {function} o.probe       async () => boolean
   * @param {number}  [o.pollMs=3000]
   * @param {number}  [o.recheckMs=15000]  probe interval while flagged unreachable
   * @param {number}  [o.confirmDelayMs=1500]
   * @param {function} [o.sleep]     (ms) => Promise, injectable for tests
   */
  constructor(o) {
    super();
    this.isOsOnline = o.isOsOnline;
    this.probeFn = o.probe;
    this.pollMs = o.pollMs || 3000;
    this.recheckMs = o.recheckMs || 15000;
    this.confirmDelayMs = o.confirmDelayMs === undefined ? 1500 : o.confirmDelayMs;
    this.sleep = o.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));

    this.osOnline = true;
    this.unreachable = false;
    this._last = true;
    this._verifying = null;
    this._pollTimer = null;
    this._recheckTimer = null;
  }

  get online() {
    return this.osOnline && !this.unreachable;
  }

  start() {
    this.osOnline = !!this.isOsOnline();
    this._last = this.online;
    this._pollTimer = setInterval(() => this._poll(), this.pollMs);
    if (this._pollTimer.unref) this._pollTimer.unref();
  }

  stop() {
    clearInterval(this._pollTimer);
    clearTimeout(this._recheckTimer);
    this._pollTimer = null;
    this._recheckTimer = null;
  }

  _poll() {
    const os = !!this.isOsOnline();
    if (os === this.osOnline) return;
    this.osOnline = os;
    if (os) {
      // Network just came back: assume fine, then double-check for a captive
      // portal / dead server right away.
      this.unreachable = false;
      this._emitIfChanged();
      this.verify();
    } else {
      clearTimeout(this._recheckTimer);
      this._emitIfChanged();
    }
  }

  _emitIfChanged() {
    const now = this.online;
    if (now !== this._last) {
      this._last = now;
      this.emit('change', now);
    }
  }

  /** Run the reachability probe and update state. Resolves to the new `online` value. */
  verify() {
    if (this._verifying) return this._verifying;
    this._verifying = (async () => {
      try {
        if (!this.isOsOnline()) {
          this.osOnline = false;
          this._emitIfChanged();
          return this.online;
        }
        this.osOnline = true;
        let ok = await this.probeFn();
        if (!ok) {
          await this.sleep(this.confirmDelayMs);
          ok = await this.probeFn();
        }
        this.unreachable = !ok;
        this._emitIfChanged();
        this._scheduleRecheck();
        return this.online;
      } finally {
        this._verifying = null;
      }
    })();
    return this._verifying;
  }

  /** A page load failed with a network error although the OS says we're online. */
  reportLoadFailure() {
    return this.verify();
  }

  _scheduleRecheck() {
    clearTimeout(this._recheckTimer);
    if (this.osOnline && this.unreachable) {
      this._recheckTimer = setTimeout(() => this.verify(), this.recheckMs);
      if (this._recheckTimer.unref) this._recheckTimer.unref();
    }
  }
}

module.exports = { Connectivity };

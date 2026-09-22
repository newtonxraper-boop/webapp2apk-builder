'use strict';

/**
 * App lock - the desktop counterpart of AppLockManager/LockActivity. Android
 * delegates to the phone's fingerprint/face/PIN; desktops have no equivalent
 * that works on both Windows and macOS, so this uses an app PIN (stored as a
 * salted scrypt hash, never the PIN itself). On macOS the lock screen can
 * additionally offer Touch ID when the Mac supports it (handled in main.js).
 */

const crypto = require('crypto');

const MIN_PIN_LENGTH = 4;
const MAX_PIN_LENGTH = 32;
const MAX_ATTEMPTS_BEFORE_DELAY = 5;
const LOCKOUT_MS = 30000;

class AppLock {
  constructor(store, now) {
    this.store = store;
    this.now = now || Date.now;
    this.failures = 0;
    this.lockedUntil = 0;
  }

  isEnabled() {
    const rec = this.store.get('applock', null);
    return !!(rec && rec.enabled && rec.salt && rec.hash);
  }

  static validPin(pin) {
    return typeof pin === 'string' && pin.length >= MIN_PIN_LENGTH && pin.length <= MAX_PIN_LENGTH;
  }

  _hash(pin, saltHex) {
    return crypto.scryptSync(pin, Buffer.from(saltHex, 'hex'), 32).toString('hex');
  }

  setPin(pin) {
    if (!AppLock.validPin(pin)) return false;
    const salt = crypto.randomBytes(16).toString('hex');
    this.store.set('applock', { enabled: true, salt, hash: this._hash(pin, salt) });
    this.failures = 0;
    this.lockedUntil = 0;
    return true;
  }

  /** @returns {{ok:boolean, retryInMs?:number}} */
  verify(pin) {
    const t = this.now();
    if (t < this.lockedUntil) return { ok: false, retryInMs: this.lockedUntil - t };
    const rec = this.store.get('applock', null);
    if (!rec || !rec.salt || !rec.hash || typeof pin !== 'string') return { ok: false };
    let ok = false;
    try {
      const a = Buffer.from(this._hash(pin, rec.salt), 'hex');
      const b = Buffer.from(rec.hash, 'hex');
      ok = a.length === b.length && crypto.timingSafeEqual(a, b);
    } catch (e) {
      ok = false;
    }
    if (ok) {
      this.failures = 0;
      return { ok: true };
    }
    this.failures++;
    if (this.failures >= MAX_ATTEMPTS_BEFORE_DELAY) {
      this.failures = 0;
      this.lockedUntil = t + LOCKOUT_MS;
      return { ok: false, retryInMs: LOCKOUT_MS };
    }
    return { ok: false };
  }

  disable(pin) {
    if (!this.verify(pin).ok) return false;
    this.store.delete('applock');
    return true;
  }
}

module.exports = { AppLock, MIN_PIN_LENGTH, MAX_PIN_LENGTH, MAX_ATTEMPTS_BEFORE_DELAY, LOCKOUT_MS };

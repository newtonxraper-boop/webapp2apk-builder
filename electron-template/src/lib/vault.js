'use strict';

/**
 * Saved login credentials, encrypted at rest with the operating system's own
 * key store (Windows DPAPI / macOS Keychain, through Electron's safeStorage) -
 * the desktop counterpart of CredentialVault.java. The point is purely to let
 * the app silently re-submit a login when the server forces a fresh one, so
 * the person only types their password again after explicitly logging out
 * (logout calls clear()).
 *
 * `safeStorage` is injected so this can be tested without Electron.
 */

class CredentialVault {
  constructor(store, safeStorage) {
    this.store = store;
    this.safe = safeStorage;
  }

  available() {
    try { return !!(this.safe && this.safe.isEncryptionAvailable()); } catch (e) { return false; }
  }

  save(username, password) {
    if (!username || !password || !this.available()) return false;
    try {
      this.store.set('credentials', {
        u: this.safe.encryptString(String(username)).toString('base64'),
        p: this.safe.encryptString(String(password)).toString('base64'),
      });
      return true;
    } catch (e) {
      return false; // best-effort - auto-login just won't be available next time
    }
  }

  /** Returns {u, p} or null. */
  get() {
    if (!this.available()) return null;
    const rec = this.store.get('credentials', null);
    if (!rec || !rec.u || !rec.p) return null;
    try {
      return {
        u: this.safe.decryptString(Buffer.from(rec.u, 'base64')),
        p: this.safe.decryptString(Buffer.from(rec.p, 'base64')),
      };
    } catch (e) {
      return null;
    }
  }

  clear() {
    this.store.delete('credentials');
  }
}

module.exports = { CredentialVault };

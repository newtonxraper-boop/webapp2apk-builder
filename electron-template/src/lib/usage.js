'use strict';

/**
 * Remembers which pages the person opens most, so the app can pre-load those
 * first. Score = visits, fading with a ~2 week half-life.
 */

const HALF_LIFE_MS = 14 * 24 * 60 * 60 * 1000;

class UsageTracker {
  constructor(store, o) {
    o = o || {};
    this.store = store;
    this.key = o.key || 'page_usage';
    this.max = o.max || 60;
    this.now = o.now || Date.now;
  }

  _data() {
    const d = this.store.get(this.key, {});
    return d && typeof d === 'object' && !Array.isArray(d) ? d : {};
  }

  static normalize(url) {
    try {
      const u = new URL(url);
      if (!/^https?:$/.test(u.protocol)) return null;
      const search = u.search.length <= 60 ? u.search : '';
      return u.origin + u.pathname + search;
    } catch (e) { return null; }
  }

  _score(e) {
    return e.n * Math.pow(0.5, (this.now() - e.last) / HALF_LIFE_MS);
  }

  record(url) {
    const k = UsageTracker.normalize(url);
    if (!k) return;
    const d = this._data();
    const e = d[k] || { n: 0, last: 0 };
    e.n = Math.min(e.n + 1, 1000);
    e.last = this.now();
    d[k] = e;
    const keys = Object.keys(d);
    if (keys.length > this.max) {
      keys.sort((a, b) => this._score(d[b]) - this._score(d[a]));
      for (const x of keys.slice(this.max)) delete d[x];
    }
    this.store.set(this.key, d);
  }

  /** Most-used URLs first; `accept(url)` filters (cacheable, same site...). */
  top(n, accept) {
    const d = this._data();
    return Object.keys(d)
      .filter((u) => !accept || accept(u))
      .sort((a, b) => this._score(d[b]) - this._score(d[a]))
      .slice(0, n);
  }
}

module.exports = { UsageTracker };

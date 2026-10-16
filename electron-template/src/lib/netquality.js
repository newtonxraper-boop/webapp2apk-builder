'use strict';

/**
 * Tracks how fast the connection really is (page-load times + a light probe)
 * and flags it "slow" with hysteresis, so the app can warn the person and
 * switch on Data saver without flickering on one bad sample.
 */

const { EventEmitter } = require('events');

class NetQuality extends EventEmitter {
  constructor(o) {
    super();
    o = o || {};
    this.slowProbeMs = o.slowProbeMs || 1800;
    this.slowLoadMs = o.slowLoadMs || 7000;
    this.alpha = o.alpha || 0.4;
    this.ema = { probe: null, load: null };
    this.slow = !!o.initialSlow;
    this._votes = 0; // consecutive samples that disagree with the current state
  }

  _threshold(kind) { return kind === 'probe' ? this.slowProbeMs : this.slowLoadMs; }

  /** kind: 'probe' | 'load'; ms: how long it took */
  record(kind, ms) {
    if (kind !== 'probe' && kind !== 'load') return;
    ms = Number(ms);
    if (!Number.isFinite(ms) || ms < 0) return;
    ms = Math.min(ms, 60000);
    const prev = this.ema[kind];
    this.ema[kind] = prev === null ? ms : prev + this.alpha * (ms - prev);
    const th = this._threshold(kind);
    const wantSlow = this.slow ? this.ema[kind] > th * 0.7 : this.ema[kind] > th;
    // 'wantSlow' only matters for the sample that just came in
    const disagrees = wantSlow !== this.slow;
    this._votes = disagrees ? this._votes + 1 : 0;
    if (this._votes >= 2) {
      this.slow = wantSlow;
      this._votes = 0;
      this.emit('change', this.slow);
    }
  }

  reset() {
    this.ema = { probe: null, load: null };
    this._votes = 0;
    if (this.slow) { this.slow = false; this.emit('change', false); }
  }
}

module.exports = { NetQuality };

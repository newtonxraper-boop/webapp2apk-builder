'use strict';

/**
 * Taskbar / dock badge helpers (DesktopApp.setBadge(n)).
 *
 * Windows has no "badge count" API - it shows a small overlay icon on the
 * taskbar button instead - so this draws a red dot with the number on it as a
 * PNG, with no native dependencies (pure JS + zlib).
 */

const zlib = require('zlib');

const GLYPHS = {
  '0': ['111', '101', '101', '101', '111'], '1': ['010', '110', '010', '010', '111'],
  '2': ['111', '001', '111', '100', '111'], '3': ['111', '001', '111', '001', '111'],
  '4': ['101', '101', '111', '001', '001'], '5': ['111', '100', '111', '001', '111'],
  '6': ['111', '100', '111', '101', '111'], '7': ['111', '001', '001', '001', '001'],
  '8': ['111', '101', '111', '101', '111'], '9': ['111', '101', '111', '001', '111'],
  '+': ['000', '010', '111', '010', '000'],
};

function normalizeCount(n) {
  n = Math.floor(Number(n));
  return Number.isFinite(n) && n > 0 ? Math.min(n, 1e9) : 0;
}

function badgeLabel(n) {
  n = normalizeCount(n);
  if (!n) return '';
  return n > 99 ? '99+' : String(n);
}

let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = [];
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[i] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function encodePng(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 32x32 PNG: red disc with the count in white. Returns a Buffer ('' count -> null). */
function badgePng(n) {
  const label = badgeLabel(n);
  if (!label) return null;
  const S = 32;
  const px = Buffer.alloc(S * S * 4);
  const set = (x, y, r, g, b, a) => {
    if (x < 0 || y < 0 || x >= S || y >= S) return;
    const i = (y * S + x) * 4;
    px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
  };
  // disc with soft edge
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const d = Math.hypot(x + 0.5 - S / 2, y + 0.5 - S / 2);
      if (d <= S / 2 - 0.5) set(x, y, 0xe5, 0x39, 0x35, 255);
      else if (d <= S / 2 + 0.5) set(x, y, 0xe5, 0x39, 0x35, Math.round(255 * (S / 2 + 0.5 - d)));
    }
  }
  const scale = label.length === 1 ? 4 : (label.length === 2 ? 3 : 2);
  const gap = scale;
  const textW = label.length * 3 * scale + (label.length - 1) * gap;
  const textH = 5 * scale;
  let ox = Math.round((S - textW) / 2);
  const oy = Math.round((S - textH) / 2);
  for (const ch of label) {
    const g = GLYPHS[ch];
    for (let r = 0; r < 5; r++) {
      for (let c = 0; c < 3; c++) {
        if (g[r][c] !== '1') continue;
        for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) set(ox + c * scale + dx, oy + r * scale + dy, 255, 255, 255, 255);
      }
    }
    ox += 3 * scale + gap;
  }
  return encodePng(S, S, px);
}

module.exports = { normalizeCount, badgeLabel, badgePng, encodePng };

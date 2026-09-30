'use strict';

/**
 * Loads src/app_config.json (written at build time by
 * scripts/generate_desktop_resources.js - the desktop counterpart of the
 * Android build's assets/app_config.json) and provides the small set of
 * host-comparison helpers every other module shares, so "is this URL part of
 * the app?" has exactly one definition.
 */

const fs = require('fs');
const path = require('path');

const DEFAULTS = Object.freeze({
  app_name: 'WebApp',
  app_url: 'https://example.com',
  package_name: 'com.webapp2apk.webapp',
  primary_color: '#3DDC84',
  accent_color: '#3DDC84',
  splash_enabled: true,
  push_enabled: false,
  filecamera_enabled: true,
  kiosk_enabled: false,
  applock_enabled: false,
  remember_login_enabled: true,
  crash_report_url: '',
  privacy_policy_url: '',
  nav_items: [],
  version_code: 0,
});

function asBool(v, fallback) {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (s === 'true' || s === '1') return true;
    if (s === 'false' || s === '0') return false;
  }
  return fallback;
}

function asHexColor(v, fallback) {
  return typeof v === 'string' && /^#[0-9A-Fa-f]{6}$/.test(v.trim()) ? v.trim().toUpperCase() : fallback;
}

function asHttpUrl(v, fallback) {
  try {
    const u = new URL(String(v).trim());
    if (u.protocol === 'http:' || u.protocol === 'https:') return u.toString();
  } catch (e) { /* fall through */ }
  return fallback;
}

function normalizeNavItems(items) {
  if (!Array.isArray(items)) return [];
  const out = [];
  for (const it of items.slice(0, 5)) {
    if (!it || typeof it !== 'object') continue;
    const url = asHttpUrl(it.url, null);
    const label = typeof it.label === 'string' ? it.label.slice(0, 20) : '';
    if (!url || !label) continue;
    out.push({ label, url, icon: typeof it.icon === 'string' ? it.icon : '' });
  }
  return out;
}

function normalize(raw) {
  const r = raw && typeof raw === 'object' ? raw : {};
  const primary = asHexColor(r.primary_color, DEFAULTS.primary_color);
  return {
    app_name: typeof r.app_name === 'string' && r.app_name.trim() ? r.app_name.trim() : DEFAULTS.app_name,
    app_url: asHttpUrl(r.app_url, DEFAULTS.app_url),
    package_name: typeof r.package_name === 'string' && r.package_name.trim() ? r.package_name.trim() : DEFAULTS.package_name,
    primary_color: primary,
    accent_color: asHexColor(r.accent_color, primary),
    splash_enabled: asBool(r.splash_enabled, DEFAULTS.splash_enabled),
    push_enabled: asBool(r.push_enabled, DEFAULTS.push_enabled),
    filecamera_enabled: asBool(r.filecamera_enabled, DEFAULTS.filecamera_enabled),
    kiosk_enabled: asBool(r.kiosk_enabled, DEFAULTS.kiosk_enabled),
    applock_enabled: asBool(r.applock_enabled, DEFAULTS.applock_enabled),
    remember_login_enabled: asBool(r.remember_login_enabled, DEFAULTS.remember_login_enabled),
    crash_report_url: typeof r.crash_report_url === 'string' ? r.crash_report_url.trim() : '',
    privacy_policy_url: typeof r.privacy_policy_url === 'string' ? r.privacy_policy_url.trim() : '',
    nav_items: normalizeNavItems(r.nav_items),
    version_code: Number.isFinite(Number(r.version_code)) ? Number(r.version_code) : 0,
  };
}

function loadConfig(file) {
  const target = file || path.join(__dirname, '..', 'app_config.json');
  try {
    return normalize(JSON.parse(fs.readFileSync(target, 'utf8')));
  } catch (e) {
    // A missing or corrupt config must never stop the app from opening -
    // fall back to defaults (which point at example.com, an obvious sign
    // the generator step didn't run).
    return normalize({});
  }
}

function hostOf(url) {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch (e) {
    return null;
  }
}

/** Exact same hostname (the APK's rule for caching, injection and interception). */
function sameHost(a, b) {
  const ha = hostOf(a);
  const hb = hostOf(b);
  return !!ha && ha === hb;
}

/**
 * Same site or a subdomain of it (either direction), e.g. cdn.example.com
 * counts as example.com. Anything else - like a carrier's own domain - is an
 * off-site redirect. Mirrors MainActivity.isSameSiteHost().
 */
function isSameSiteHost(requestedHost, actualHost) {
  if (!requestedHost || !actualHost) return false;
  const a = String(requestedHost).toLowerCase();
  const b = String(actualHost).toLowerCase();
  if (a === b) return true;
  return b.endsWith('.' + a) || a.endsWith('.' + b);
}

function isHttpUrl(url) {
  try {
    const p = new URL(url).protocol;
    return p === 'http:' || p === 'https:';
  } catch (e) {
    return false;
  }
}

module.exports = { DEFAULTS, normalize, loadConfig, hostOf, sameHost, isSameSiteHost, isHttpUrl };

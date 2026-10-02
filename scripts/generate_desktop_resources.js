/**
 * generate_desktop_resources.js - the desktop-build equivalent of
 * generate_resources.py.
 *
 *  1. Writes electron-template/src/app_config.json from the environment
 *     variables set by the GitHub Actions workflow (the desktop counterpart of
 *     the Android build's assets/app_config.json). The app reads everything
 *     else it needs from that one file at runtime, so no other source file
 *     needs templating (and no value can ever break out of a string literal).
 *  2. Fills the three {{PLACEHOLDER}} tokens in package.json (product name,
 *     npm name, appId) - JSON-escaped.
 *  3. Downloads + resizes the uploaded icon into build/icon.png (electron-builder
 *     derives the platform .ico/.icns from that single square PNG) and copies
 *     it to src/icon.png for the splash screen and window icon.
 *
 * Written in Node (not Python) so it runs identically on the windows-latest
 * and macos-latest runners.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');

const TEMPLATE_ROOT = path.join(__dirname, '..', 'electron-template');
const PACKAGE_JSON = path.join(TEMPLATE_ROOT, 'package.json');
const CONFIG_JSON = path.join(TEMPLATE_ROOT, 'src', 'app_config.json');
const BUILD_ICON = path.join(TEMPLATE_ROOT, 'build', 'icon.png');
const SRC_ICON = path.join(TEMPLATE_ROOT, 'src', 'icon.png');

function env(name, fallback) {
  const v = process.env[name];
  return v === undefined || v === null || v === '' ? fallback : v;
}

function sanitizeSegment(text) {
  let seg = (text || '').replace(/[^a-zA-Z0-9_]/g, '').toLowerCase();
  if (!seg || !/^[a-z_]/.test(seg)) seg = 'a' + seg;
  return seg;
}

function deriveSlug(appName) {
  const seg = sanitizeSegment(appName) || 'app';
  return seg.replace(/^a(?=[0-9])/, 'app'); // avoid ugly "a123" npm names
}

function validatePackageName(pkg) {
  const segments = pkg.split('.').map(sanitizeSegment).filter(Boolean);
  if (segments.length < 2) return ['com', 'webapp2apk'].concat(segments).join('.');
  return segments.join('.');
}

function derivePackageName(appName) {
  return `com.webapp2apk.${sanitizeSegment(appName)}`;
}

function sanitizeHexColor(value, fallback) {
  const v = (value || '').trim();
  return /^#[0-9A-Fa-f]{6}$/.test(v) ? v.toUpperCase() : fallback;
}

function boolValue(name, fallback) {
  const val = (process.env[name] || '').trim().toLowerCase();
  if (val === 'true' || val === '1') return true;
  if (val === 'false' || val === '0') return false;
  return !!fallback;
}

function cleanUrl(value) {
  return (value || '').trim().replace(/[\r\n]/g, '');
}

// Decodes trigger.php's base64-encoded nav_items JSON (identical payload to
// what the Android build's write_nav_items() consumes).
function decodeNavItems(navItemsB64) {
  if (!navItemsB64) return [];
  try {
    const parsed = JSON.parse(Buffer.from(navItemsB64, 'base64').toString('utf8'));
    if (!Array.isArray(parsed)) return [];
    return parsed.slice(0, 5)
      .filter((i) => i && typeof i === 'object' && i.label && i.url)
      .map((i) => ({ label: String(i.label).slice(0, 20), url: String(i.url), icon: i.icon ? String(i.icon) : '' }));
  } catch (e) {
    console.log(`WARNING: could not decode NAV_ITEMS_B64, defaulting to no tabs: ${e.message}`);
    return [];
  }
}

function downloadBuffer(url) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https:') ? https : http;
    const req = lib.get(url, { headers: { 'User-Agent': 'webapp2apk-desktop-builder' } }, (res) => {
      if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        downloadBuffer(res.headers.location).then(resolve, reject);
        return;
      }
      if (res.statusCode !== 200) {
        reject(new Error(`HTTP ${res.statusCode} fetching ${url}`));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    });
    req.on('error', reject);
    req.setTimeout(20000, () => req.destroy(new Error('Timed out downloading icon')));
  });
}

async function processIcon(iconUrl) {
  if (!iconUrl) {
    console.log('No icon_url provided - keeping the default placeholder icon.');
    return false;
  }

  let raw;
  try {
    raw = await downloadBuffer(iconUrl);
  } catch (e) {
    console.log(`WARNING: could not download icon from ${iconUrl}: ${e.message}`);
    return false;
  }

  let Jimp;
  try {
    Jimp = require('jimp');
  } catch (e) {
    console.log('WARNING: jimp is not installed - skipping icon processing.');
    return false;
  }

  try {
    const image = await Jimp.read(raw);
    const side = Math.min(image.bitmap.width, image.bitmap.height);
    image
      .crop((image.bitmap.width - side) / 2, (image.bitmap.height - side) / 2, side, side)
      .resize(1024, 1024, Jimp.RESIZE_BILINEAR);
    await image.writeAsync(BUILD_ICON);
    console.log('Custom app icon applied (1024x1024, electron-builder will derive .ico/.icns from it).');
    return true;
  } catch (e) {
    console.log(`WARNING: downloaded icon is not a valid image: ${e.message}`);
    return false;
  }
}

async function main() {
  const appName = env('APP_NAME', 'WebApp').trim() || 'WebApp';
  const appUrl = env('APP_URL', 'https://example.com').trim() || 'https://example.com';
  const iconUrl = env('ICON_URL', '').trim();
  const primaryColor = sanitizeHexColor(env('PRIMARY_COLOR', ''), '#3DDC84');
  const accentColor = sanitizeHexColor(env('ACCENT_COLOR', ''), primaryColor);

  const rawPackage = env('PACKAGE_NAME', '').trim();
  const packageName = rawPackage ? validatePackageName(rawPackage) : derivePackageName(appName);
  const packageSlug = deriveSlug(appName);

  const cfg = {
    app_name: appName,
    app_url: appUrl,
    package_name: packageName,
    primary_color: primaryColor,
    accent_color: accentColor,
    splash_enabled: boolValue('SPLASH_ENABLED', true),
    push_enabled: boolValue('PUSH_ENABLED', false),
    filecamera_enabled: boolValue('FILECAMERA_ENABLED', true),
    kiosk_enabled: boolValue('KIOSK_ENABLED', false),
    applock_enabled: boolValue('APPLOCK_ENABLED', false),
    remember_login_enabled: boolValue('REMEMBER_LOGIN_ENABLED', true),
    external_links_in_app: boolValue('EXTERNAL_LINKS_IN_APP', false),
    update_check_url: cleanUrl(env('UPDATE_CHECK_URL', '')),
    build_id: /^[A-Za-z0-9_-]{1,64}$/.test(env('BUILD_ID', '').trim()) ? env('BUILD_ID', '').trim() : '',
    crash_report_url: cleanUrl(env('CRASH_REPORT_URL', '')),
    privacy_policy_url: cleanUrl(env('PRIVACY_POLICY_URL', '')),
    nav_items: decodeNavItems(env('NAV_ITEMS_B64', '').trim()),
    // Same idea as the Android build's versionCode: a build timestamp, so the
    // app's "a newer version exists" check (version.json) can compare it.
    version_code: Math.floor(Date.now() / 1000),
  };
  fs.writeFileSync(CONFIG_JSON, JSON.stringify(cfg, null, 2) + '\n', 'utf8');

  // package.json placeholders (values JSON-escaped so a quote in the app name can't break the file)
  const esc = (s) => JSON.stringify(String(s)).slice(1, -1);
  let pkg = fs.readFileSync(PACKAGE_JSON, 'utf8');
  pkg = pkg
    .split('{{APP_NAME}}').join(esc(appName))
    .split('{{PACKAGE_NAME}}').join(esc(packageName))
    .split('{{PACKAGE_SLUG}}').join(esc(packageSlug));
  JSON.parse(pkg); // fail the build here, loudly, rather than deep inside electron-builder
  fs.writeFileSync(PACKAGE_JSON, pkg, 'utf8');

  const iconApplied = await processIcon(iconUrl);
  try {
    fs.copyFileSync(BUILD_ICON, SRC_ICON);
  } catch (e) {
    console.log(`WARNING: could not copy icon into src/: ${e.message}`);
  }

  console.log('Resources generated successfully under electron-template/');
  console.log(`App name: ${appName}`);
  console.log(`App URL: ${appUrl}`);
  console.log(`Package name (appId): ${packageName}`);
  console.log(`Primary color: ${primaryColor}  Accent: ${accentColor}`);
  console.log(`splash_enabled=${cfg.splash_enabled} push_enabled=${cfg.push_enabled} filecamera_enabled=${cfg.filecamera_enabled} kiosk_enabled=${cfg.kiosk_enabled} applock_enabled=${cfg.applock_enabled} remember_login_enabled=${cfg.remember_login_enabled}`);
  console.log(`Nav tabs: ${JSON.stringify(cfg.nav_items)}`);
  console.log(`Custom icon applied: ${iconApplied}`);
  console.log(`version_code: ${cfg.version_code}`);
}

main().catch((e) => {
  console.error('generate_desktop_resources.js failed:', e);
  process.exit(1);
});

/**
 * generate_desktop_resources.js - the desktop-build equivalent of
 * generate_resources.py. Fills {{PLACEHOLDER}} tokens in electron-template/
 * (package.json, main.js, offline.html) from environment variables set by
 * the GitHub Actions workflow, and downloads+resizes the uploaded icon into
 * build/icon.png (electron-builder auto-generates the platform-specific
 * .ico/.icns from that single square PNG - no separate icon step needed).
 *
 * Written in Node (not Python) so it runs identically on both the
 * windows-latest and macos-latest runners without worrying about which
 * "python"/"python3" alias exists on each image.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');

const TEMPLATE_ROOT = path.join(__dirname, '..', 'electron-template');
const TEXT_FILES = [
  path.join(TEMPLATE_ROOT, 'package.json'),
  path.join(TEMPLATE_ROOT, 'src', 'main.js'),
  path.join(TEMPLATE_ROOT, 'src', 'offline.html'),
];

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
  const destPath = path.join(TEMPLATE_ROOT, 'build', 'icon.png');
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
    console.log('WARNING: jimp is not installed - skipping icon processing. ' +
      'Make sure "npm install --no-save jimp" runs before this script.');
    return false;
  }

  try {
    const image = await Jimp.read(raw);
    const side = Math.min(image.bitmap.width, image.bitmap.height);
    image
      .crop((image.bitmap.width - side) / 2, (image.bitmap.height - side) / 2, side, side)
      .resize(1024, 1024, Jimp.RESIZE_BILINEAR);
    await image.writeAsync(destPath);
    console.log('Custom app icon applied (1024x1024, electron-builder will derive .ico/.icns from it).');
    return true;
  } catch (e) {
    console.log(`WARNING: downloaded icon is not a valid image: ${e.message}`);
    return false;
  }
}

function applyReplacements(replacements) {
  let updated = 0;
  for (const filePath of TEXT_FILES) {
    if (!fs.existsSync(filePath)) continue;
    let content = fs.readFileSync(filePath, 'utf8');
    const original = content;
    for (const [placeholder, value] of Object.entries(replacements)) {
      content = content.split(placeholder).join(value);
    }
    if (content !== original) {
      fs.writeFileSync(filePath, content, 'utf8');
      updated++;
    }
  }
  return updated;
}

async function main() {
  const appName = env('APP_NAME', 'WebApp').trim() || 'WebApp';
  const appUrl = env('APP_URL', 'https://example.com').trim() || 'https://example.com';
  const iconUrl = env('ICON_URL', '').trim();
  const primaryColor = sanitizeHexColor(env('PRIMARY_COLOR', ''), '#3DDC84');

  const rawPackage = env('PACKAGE_NAME', '').trim();
  const packageName = rawPackage ? validatePackageName(rawPackage) : derivePackageName(appName);
  const packageSlug = deriveSlug(appName);

  const replacements = {
    '{{APP_NAME}}': appName,
    '{{APP_URL}}': appUrl,
    '{{PACKAGE_NAME}}': packageName,
    '{{PACKAGE_SLUG}}': packageSlug,
    '{{PRIMARY_COLOR}}': primaryColor,
  };

  const updated = applyReplacements(replacements);
  const iconApplied = await processIcon(iconUrl);

  console.log('Resources generated successfully under electron-template/');
  console.log(`App name: ${appName}`);
  console.log(`App URL: ${appUrl}`);
  console.log(`Package name (appId): ${packageName}`);
  console.log(`Primary color: ${primaryColor}`);
  console.log(`Custom icon applied: ${iconApplied}`);
  console.log(`Text files updated: ${updated}`);
}

main().catch((e) => {
  console.error('generate_desktop_resources.js failed:', e);
  process.exit(1);
});

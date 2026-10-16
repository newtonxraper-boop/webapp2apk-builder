'use strict';

/**
 * Data saver rules. Pure functions so they can be tested without Electron.
 * When active the app: blocks video/audio downloads, embedded video players
 * from big video sites, and very large images, and sends "Save-Data: on".
 */

const VIDEO_HOSTS = /(^|\.)(youtube\.com|youtube-nocookie\.com|youtu\.be|vimeo\.com|tiktok\.com|dailymotion\.com)$/i;
const MAX_IMAGE_BYTES = 400 * 1024;

function hostOfUrl(u) {
  try { return new URL(u).hostname; } catch (e) { return ''; }
}

/** onBeforeRequest: true = cancel the request */
function shouldBlockRequest(details, appHost) {
  if (!details) return false;
  const t = details.resourceType;
  if (t === 'media') return true;
  if (t === 'subFrame') {
    const h = hostOfUrl(details.url);
    return !!h && h !== appHost && VIDEO_HOSTS.test(h);
  }
  return false;
}

function headerValue(headers, name) {
  if (!headers) return undefined;
  const k = Object.keys(headers).find((x) => x.toLowerCase() === name);
  if (!k) return undefined;
  const v = headers[k];
  return Array.isArray(v) ? v[0] : v;
}

/** onHeadersReceived: true = cancel (image bigger than the limit) */
function shouldBlockByHeaders(details) {
  if (!details || details.resourceType !== 'image') return false;
  const len = Number(headerValue(details.responseHeaders, 'content-length'));
  return Number.isFinite(len) && len > MAX_IMAGE_BYTES;
}

module.exports = { shouldBlockRequest, shouldBlockByHeaders, MAX_IMAGE_BYTES, VIDEO_HOSTS };

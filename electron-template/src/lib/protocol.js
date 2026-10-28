'use strict';

/**
 * "Continue with Google" inside the desktop app.
 *
 * Google refuses to sign people in inside an embedded browser, so the app
 * opens the Google page in the person's REAL browser instead. The site then
 * hands the finished login back with a link like
 *
 *     <scheme>://auth?url=https://yoursite/login.php?app_token=...
 *
 * which the operating system delivers to this app (we register <scheme> as the
 * app's own protocol). The app opens that address - only if it is on the
 * app's own site - inside its own window, where the site swaps the one-time
 * token for a normal sign-in. Same idea as the Android app.
 *
 * Everything here is pure (no Electron), so it can be unit tested.
 */

const { isHttpUrl, hostOf, isSameSiteHost } = require('./config');

/**
 * The custom URL scheme for a package name. URL schemes may only contain
 * letters, digits, "+", "-" and "."; package names may contain "_", which
 * becomes "-". Must start with a letter. The site receives this same value in
 * the X-Requested-With header and uses it to build the hand-back link, so the
 * build script (scripts/generate_desktop_resources.js) uses the same rule.
 */
function protocolScheme(packageName) {
  let s = String(packageName || '').toLowerCase().replace(/_/g, '-').replace(/[^a-z0-9+.\-]/g, '');
  if (!/^[a-z]/.test(s)) s = 'app' + s;
  return s.slice(0, 100) || 'webapp';
}

/**
 * If `link` is a valid hand-back link for this app, returns the web address to
 * open inside the app; otherwise null. Only http(s) addresses on the app's own
 * site are ever returned.
 */
function parseAuthLink(link, scheme, appUrl) {
  if (typeof link !== 'string' || !scheme) return null;
  let u;
  try { u = new URL(link.trim().replace(/^"+|"+$/g, '')); } catch (e) { return null; }
  if (u.protocol !== scheme + ':') return null;
  if ((u.hostname || '').toLowerCase() !== 'auth') return null;
  const target = u.searchParams.get('url');
  if (!target || !isHttpUrl(target)) return null;
  if (!isSameSiteHost(hostOf(appUrl), hostOf(target))) return null;
  return target;
}

/** The hand-back link in a list of command-line arguments (Windows / Linux), or null. */
function authLinkFromArgv(argv, scheme, appUrl) {
  for (const a of argv || []) {
    if (typeof a === 'string' && a.toLowerCase().startsWith(scheme + '://')) {
      const t = parseAuthLink(a, scheme, appUrl);
      if (t) return t;
    }
  }
  return null;
}

const GOOGLE_AUTH_HOSTS = ['accounts.google.com'];

/**
 * True when `url` is Google's sign-in page for a *server-side* OAuth flow of
 * this app's own site: i.e. its redirect_uri points back at the app's site.
 * Those are the flows the site can finish by handing a token back to the app.
 * Other Google sign-ins (a Google button run entirely by JavaScript, popups...)
 * are left alone and keep working inside the app exactly as before.
 */
function isGoogleHandoffUrl(url, appUrl) {
  if (!isHttpUrl(url)) return false;
  let u;
  try { u = new URL(url); } catch (e) { return false; }
  if (!GOOGLE_AUTH_HOSTS.includes(u.hostname.toLowerCase())) return false;
  if (!/^\/(o\/oauth2|signin\/oauth|v3\/signin|oauth2)/i.test(u.pathname)) return false;
  const redirect = u.searchParams.get('redirect_uri');
  if (!redirect || !isHttpUrl(redirect)) return false;
  return isSameSiteHost(hostOf(appUrl), hostOf(redirect));
}

module.exports = { protocolScheme, parseAuthLink, authLinkFromArgv, isGoogleHandoffUrl };

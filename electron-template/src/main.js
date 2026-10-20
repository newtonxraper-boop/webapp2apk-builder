'use strict';

/**
 * Main process for the Windows (.exe) and macOS (.dmg) builds.
 *
 * Behaves like the Android build of the same app:
 *   - offline page cache, offline banner, offline submission queue with a
 *     "waiting to sync" banner + Send now, reachability probe / captive-portal
 *     protection, nav-tab prefetch (lib/*, this file)
 *   - Settings (App lock, Kiosk mode, Notifications, Offline sync, Storage,
 *     Version), share / refresh / settings buttons, bottom tabs, splash,
 *     update banner, crash reports (shell.html)
 *   - page-facing bridges with the same names as the APK's (AndroidShare,
 *     AndroidPrint, AndroidScanQR, AndroidNotify, AndroidLocation,
 *     AndroidNfc, AndroidRetry) - see guest-preload.js
 *
 * All build-time values come from src/app_config.json (written by
 * scripts/generate_desktop_resources.js).
 */

const {
  app, BrowserWindow, Menu, shell, ipcMain, session, dialog, Notification,
  clipboard, systemPreferences, ShareMenu, powerMonitor, net, safeStorage, webContents,
  Tray, nativeImage, screen,
} = require('electron');
const path = require('path');
const fs = require('fs');

const { loadConfig, hostOf, sameHost, isSameSiteHost, isHttpUrl, siteDomain } = require('./lib/config');
const { Store } = require('./lib/store');
const { restoreBounds, trackWindow } = require('./lib/window-state');
const { normalizeCount, badgeLabel, badgePng } = require('./lib/badge');
const { NetQuality } = require('./lib/netquality');
const { shouldBlockRequest, shouldBlockByHeaders } = require('./lib/datasaver');
const { UsageTracker } = require('./lib/usage');
const { PageCache, isSensitiveUrl } = require('./lib/offline-cache');
const { AssetCache } = require('./lib/asset-cache');
const { OfflineQueue } = require('./lib/offline-queue');
const { Connectivity } = require('./lib/connectivity');
const { probe } = require('./lib/reachability');
const { CredentialVault } = require('./lib/vault');
const { AppLock } = require('./lib/applock');
const { Notifier } = require('./lib/notifier');
const { buildInjection } = require('./lib/page-script');
const netutil = require('./lib/net');
const { Updater } = require('./lib/updater');

const config = loadConfig();
const PARTITION = 'persist:webapp';
const GUEST_PRELOAD = path.join(__dirname, 'guest-preload.js');
const SHELL_PRELOAD = path.join(__dirname, 'shell-preload.js');
const OFFLINE_PAGE = path.join(__dirname, 'offline.html');

const MAX_QUEUEABLE_FILE_BYTES = 4 * 1024 * 1024;
const MAX_SHARE_FILE_BYTES = 8 * 1024 * 1024;
const FLUSH_RETRY_DELAYS_MS = [5000, 15000, 30000, 60000];
const UPDATE_CHECK_MIN_INTERVAL_MS = 60 * 60 * 1000;
const RELOCK_AFTER_BLUR_MS = 60 * 1000;

let mainWindow = null;
let tray = null;
let isQuitting = false; // true once the app is really quitting (Tray > Quit, Cmd+Q, update install...)
let badgeCount = 0;
const startedHidden = process.argv.includes('--hidden'); // launched by 'Open when the computer starts'
let guest = null; // the ACTIVE page's <webview> webContents
const guests = new Set(); // every live page (pages are kept alive in the background so Back/Forward don't reload)

// Populated in start() once the app is ready.
let store = null;
let ses = null;
let cache = null;
let assetCache = null;
let queue = null;
let connectivity = null;
let vault = null;
let applock = null;
let notifier = null;
let updater = null;
let netQuality = null;
let usage = null;

const state = {
  queueSize: 0,
  lastSyncError: null,
  updateUrl: null,
  updateStatus: 'idle', // 'idle' | 'downloading' | 'ready' | 'error'
  updateProgress: 0,
  locked: false,
  view: 'live', // 'live' | 'cached' | 'offline-page'
  showingCachedFor: null,
  lastCacheMissUrl: null,
  lastRequestedUrl: null,
  lastPageUrl: null,
  pendingCredentials: null,
  skipAutoLoginOnce: false,
  prefetchDone: false,
};

// ---------------------------------------------------------------------------
// small helpers
// ---------------------------------------------------------------------------

function isLive(wc) {
  return !!wc && !wc.isDestroyed();
}

function sendShell(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed() && isLive(mainWindow.webContents)) {
    mainWindow.webContents.send(channel, payload);
  }
}

function toast(message) {
  sendShell('shell:toast', String(message));
}

function isOnline() {
  return connectivity ? connectivity.online : true;
}

function isKiosk() {
  if (!config.kiosk_enabled) return false; // feature not included in this build
  return store.has('kiosk_override') ? !!store.get('kiosk_override') : !!config.kiosk_enabled;
}

// ----- data saver: 'off' | 'auto' (on while the connection is slow) | 'on' -----
function dataSaverMode() {
  const m = store.get('data_saver', 'auto');
  return m === 'on' || m === 'off' ? m : 'auto';
}

function dataSaverActive() {
  if (!store) return false;
  const m = dataSaverMode();
  return m === 'on' || (m === 'auto' && !!(netQuality && netQuality.slow));
}

// Tell every open page (so the site can adapt: DesktopApp.isSlow / dataSaver).
function broadcastNetToGuests() {
  const payload = { slow: !!(netQuality && netQuality.slow), saver: dataSaverActive() };
  for (const g of guests) { if (isLive(g)) { try { g.send('w2a:net', payload); } catch (e) { /* ignore */ } } }
}

function onNetQualityChange() {
  try { store.set('last_net_slow', !!netQuality.slow); } catch (e) { /* ignore */ }
  broadcastState();
  broadcastNetToGuests();
}

function notificationsAllowed() {
  return store.get('notifications', true) !== false;
}

function publicState() {
  return {
    online: isOnline(),
    queueSize: state.queueSize,
    lastSyncError: state.lastSyncError,
    updateUrl: state.updateUrl,
    updateStatus: state.updateStatus,
    updateProgress: state.updateProgress,
    locked: state.locked,
    lockSetupRequired: !!(config.applock_enabled && applock && applock.needsSetup()),
    kiosk: isKiosk(),
    slow: !!(netQuality && netQuality.slow),
    dataSaver: dataSaverActive(),
  };
}

function broadcastState() {
  sendShell('shell:state', publicState());
}

function broadcastOnlineToGuest() {
  if (isLive(guest)) guest.send('w2a:online', isOnline());
}

function refreshQueueSize() {
  state.queueSize = queue ? queue.size() : 0;
}

function isNetworkErrorCode(code) {
  // Chromium net errors: -100..-199 are connection-level failures
  // (refused, reset, timed out, name not resolved, internet disconnected...),
  // plus -7 (timed out) and -21 (network changed). -2xx are certificate
  // errors and must NOT be treated as "offline".
  return (code <= -100 && code >= -199) || code === -7 || code === -21;
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function isAllowedTarget(url) {
  return isHttpUrl(url) && isSameSiteHost(hostOf(config.app_url), hostOf(url));
}

// Login / sign-in flows must stay inside the app: the site's own login
// buttons often bounce through www<->apex, a login subdomain, or a social /
// SSO provider (Google, Facebook, Apple, Microsoft, GitHub...). Sending those
// to the default browser breaks the login (the session lives in the app).
const AUTH_HOSTS = [
  'accounts.google.com', 'google.com', 'facebook.com', 'appleid.apple.com',
  'login.microsoftonline.com', 'login.live.com', 'github.com', 'twitter.com',
  'x.com', 'linkedin.com', 'auth0.com', 'okta.com', 'firebaseapp.com',
  'clerk.accounts.dev', 'amazoncognito.com',
];
const AUTH_PATH_RE = /(^|[\/_.-])(login|log-in|signin|sign-in|signup|sign-up|register|oauth2?|authorize|auth|sso|callback|account)([\/_.-]|$)/i;

function isAuthUrl(url) {
  if (!isHttpUrl(url)) return false;
  let u;
  try { u = new URL(url); } catch (e) { return false; }
  const h = u.hostname.toLowerCase();
  if (!AUTH_HOSTS.some((a) => h === a || h.endsWith('.' + a))) return false;
  return /^(accounts|login|auth|appleid|sso|id)\./.test(h)
    || AUTH_PATH_RE.test(u.pathname)
    || /oauth|signin|login/i.test(u.search);
}

// True when a URL should load inside the app rather than the default browser.
function isInAppUrl(url) {
  if (!isHttpUrl(url)) return false;
  return isSameSiteHost(hostOf(config.app_url), hostOf(url)) // incl. www <-> apex + subdomains
    || isAuthUrl(url)
    || !!config.external_links_in_app;
}

function openExternalSafe(url) {
  if (isHttpUrl(url)) shell.openExternal(url).catch(() => {});
}

// ---------------------------------------------------------------------------
// crash reports: written to disk first (so the record survives the crash),
// uploaded next time the app is running and online - same idea as the
// Android CrashReporter. Does nothing unless crash_report_url is configured.
// ---------------------------------------------------------------------------

function crashFile() {
  return path.join(app.getPath('userData'), 'last_crash.json');
}

function recordCrash(payload) {
  if (!config.crash_report_url) return;
  try {
    fs.writeFileSync(crashFile(), JSON.stringify(Object.assign({
      package: config.package_name,
      version_code: config.version_code,
      platform: process.platform,
      at: Date.now(),
    }, payload)), 'utf8');
  } catch (e) { /* nothing more we can do */ }
}

async function uploadPendingCrash() {
  if (!config.crash_report_url || !isHttpUrl(config.crash_report_url)) return;
  let body;
  try { body = fs.readFileSync(crashFile()); } catch (e) { return; }
  try {
    const res = await netutil.request({
      url: config.crash_report_url,
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      timeoutMs: 10000,
      readBody: false,
    });
    if (res.status >= 200 && res.status < 400) fs.unlinkSync(crashFile());
  } catch (e) { /* try again next launch */ }
}

process.on('uncaughtException', (err) => {
  recordCrash({ type: 'main_process_uncaught_exception', message: err && err.message, stack: err && err.stack });
});
process.on('unhandledRejection', (err) => {
  recordCrash({ type: 'main_process_unhandled_rejection', message: err && err.message, stack: err && err.stack });
});

// ---------------------------------------------------------------------------
// launch URLs (Windows jump-list tasks / second launches)
// ---------------------------------------------------------------------------

function openUrlArg(argv) {
  const a = (argv || []).find((x) => typeof x === 'string' && x.startsWith('--open-url='));
  if (!a) return null;
  const url = a.slice('--open-url='.length);
  return isAllowedTarget(url) ? url : null;
}

// Menu items / nav buttons: the app's own site always loads in the app; any
// other website follows the build option (inside the app, or the default browser).
function navigateGuest(url) {
  if (!isHttpUrl(url)) return;
  if (isInAppUrl(url)) {
    if (isLive(guest)) guest.loadURL(url).catch(() => {});
  } else {
    openExternalSafe(url);
  }
}

// ---------------------------------------------------------------------------
// offline fallback: what to show when a page can't be loaded
// ---------------------------------------------------------------------------

async function showOfflineFallback(contents, failedUrl) {
  if (!isLive(contents)) return;
  const entry = cache ? cache.read(failedUrl) : null;
  const dataUrl = entry ? cache.toDataUrl(entry, failedUrl) : null;
  if (dataUrl) {
    state.view = 'cached';
    state.showingCachedFor = failedUrl;
    state.lastCacheMissUrl = null;
    try {
      // The address is passed as the base so relative links, cookies and the
      // page's origin all behave as if it had loaded normally - the same
      // trick as loadDataWithBaseURL on Android.
      await contents.loadURL(dataUrl, { baseURLForDataURL: failedUrl });
    } catch (e) { /* ERR_ABORTED when superseded by another navigation */ }
    return;
  }
  state.view = 'offline-page';
  state.showingCachedFor = null;
  state.lastCacheMissUrl = failedUrl;
  try {
    await contents.loadFile(OFFLINE_PAGE, {
      query: { primary: config.primary_color, accent: config.accent_color, name: config.app_name },
    });
  } catch (e) { /* ignore */ }
}

async function attemptRealRefresh() {
  if (connectivity) await connectivity.verify();
  if (isLive(guest)) {
    if (state.view === 'offline-page' && state.lastCacheMissUrl) {
      guest.loadURL(state.lastCacheMissUrl).catch(() => {});
    } else if (state.view === 'cached' && state.showingCachedFor) {
      guest.loadURL(state.showingCachedFor).catch(() => {});
    } else {
      guest.reload();
    }
  }
  if (isOnline() && queue && queue.size() > 0) flushQueue();
}

// ---------------------------------------------------------------------------
// offline queue flushing (foreground retry with backoff + a slow safety-net tick)
// ---------------------------------------------------------------------------

let flushRetryAttempt = 0;
let retryTimer = null;

function cancelQueueRetry() {
  clearTimeout(retryTimer);
  retryTimer = null;
  flushRetryAttempt = 0;
}

function scheduleQueueRetry() {
  clearTimeout(retryTimer);
  const delay = FLUSH_RETRY_DELAYS_MS[Math.min(flushRetryAttempt, FLUSH_RETRY_DELAYS_MS.length - 1)];
  flushRetryAttempt++;
  retryTimer = setTimeout(() => {
    retryTimer = null;
    if (isOnline()) flushQueue();
  }, delay);
}

async function flushQueue() {
  if (!queue) return;
  if (queue.size() === 0) {
    refreshQueueSize();
    state.lastSyncError = null;
    broadcastState();
    return;
  }
  if (!isOnline()) return;

  const r = await queue.flush();
  state.queueSize = r.remaining;
  state.lastSyncError = r.remaining > 0 ? r.lastError : null;
  broadcastState();

  if (r.succeeded > 0) {
    toast(r.succeeded === 1 ? '1 saved item sent' : r.succeeded + ' saved items sent');
  } else if (r.dropped > 0) {
    toast(r.dropped === 1 ? "1 item couldn't be sent and was discarded" : r.dropped + " items couldn't be sent and were discarded");
  } else if (r.remaining > 0 && r.lastError) {
    toast('Send failed: ' + r.lastError);
  }

  if (r.remaining > 0) {
    if (isOnline()) scheduleQueueRetry();
  } else {
    cancelQueueRetry();
  }
}

async function forceSendQueueNow() {
  await connectivity.verify();
  if (!isOnline()) {
    toast("Still no connection - will send automatically once you're back online");
    return;
  }
  toast('Sending now\u2026');
  await flushQueue();
}

// ---------------------------------------------------------------------------
// background page caching + nav-tab prefetch
// ---------------------------------------------------------------------------

const recentlyCached = new Map(); // url -> timestamp, to avoid re-fetching in a burst

function cachePageInBackground(url) {
  if (!cache || !isOnline() || !cache.isCacheable(url)) return;
  // The page just loaded from the network: replace its saved copy with the
  // fresh one right away (only a 2 s guard against duplicate load events).
  const last = recentlyCached.get(url) || 0;
  if (Date.now() - last < 2000) return;
  recentlyCached.set(url, Date.now());
  if (recentlyCached.size > 200) recentlyCached.clear();
  cache.replaceFromNetwork(url).catch(() => {});
}

async function prefetchNavTabs() {
  if (!cache || !isOnline() || dataSaverActive()) return; // never spend data on guesses while saving
  const slow = !!(netQuality && netQuality.slow);
  const home = new URL(config.app_url).href;
  const ok = (u) => u !== home && cache.isCacheable(u) && sameHost(u, config.app_url);
  // Smart order: the pages this person opens most come first, then the nav tabs.
  const favourites = usage ? usage.top(slow ? 2 : 5, ok) : [];
  const urls = [];
  for (const u of favourites.concat(config.nav_items.map((i) => i.url))) {
    if (ok(u) && !urls.includes(u)) urls.push(u);
  }
  const batch = slow ? 1 : 2; // two at a time (like the APK's pool), one on a slow link
  const list = slow ? urls.slice(0, 4) : urls;
  for (let i = 0; i < list.length; i += batch) {
    if (!isOnline() || dataSaverActive()) return;
    await Promise.all(list.slice(i, i + batch).map((u) => cache.fetchAndCache(u).catch(() => {})));
  }
}

// ---------------------------------------------------------------------------
// update check (<site>/version.json - same file the Android build reads)
// ---------------------------------------------------------------------------

// Asks the build site (update_check_url, baked in at build time) whether a
// newer build of THIS app exists. The app identifies itself by its major
// domain + its name (plus the id of the build it came from, which the server
// uses to make sure only the same account's newer builds are ever offered).
async function checkBuildSiteForUpdate() {
  const u = new URL(config.update_check_url);
  u.searchParams.set('domain', siteDomain(config.app_url));
  u.searchParams.set('name', config.app_name);
  u.searchParams.set('platform', process.platform === 'darwin' ? 'macos' : 'windows');
  u.searchParams.set('build', config.build_id);
  const info = await netutil.fetchJson(u.toString(), 5000);
  if (!info || info.update !== true) return;
  const url = String(info.download_url || '');
  // only ever offer a download hosted on the build site itself
  if (!isHttpUrl(url) || hostOf(url) !== hostOf(config.update_check_url)) return;
  state.updateUrl = url;
  broadcastState();
  startAutoUpdate({ url, sha256: info.sha256, size: info.size });
}

// Hosts the app may download an installer from on its own: the build site and
// the app's own website (never anything else).
function isTrustedUpdateHost(url) {
  if (!isHttpUrl(url)) return false;
  const h = hostOf(url);
  return (config.update_check_url && h === hostOf(config.update_check_url))
    || isSameSiteHost(hostOf(config.app_url), h);
}

// Downloads the newer installer inside the app (Windows + macOS only). If
// anything goes wrong the banner still falls back to the browser download.
function startAutoUpdate(info) {
  if (!updater || (process.platform !== 'win32' && process.platform !== 'darwin')) return;
  if (!isTrustedUpdateHost(info.url)) return;
  // The same installer was already run once and this version is still being
  // offered, so the silent install did not work - leave it to the manual link.
  if (store.get('update_tried', '') === info.url) return;
  updater.start(info).catch(() => {});
}

async function checkForUpdate() {
  if (!isOnline()) return;
  const last = store.get('last_update_check', 0);
  if (Date.now() - last < UPDATE_CHECK_MIN_INTERVAL_MS) return;
  store.set('last_update_check', Date.now());
  try {
    if (config.update_check_url && config.build_id) {
      await checkBuildSiteForUpdate();
      return;
    }
    const home = new URL(config.app_url);
    const info = await netutil.fetchJson(home.protocol + '//' + home.host + '/version.json', 5000);
    if (!info) return;
    const latest = Number(info.version_code);
    const url = process.platform === 'darwin'
      ? (info.dmg_url || info.mac_url || info.desktop_url)
      : (info.exe_url || info.windows_url || info.desktop_url);
    if (!(latest > 0) || !url || !isHttpUrl(String(url))) return;
    if (latest > config.version_code) {
      state.updateUrl = String(url);
      broadcastState();
      startAutoUpdate({ url: String(url) });
    }
  } catch (e) { /* silent, like the APK */ }
}

// ---------------------------------------------------------------------------
// window, menu, kiosk, lock
// ---------------------------------------------------------------------------

// ----- tray + background mode ---------------------------------------------
// Closing the window hides it to the system tray instead of quitting, so the
// website's reminders, notifications and offline-queue sync keep running.
// Only Tray > Quit (or Cmd+Q / File > Quit) really exits.
function runInBackgroundWanted() {
  return store.get('run_in_background', true) !== false;
}

function backgroundActive() {
  return !!tray && !isQuitting && runInBackgroundWanted();
}

function showMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    if (app.isReady()) createWindow(config.app_url);
    return;
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  if (!mainWindow.isVisible()) mainWindow.show();
  mainWindow.focus();
}

function quitForReal() {
  isQuitting = true;
  app.quit();
}

function updateTrayTooltip() {
  if (!tray) return;
  try {
    tray.setToolTip(badgeCount > 0 ? config.app_name + ' - ' + badgeLabel(badgeCount) + ' new' : config.app_name);
  } catch (e) { /* cosmetic */ }
}

function createTray() {
  if (tray || !Tray || !nativeImage) return;
  try {
    let img = nativeImage.createFromPath(path.join(__dirname, 'icon.png'));
    if (img && !img.isEmpty()) img = img.resize({ width: process.platform === 'darwin' ? 18 : 16, height: process.platform === 'darwin' ? 18 : 16 });
    tray = new Tray(img);
    updateTrayTooltip();
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Open ' + config.app_name, click: showMainWindow },
      { type: 'separator' },
      { label: 'Quit', click: quitForReal },
    ]));
    // Windows / Linux: a single click on the icon opens the app (macOS uses the menu).
    tray.on('click', showMainWindow);
  } catch (e) {
    tray = null; // no tray on this system (e.g. a Linux desktop without one) - closing then quits as before
  }
}

// ----- taskbar / dock badge: DesktopApp.setBadge(n) ------------------------
function applyBadge() {
  const n = badgeCount;
  try {
    if (process.platform === 'darwin') {
      if (app.dock && app.dock.setBadge) app.dock.setBadge(badgeLabel(n));
    } else if (process.platform === 'win32') {
      if (mainWindow && !mainWindow.isDestroyed()) {
        const png = badgePng(n);
        mainWindow.setOverlayIcon(png ? nativeImage.createFromBuffer(png) : null, png ? badgeLabel(n) + ' new' : '');
      }
    } else if (typeof app.setBadgeCount === 'function') {
      app.setBadgeCount(n);
    }
  } catch (e) { /* cosmetic - never break the page over a badge */ }
  updateTrayTooltip();
}

function setBadge(n) {
  const next = normalizeCount(n);
  if (next === badgeCount) return;
  badgeCount = next;
  applyBadge();
}

// ----- start with the computer (Settings toggle; on by default) -----
function launchAtLoginWanted() {
  return store.get('launch_at_login', true) !== false;
}

function applyLaunchAtLogin() {
  try {
    // only register installed builds - never a dev/unpacked run
    if (!app.isPackaged || typeof app.setLoginItemSettings !== 'function') return;
    // --hidden: when started by the system at sign-in, go straight to the tray
    app.setLoginItemSettings({ openAtLogin: launchAtLoginWanted(), args: ['--hidden'] });
  } catch (e) { /* ignore - not supported on this system */ }
}

function applyKiosk() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const on = isKiosk();
  mainWindow.setKiosk(on);
  if (!on && mainWindow.isFullScreen() && process.platform !== 'darwin') mainWindow.setFullScreen(false);
}

function lockNow() {
  if (config.applock_enabled && applock && applock.isEnabled()) {
    state.locked = true;
    broadcastState();
  }
}

function canTouchId() {
  try {
    return process.platform === 'darwin' && systemPreferences.canPromptTouchID();
  } catch (e) {
    return false;
  }
}

function openSettings() {
  sendShell('shell:open-settings');
}

function buildMenu() {
  const isMac = process.platform === 'darwin';
  const zoom = (delta) => {
    if (!isLive(guest)) return;
    if (delta === 0) guest.setZoomLevel(0);
    else guest.setZoomLevel(Math.max(-3, Math.min(5, guest.getZoomLevel() + delta)));
  };
  const template = [
    ...(isMac ? [{ role: 'appMenu' }] : []),
    {
      label: 'File',
      submenu: [
        { label: 'Settings\u2026', accelerator: 'CmdOrCtrl+,', click: openSettings },
        { type: 'separator' },
        isMac ? { role: 'close' } : { role: 'quit' },
      ],
    },
    { role: 'editMenu' },
    {
      label: 'View',
      submenu: [
        { label: 'Reload', accelerator: 'CmdOrCtrl+R', click: () => attemptRealRefresh() },
        { label: 'Back', accelerator: isMac ? 'Cmd+[' : 'Alt+Left', click: () => sendShell('shell:nav', 'back') },
        { label: 'Forward', accelerator: isMac ? 'Cmd+]' : 'Alt+Right', click: () => sendShell('shell:nav', 'forward') },
        { type: 'separator' },
        { label: 'Zoom In', accelerator: 'CmdOrCtrl+Plus', click: () => zoom(0.5) },
        { label: 'Zoom Out', accelerator: 'CmdOrCtrl+-', click: () => zoom(-0.5) },
        { label: 'Actual Size', accelerator: 'CmdOrCtrl+0', click: () => zoom(0) },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    { role: 'windowMenu' },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function setupShortcuts() {
  // The desktop counterpart of the Android build's home-screen shortcuts:
  // Windows jump-list tasks / macOS dock menu, one per bottom-nav tab.
  if (!config.nav_items.length) return;
  if (process.platform === 'win32') {
    try {
      app.setUserTasks(config.nav_items.map((i) => ({
        program: process.execPath,
        arguments: '--open-url=' + i.url,
        iconPath: process.execPath,
        iconIndex: 0,
        title: i.label,
        description: i.label,
      })));
    } catch (e) { /* cosmetic */ }
  } else if (process.platform === 'darwin' && app.dock) {
    try {
      app.dock.setMenu(Menu.buildFromTemplate(config.nav_items.map((i) => ({
        label: i.label,
        click: () => navigateGuest(i.url),
      }))));
    } catch (e) { /* cosmetic */ }
  }
}

function createWindow(startUrl) {
  let saved = restoreBounds(null);
  try {
    saved = restoreBounds(store.get('window_bounds', null), screen.getAllDisplays().map((d) => d.workArea));
  } catch (e) { /* first run / no screen info - use the defaults */ }
  const hiddenStart = startedHidden && !!tray && runInBackgroundWanted() && !isKiosk();
  mainWindow = new BrowserWindow({
    width: saved.width,
    height: saved.height,
    ...(saved.x !== undefined ? { x: saved.x, y: saved.y } : {}),
    show: !hiddenStart,
    minWidth: 480,
    minHeight: 360,
    backgroundColor: config.primary_color || '#0f1115',
    title: config.app_name,
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    // Branded title bar drawn by shell.html; Windows/Linux keep their native
    // minimise / maximise / close buttons (tinted), macOS keeps its traffic lights.
    ...(process.platform === 'darwin'
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 14, y: 12 } }
      : { titleBarStyle: 'hidden', titleBarOverlay: { color: '#12151b', symbolColor: '#e8eaed', height: 38 } }),
    webPreferences: {
      preload: SHELL_PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false, // the <webview> tag needs this off
      backgroundThrottling: false, // keep timers (reminders, sync) running while hidden in the tray
      webviewTag: true,
      spellcheck: false,
    },
  });
  mainWindow.setTitle(config.app_name);

  // Everything the <webview> (the actual website) gets is decided here, not
  // by the shell's markup.
  mainWindow.webContents.on('will-attach-webview', (event, webPreferences, params) => {
    if (!isHttpUrl(params.src || '')) {
      event.preventDefault();
      return;
    }
    webPreferences.preload = GUEST_PRELOAD;
    webPreferences.nodeIntegration = false;
    webPreferences.contextIsolation = true;
    webPreferences.webSecurity = true;
    webPreferences.allowRunningInsecureContent = false;
    webPreferences.backgroundThrottling = false;
    params.partition = PARTITION;
  });

  mainWindow.webContents.on('render-process-gone', (e, details) => {
    recordCrash({ type: 'render_process_gone', reason: details.reason });
  });

  mainWindow.loadFile(path.join(__dirname, 'shell.html'), { query: { start: startUrl } });

  // remember size / position / maximised between runs
  if (saved.maximized && !isKiosk()) { try { mainWindow.maximize(); } catch (e) { /* ignore */ } }
  trackWindow(mainWindow, store, 'window_bounds');

  // Close button = hide to the tray (background mode) instead of quitting.
  mainWindow.on('close', (e) => {
    if (!backgroundActive() || isKiosk()) return;
    e.preventDefault();
    mainWindow.hide();
    lockNow();
  });

  mainWindow.on('session-end', () => { isQuitting = true; }); // Windows sign-out / shutdown must not be blocked by hide-to-tray

  mainWindow.on('closed', () => {
    mainWindow = null;
    guest = null;
  });

  // Windows "back" mouse button / media key, macOS trackpad swipe
  mainWindow.on('app-command', (e, cmd) => {
    if (cmd === 'browser-backward') sendShell('shell:nav', 'back');
    if (cmd === 'browser-forward') sendShell('shell:nav', 'forward');
  });
  mainWindow.on('swipe', (e, dir) => {
    if (dir === 'left') sendShell('shell:nav', 'back');
    if (dir === 'right') sendShell('shell:nav', 'forward');
  });

  // App lock: re-lock when minimised, or after being in the background a while.
  let blurTimer = null;
  mainWindow.on('minimize', lockNow);
  mainWindow.on('blur', () => {
    clearTimeout(blurTimer);
    blurTimer = setTimeout(lockNow, RELOCK_AFTER_BLUR_MS);
  });
  mainWindow.on('focus', () => clearTimeout(blurTimer));

  applyKiosk();
  applyLaunchAtLogin();
  applyBadge(); // the Windows overlay belongs to the window, so re-apply on a new one
}

// ---------------------------------------------------------------------------
// events on the <webview>'s web contents (the actual site)
// ---------------------------------------------------------------------------

function trackNavigationTarget(url) {
  // logout: forget the saved login and don't auto-fill it on the next login page
  try {
    const p = new URL(url).pathname.toLowerCase();
    if (p.includes('logout') && sameHost(url, config.app_url)) {
      if (vault) vault.clear();
      state.pendingCredentials = null;
      state.skipAutoLoginOnce = true;
    }
  } catch (e) { /* ignore */ }
}

function onMainFrameCommitted(url) {
  // Save the captured login once the person has moved on from the login page
  // (i.e. it actually worked), mirroring the APK's onPageFinished logic.
  const cameFromLogin = state.lastPageUrl && isSensitiveUrl(state.lastPageUrl);
  const nowOnNonLogin = !isSensitiveUrl(url);
  if (cameFromLogin && nowOnNonLogin && state.pendingCredentials && vault && config.remember_login_enabled) {
    vault.save(state.pendingCredentials.u, state.pendingCredentials.p);
    state.pendingCredentials = null;
  }
  state.lastPageUrl = url;
  try {
    if (usage && isHttpUrl(url) && sameHost(url, config.app_url) && !isSensitiveUrl(url)) usage.record(url);
  } catch (e) { /* ignore */ }
}

function attachGuestHandlers(contents) {
  guest = contents;
  guests.add(contents);
  contents.on('destroyed', () => { guests.delete(contents); if (guest === contents) guest = null; });

  // ---- copy / paste / cut / undo / select-all inside the website ----------
  // Electron does not give a <webview> a right-click menu, and the Edit-menu
  // roles act on the shell window rather than on the site inside the
  // <webview>, so copy and paste silently did nothing. Handle both here,
  // directly on the site's own web contents.
  contents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown' || input.isAutoRepeat) return;
    const mod = process.platform === 'darwin' ? input.meta : input.control;
    if (!mod || input.alt) return;
    const key = String(input.key || '').toLowerCase();
    let action = null;
    if (key === 'c') action = 'copy';
    else if (key === 'x') action = 'cut';
    else if (key === 'v') action = input.shift ? 'pasteAndMatchStyle' : 'paste';
    else if (key === 'a') action = 'selectAll';
    else if (key === 'z') action = input.shift ? 'redo' : 'undo';
    else if (key === 'y' && process.platform !== 'darwin') action = 'redo';
    if (!action || !isLive(contents)) return;
    event.preventDefault();
    try { contents[action](); } catch (e) { /* ignore */ }
  });

  contents.on('context-menu', (event, params) => {
    if (!isLive(contents)) return;
    const f = params.editFlags || {};
    const items = [];
    if (params.isEditable) {
      items.push(
        { label: 'Undo', enabled: !!f.canUndo, click: () => contents.undo() },
        { label: 'Redo', enabled: !!f.canRedo, click: () => contents.redo() },
        { type: 'separator' },
        { label: 'Cut', enabled: !!f.canCut, click: () => contents.cut() },
        { label: 'Copy', enabled: !!f.canCopy, click: () => contents.copy() },
        { label: 'Paste', enabled: !!f.canPaste, click: () => contents.paste() },
        { label: 'Select All', enabled: !!f.canSelectAll, click: () => contents.selectAll() },
      );
    } else {
      if (params.linkURL) {
        items.push({ label: 'Copy Link Address', click: () => clipboard.writeText(params.linkURL) });
      }
      if (params.selectionText) {
        items.push({ label: 'Copy', click: () => contents.copy() });
      }
      if (params.mediaType === 'image' && params.srcURL) {
        items.push({ label: 'Copy Image', click: () => contents.copyImageAt(params.x, params.y) });
      }
      if (params.selectionText || items.length === 0) {
        items.push({ label: 'Select All', click: () => contents.selectAll() });
      }
    }
    if (items.length) Menu.buildFromTemplate(items).popup({ window: mainWindow || undefined });
  });

  contents.setWindowOpenHandler(({ url, disposition }) => {
    if (!isHttpUrl(url)) { openExternalSafe(url); return { action: 'deny' }; }
    // Real popup windows (window.open with features) - e.g. "Sign in with Google" -
    // need to stay a child window so the site can read the result back. They
    // share the app's session, so the login lands in the app.
    if (isAuthUrl(url) && disposition === 'new-window') return { action: 'allow' };
    // Ordinary target="_blank" links (including login / register buttons) just
    // load in the same view, with the same saved session.
    if (isInAppUrl(url)) {
      if (isLive(guest)) guest.loadURL(url).catch(() => {});
      return { action: 'deny' };
    }
    openExternalSafe(url);
    return { action: 'deny' };
  });

  contents.on('will-navigate', (e, url) => {
    trackNavigationTarget(url);
    if (!isHttpUrl(url)) {
      // file: (our own offline page) and data: (cached pages) are internal;
      // anything else that isn't http(s) is handed to the OS.
      if (!/^(file:|data:|about:)/i.test(url)) { e.preventDefault(); shell.openExternal(url).catch(() => {}); }
      return;
    }
    if (!isInAppUrl(url)) {
      e.preventDefault();
      openExternalSafe(url);
    }
  });
  contents.on('will-redirect', (e, url) => trackNavigationTarget(url));

  contents.on('did-start-navigation', (e, url, isInPlace, isMainFrame) => {
    // Electron passes these positionally (and also on `details`); accept both.
    const target = typeof url === 'string' ? url : (e && e.url);
    const main = typeof isMainFrame === 'boolean' ? isMainFrame : (e && e.isMainFrame);
    if (!main || !target) return;
    trackNavigationTarget(target);
    if (isHttpUrl(target)) {
      state.view = 'live';
      state.showingCachedFor = null;
      if (sameHost(target, config.app_url)) state.lastRequestedUrl = target;
    }
  });

  contents.on('did-navigate', async (e, url) => {
    if (url.startsWith('data:') || url.startsWith('file:')) return;
    onMainFrameCommitted(url);
    // A redirect to a different site right after asking for our own is what a
    // hotel/airport/carrier portal does. Check whether the real server is
    // actually reachable; if not, treat it as offline and show the cache.
    if (!isSameSiteHost(hostOf(config.app_url), hostOf(url)) && state.lastRequestedUrl && connectivity) {
      const online = await connectivity.verify();
      if (!online) showOfflineFallback(contents, state.lastRequestedUrl);
    }
  });
  contents.on('did-navigate-in-page', (e, url, isMainFrame) => {
    if (isMainFrame && !url.startsWith('data:')) onMainFrameCommitted(url);
  });

  contents.on('did-fail-load', async (e, code, desc, url, isMainFrame) => {
    // -3 is ERR_ABORTED, which fires on ordinary redirects and superseded
    // navigations - treating it as a failure would flash the offline page on
    // every normal click.
    if (!isMainFrame || code === -3 || !isHttpUrl(url) || !isNetworkErrorCode(code)) return;
    if (isOnline() && connectivity) await connectivity.reportLoadFailure();
    if (!isOnline()) showOfflineFallback(contents, url);
    // else: the network is fine - it's a real, different problem (leave
    // Chromium's own error page in place).
  });

  // ---- faster start: if the live site is slow to answer on launch, show the
  // saved copy of the page straight away, then switch to the live one.
  let loadStartedAt = 0;
  contents.on('did-start-loading', () => {
    loadStartedAt = Date.now();
    if (state.startupDone || state.startupTimer) return;
    const wait = store && store.get('last_net_slow', false) ? 1200 : 3000;
    state.startupTimer = setTimeout(() => {
      state.startupTimer = null;
      if (state.startupDone || !isLive(contents)) return;
      state.startupDone = true;
      const url = config.app_url;
      const entry = cache ? cache.read(url) : null;
      if (!entry) return;
      state.startupSnapshot = true;
      showOfflineFallback(contents, url).then(() => {
        toast('Showing saved page - loading the latest...');
        if (!isOnline()) return;
        cache.replaceFromNetwork(url)
          .catch(() => {})
          .then(() => {
            if (state.startupSnapshot && isLive(contents) && state.showingCachedFor === url) {
              state.startupSnapshot = false;
              contents.loadURL(url).catch(() => {});
            }
          });
      });
    }, wait);
    if (state.startupTimer.unref) state.startupTimer.unref();
  });
  contents.on('dom-ready', () => {
    const u = contents.getURL();
    if (isHttpUrl(u)) {
      state.startupDone = true;
      clearTimeout(state.startupTimer);
      state.startupTimer = null;
      state.startupSnapshot = false;
    }
  });
  contents.on('did-stop-loading', () => {
    const u = contents.getURL();
    if (loadStartedAt && netQuality && isHttpUrl(u) && sameHost(u, config.app_url) && state.view === 'live') {
      netQuality.record('load', Date.now() - loadStartedAt);
    }
    loadStartedAt = 0;
  });

  contents.on('did-frame-finish-load', (e, isMainFrame) => {
    if (!isMainFrame) return;
    const url = contents.getURL();
    if (!url || url.startsWith('data:') || url.startsWith('file:')) return;
    if (!state.prefetchDone && isOnline() && !dataSaverActive() && sameHost(url, config.app_url)) {
      state.prefetchDone = true;
      prefetchNavTabs();
    }
    if (isOnline()) cachePageInBackground(url);
  });

  contents.on('render-process-gone', (e, details) => {
    recordCrash({ type: 'webview_render_process_gone', reason: details.reason });
  });
}

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------

// ----- download manager (list shown in the shell's Downloads panel) -----
const MAX_TRACKED_DOWNLOADS = 30;
const RISKY_OPEN_EXT = /\.(exe|msi|bat|cmd|com|scr|ps1|vbs|vbe|js|jse|jar|app|dmg|pkg|sh|lnk|reg|hta|command|apk)$/i;
const downloads = [];
const downloadItems = new Map();
let downloadSeq = 0;
let downloadTimer = null;

function publicDownloads() {
  return downloads.map((d) => ({
    id: d.id, name: d.name, state: d.state, received: d.received, total: d.total,
    startedAt: d.startedAt, canResume: !!d.canResume && !!downloadItems.get(d.id), canOpen: d.state === 'completed' && !RISKY_OPEN_EXT.test(d.savePath || ''),
  }));
}

function broadcastDownloads(immediate) {
  if (immediate) {
    if (downloadTimer) { clearTimeout(downloadTimer); downloadTimer = null; }
    sendShell('shell:downloads', publicDownloads());
    return;
  }
  if (downloadTimer) return; // throttle progress updates
  downloadTimer = setTimeout(() => { downloadTimer = null; sendShell('shell:downloads', publicDownloads()); }, 250);
}

// A dropped connection must not lose a half-finished download: keep trying to
// pick it up from the same byte (Chromium's HTTP range resume), with growing
// pauses, and again straight away when the network comes back.
const MAX_DOWNLOAD_RETRIES = 12;

function tryResume(rec, item) {
  try {
    if (item && item.canResume()) {
      rec.state = 'progressing';
      item.resume();
      return true;
    }
  } catch (e) { /* ignore */ }
  return false;
}

function scheduleDownloadResume(rec, item) {
  if (rec.resumeTimer || rec.retries >= MAX_DOWNLOAD_RETRIES) return;
  let can = false;
  try { can = item.canResume(); } catch (e) { can = false; }
  if (!can) return;
  rec.retries += 1;
  const wait = Math.min(2000 * rec.retries, 30000);
  rec.resumeTimer = setTimeout(() => {
    rec.resumeTimer = null;
    if (rec.state !== 'interrupted') return;
    if (isOnline()) tryResume(rec, item); // offline: resumeInterruptedDownloads() runs when the network is back
    broadcastDownloads(true);
  }, wait);
  if (rec.resumeTimer.unref) rec.resumeTimer.unref();
}

function resumeInterruptedDownloads() {
  for (const rec of downloads) {
    const item = downloadItems.get(rec.id);
    if (rec.state === 'interrupted' && item) {
      clearTimeout(rec.resumeTimer);
      rec.resumeTimer = null;
      rec.retries = Math.min(rec.retries, MAX_DOWNLOAD_RETRIES - 1);
      tryResume(rec, item);
    }
  }
  broadcastDownloads(true);
}

function trackDownload(item) {
  const rec = {
    id: ++downloadSeq, name: item.getFilename(), savePath: '', state: 'progressing',
    received: 0, total: item.getTotalBytes ? item.getTotalBytes() : 0, startedAt: Date.now(),
    retries: 0, canResume: false, lastBytes: 0, resumeTimer: null,
  };
  downloads.unshift(rec);
  downloadItems.set(rec.id, item);
  while (downloads.length > MAX_TRACKED_DOWNLOADS) {
    const old = downloads.pop();
    downloadItems.delete(old.id);
  }
  if (typeof item.on === 'function') {
    item.on('updated', (e, st) => {
      rec.received = item.getReceivedBytes();
      rec.total = item.getTotalBytes();
      try { rec.savePath = item.getSavePath() || rec.savePath; } catch (err) { /* ignore */ }
      rec.state = st === 'interrupted' ? 'interrupted' : (item.isPaused() ? 'paused' : 'progressing');
      try { rec.canResume = item.canResume(); } catch (err) { rec.canResume = false; }
      if (rec.received > rec.lastBytes) { rec.lastBytes = rec.received; rec.retries = 0; } // progress made: reset the retry budget
      if (rec.state === 'interrupted') scheduleDownloadResume(rec, item);
      broadcastDownloads(false);
    });
  }
  item.once('done', (e, result) => {
    try { rec.savePath = item.getSavePath() || rec.savePath; } catch (err) { /* ignore */ }
    rec.state = result;
    if (result === 'completed' && rec.total) rec.received = rec.total;
    let keep = false;
    if (result === 'interrupted') { try { keep = item.canResume(); } catch (err) { keep = false; } }
    rec.canResume = keep;
    if (keep) scheduleDownloadResume(rec, item); else downloadItems.delete(rec.id);
    broadcastDownloads(true);
  });
  broadcastDownloads(true);
}

function isGuestSender(sender) {
  if (!sender) return false;
  for (const g of guests) { if (isLive(g) && g.id === sender.id) return true; }
  return false;
}

function settingsSnapshot() {
  let cacheStats = { files: 0, bytes: 0 };
  let assetStats = { files: 0, bytes: 0 };
  try { cacheStats = cache.stats(); } catch (e) { /* ignore */ }
  try { assetStats = assetCache.stats(); } catch (e) { /* ignore */ }
  let hasSavedLogin = false;
  try { hasSavedLogin = !!config.remember_login_enabled && vault.get() !== null; } catch (e) { /* ignore */ }
  return {
    appLock: !!config.applock_enabled && applock.isEnabled(),
    appLockAvailable: !!config.applock_enabled,
    appLockRequired: !!config.applock_enabled,
    kioskAvailable: !!config.kiosk_enabled,
    canTouchId: canTouchId(),
    kiosk: isKiosk(),
    notifications: notificationsAllowed(),
    launchAtLogin: launchAtLoginWanted(),
    runInBackground: runInBackgroundWanted(),
    dataSaverMode: dataSaverMode(),
    slow: !!(netQuality && netQuality.slow),
    trayAvailable: !!tray,
    pushBuild: !!config.push_enabled,
    rememberLoginEnabled: !!config.remember_login_enabled,
    hasSavedLogin,
    queueSize: state.queueSize,
    lastSyncError: state.lastSyncError,
    cacheBytes: cacheStats.bytes + assetStats.bytes,
    version: app.getVersion(),
    versionCode: config.version_code,
    platform: process.platform,
    updateUrl: state.updateUrl,
    updateStatus: state.updateStatus,
    updateProgress: state.updateProgress,
  };
}

function shareUrlOrText(payload) {
  // macOS: native share sheet. Elsewhere: copy to the clipboard (Windows has no
  // share sheet reachable from a desktop app) and say so.
  try {
    if (process.platform === 'darwin' && ShareMenu) {
      const menu = new ShareMenu(payload);
      menu.popup(mainWindow ? { window: mainWindow } : undefined);
      return;
    }
  } catch (e) { /* fall through to clipboard */ }
  const text = (payload.urls && payload.urls[0]) || (payload.texts && payload.texts[0]) || '';
  if (text) {
    clipboard.writeText(text);
    toast(payload.urls ? 'Link copied to clipboard' : 'Copied to clipboard');
  }
}

async function shareFile(base64Data, fileName) {
  const safeName = path.basename(String(fileName || 'file')).replace(/[\\/:*?"<>|]/g, '_') || 'file';
  const buf = Buffer.from(String(base64Data || ''), 'base64');
  if (!buf.length || buf.length > MAX_SHARE_FILE_BYTES) return false;
  const res = await dialog.showSaveDialog(mainWindow, { defaultPath: safeName });
  if (res.canceled || !res.filePath) return false;
  try {
    fs.writeFileSync(res.filePath, buf);
    toast('Saved ' + path.basename(res.filePath));
    shell.showItemInFolder(res.filePath);
    return true;
  } catch (e) {
    toast('Could not save the file');
    return false;
  }
}

function deliverToGuest(script) {
  if (isLive(guest)) guest.executeJavaScript(script, true).catch(() => {});
}

async function startQrScan() {
  if (!config.filecamera_enabled) {
    deliverToGuest('window.onQRScanError && window.onQRScanError("permission_denied");');
    return;
  }
  if (process.platform === 'darwin') {
    let ok = false;
    try { ok = await systemPreferences.askForMediaAccess('camera'); } catch (e) { ok = false; }
    if (!ok) {
      deliverToGuest('window.onQRScanError && window.onQRScanError("permission_denied");');
      return;
    }
  }
  sendShell('shell:qr-open');
}

function registerIpc() {
  // ----- from the shell (local trusted UI) --------------------------------
  const shellHandlers = {
    boot: () => ({
      config: {
        app_name: config.app_name,
        app_url: config.app_url,
        primary_color: config.primary_color,
        accent_color: config.accent_color,
        splash_enabled: config.splash_enabled,
        filecamera_enabled: config.filecamera_enabled,
        nav_items: config.nav_items,
      },
      partition: PARTITION,
      platform: process.platform,
      state: publicState(),
      settings: settingsSnapshot(),
    }),
    reload: () => attemptRealRefresh(),
    retry: () => attemptRealRefresh(),
    'sync-now': () => forceSendQueueNow(),
    'set-titlebar-color': (bg, fg) => {
      const ok = (c) => typeof c === 'string' && /^#[0-9a-f]{6}$/i.test(c);
      if (process.platform !== 'darwin' && mainWindow && !mainWindow.isDestroyed() && ok(bg) && ok(fg) && mainWindow.setTitleBarOverlay) {
        try { mainWindow.setTitleBarOverlay({ color: bg, symbolColor: fg, height: 38 }); } catch (_) {}
      }
      return true;
    },
    'set-active-guest': (id) => {
      for (const g of guests) {
        if (isLive(g) && g.id === Number(id)) { guest = g; return true; }
      }
      return false;
    },
    'go-forward': () => {
      if (isLive(guest) && guest.canGoForward()) { guest.goForward(); return true; }
      return false;
    },
    'get-downloads': () => publicDownloads(),
    'download-show': (id) => {
      const d = downloads.find((x) => x.id === id);
      if (!d || !d.savePath) return false;
      shell.showItemInFolder(d.savePath);
      return true;
    },
    'download-open': async (id) => {
      const d = downloads.find((x) => x.id === id);
      if (!d || d.state !== 'completed' || !d.savePath) return false;
      // never launch programs/scripts straight from a download - show them in the folder instead
      if (RISKY_OPEN_EXT.test(d.savePath) || !fs.existsSync(d.savePath)) { shell.showItemInFolder(d.savePath); return false; }
      return (await shell.openPath(d.savePath)) === '';
    },
    'download-resume': (id) => {
      const rec = downloads.find((x) => x.id === id);
      const it = downloadItems.get(id);
      if (!rec || !it) return false;
      rec.retries = 0;
      const ok = tryResume(rec, it);
      broadcastDownloads(true);
      return ok;
    },
    'download-cancel': (id) => {
      const it = downloadItems.get(id);
      if (it) { try { it.cancel(); } catch (e) { /* ignore */ } }
      return true;
    },
    'download-clear': () => {
      for (let i = downloads.length - 1; i >= 0; i--) {
        if (downloads[i].state !== 'progressing' && downloads[i].state !== 'paused') downloads.splice(i, 1);
      }
      broadcastDownloads(true);
      return true;
    },
    'offline-status': () => {
      let c = { files: 0, bytes: 0, newest: 0 };
      let a = { files: 0, bytes: 0, newest: 0 };
      try { c = cache.stats(); } catch (e) { /* ignore */ }
      try { a = assetCache.stats(); } catch (e) { /* ignore */ }
      return {
        online: isOnline(),
        pages: c.files, pageBytes: c.bytes,
        assets: a.files, assetBytes: a.bytes,
        pageLimitBytes: cache.maxBytes || 0,
        lastCachedAt: Math.max(c.newest || 0, a.newest || 0),
        queueSize: state.queueSize,
        lastSyncError: state.lastSyncError,
      };
    },
    'go-back': () => {
      // history navigation only - no reload of the current page
      if (isLive(guest) && guest.canGoBack()) { guest.goBack(); return true; }
      return false;
    },
    'share-current': () => {
      let url = isLive(guest) ? guest.getURL() : '';
      if (!isHttpUrl(url)) url = state.showingCachedFor || state.lastCacheMissUrl || config.app_url;
      shareUrlOrText({ urls: [url] });
    },
    'clear-cache': () => {
      const n = cache.clear() + assetCache.clear();
      toast('Offline cache cleared');
      return n;
    },
    'forget-login': () => {
      const had = vault.get() !== null;
      vault.clear();
      state.pendingCredentials = null;
      if (had) toast('Saved login forgotten');
      return true;
    },
    'get-settings': () => settingsSnapshot(),
    'set-kiosk': (on) => {
      if (!config.kiosk_enabled) return false;
      store.set('kiosk_override', !!on);
      applyKiosk();
      broadcastState();
      return isKiosk();
    },
    'set-launch-at-login': (on) => {
      store.set('launch_at_login', !!on);
      applyLaunchAtLogin();
      return launchAtLoginWanted();
    },
    'set-data-saver': (mode) => {
      store.set('data_saver', mode === 'on' || mode === 'off' ? mode : 'auto');
      broadcastState();
      broadcastNetToGuests();
      return dataSaverMode();
    },
    'set-run-in-background': (on) => {
      store.set('run_in_background', !!on);
      return runInBackgroundWanted();
    },
    'set-notifications': (on) => {
      store.set('notifications', !!on);
      return notificationsAllowed();
    },
    'lock-set-pin': (pin) => {
      if (!config.applock_enabled) return false;
      const ok = applock.setPin(typeof pin === 'string' ? pin : '');
      if (ok) state.locked = false; // covers the forced first-run setup case; a no-op if it was already unlocked
      broadcastState();
      return ok;
    },
    'lock-disable': (pin) => !config.applock_enabled ? false : applock.disable(typeof pin === 'string' ? pin : ''),
    'lock-verify': (pin) => {
      const r = applock.verify(typeof pin === 'string' ? pin : '');
      if (r.ok) {
        state.locked = false;
        broadcastState();
      }
      return r;
    },
    'lock-touchid': async () => {
      if (!config.applock_enabled) return false;
      if (!canTouchId()) return false;
      try {
        await systemPreferences.promptTouchID('unlock ' + config.app_name);
        state.locked = false;
        broadcastState();
        return true;
      } catch (e) {
        return false;
      }
    },
    'lock-now': () => lockNow(),
    'open-update': async () => {
      if (updater && updater.status === 'ready') {
        const ok = await updater.installNow();
        if (ok) return true;
      } else if (updater && updater.status === 'downloading') {
        toast('Downloading the update - ' + Math.round(state.updateProgress * 100) + '%');
        return true;
      }
      openExternalSafe(state.updateUrl); // fallback: normal browser download
      return false;
    },
    'open-link': (url) => navigateGuest(typeof url === 'string' ? url : ''),
    'qr-result': (res) => {
      if (!res || typeof res !== 'object') return;
      if (typeof res.text === 'string') {
        deliverToGuest('window.onQRScanResult && window.onQRScanResult(' + JSON.stringify(res.text) + ');');
      } else {
        const reason = typeof res.error === 'string' ? res.error : 'cancelled';
        deliverToGuest('window.onQRScanError && window.onQRScanError(' + JSON.stringify(reason) + ');');
      }
    },
  };

  ipcMain.handle('shell:invoke', async (event, name, arg) => {
    if (!mainWindow || event.sender !== mainWindow.webContents) return null;
    const fn = shellHandlers[name];
    return fn ? fn(arg) : null;
  });

  // ----- from the website's own pages (untrusted; validated) -------------------
  ipcMain.on('w2a:boot', (event, hrefArg) => {
    if (!isGuestSender(event.sender)) { event.returnValue = null; return; }
    let href = typeof hrefArg === 'string' ? hrefArg : '';
    // A page shown from the offline cache is a data: URL whose base is the
    // real address - main knows which one (it just loaded it).
    if (href.startsWith('data:') && state.view === 'cached' && state.showingCachedFor) href = state.showingCachedFor;
    const trusted = isHttpUrl(href) && sameHost(href, config.app_url);
    const offlinePage = /^file:/i.test(href) && /offline\.html/i.test(href);
    event.returnValue = {
      trusted,
      offlinePage,
      online: isOnline(),
      slow: !!(netQuality && netQuality.slow),
      saver: dataSaverActive(),
      platform: process.platform,
      script: trusted ? buildInjection({ maxFileBytes: MAX_QUEUEABLE_FILE_BYTES, baseUrl: href }) : null,
      credentials: trusted && config.remember_login_enabled && vault.available(),
    };
  });

  ipcMain.handle('w2a:enqueue', (event, json) => {
    if (!isGuestSender(event.sender) || typeof json !== 'string' || json.length > 40 * 1024 * 1024) return { ok: false };
    const r = queue.enqueue(json);
    if (r.ok) {
      refreshQueueSize();
      broadcastState();
    }
    return { ok: r.ok };
  });
  ipcMain.on('w2a:queued', (event) => {
    if (!isGuestSender(event.sender)) return;
    toast("Saved - will send once you're back online");
    // If we're actually online (the page thought otherwise for a moment), send right away.
    if (isOnline()) flushQueue();
  });
  ipcMain.on('w2a:queue-failed', (event) => {
    if (isGuestSender(event.sender)) toast('Could not save this - please try again');
  });
  ipcMain.on('w2a:retry', (event) => {
    if (isGuestSender(event.sender)) attemptRealRefresh();
  });
  ipcMain.on('w2a:print', (event) => {
    if (isGuestSender(event.sender) && isLive(guest)) guest.print({ printBackground: true }, () => {});
  });
  ipcMain.on('w2a:open-page', (event, url) => {
    if (!isGuestSender(event.sender)) return;
    url = String(url || '');
    if (!isHttpUrl(url)) return;
    // Login / sign-up / checkout pages (and www<->apex variants) can't be kept
    // as a background page, but the click was already cancelled by the page
    // preload - so load them in the same view instead of silently dropping the
    // click (that is what made the login buttons look dead).
    if (!sameHost(url, config.app_url) || isSensitiveUrl(url)) {
      if (isInAppUrl(url) && isLive(event.sender)) event.sender.loadURL(url).catch(() => {});
      else openExternalSafe(url);
      return;
    }
    sendShell('shell:open-page', url);
  });
  ipcMain.on('w2a:badge', (event, n) => {
    if (!isGuestSender(event.sender)) return;
    setBadge(n);
  });
  ipcMain.on('w2a:share-text', (event, text) => {
    if (!isGuestSender(event.sender)) return;
    shareUrlOrText({ texts: [String(text)] });
  });
  ipcMain.handle('w2a:share-file', (event, data, name) => {
    if (!isGuestSender(event.sender)) return false;
    return shareFile(data, name);
  });
  ipcMain.on('w2a:notify-schedule', (event, id, title, body, delaySeconds) => {
    if (isGuestSender(event.sender) && notificationsAllowed()) notifier.schedule(id, title, body, delaySeconds);
  });
  ipcMain.on('w2a:notify-cancel', (event, id) => {
    if (isGuestSender(event.sender)) notifier.cancel(id);
  });
  ipcMain.on('w2a:qr-scan', (event) => {
    if (isGuestSender(event.sender)) startQrScan();
  });
  ipcMain.on('w2a:cred-capture', (event, u, p) => {
    if (!isGuestSender(event.sender) || !config.remember_login_enabled) return;
    if (typeof u === 'string' && typeof p === 'string' && u && p) state.pendingCredentials = { u, p };
  });
  ipcMain.handle('w2a:cred-get', (event) => {
    if (!isGuestSender(event.sender) || !config.remember_login_enabled) return null;
    if (state.skipAutoLoginOnce) {
      state.skipAutoLoginOnce = false;
      return null;
    }
    return vault.get();
  });
}

// ---------------------------------------------------------------------------
// session: permissions, downloads, user agent
// ---------------------------------------------------------------------------

function setupSession() {
  ses = session.fromPartition(PARTITION);

  // Present as plain Chrome: the default UA contains "Electron/x.y.z" and the
  // app's name, which some sites treat as an unsupported browser.
  try {
    const ua = ses.getUserAgent()
      .replace(/\s*Electron\/[\d.]+/i, '')
      .replace(new RegExp('\\s*' + escapeRegExp(app.getName()) + '/[\\d.]+', 'i'), '');
    ses.setUserAgent(ua);
  } catch (e) { /* cosmetic */ }

  // Data saver: only does anything while it is switched on (or auto + slow).
  try {
    if (ses.webRequest) {
      const appHost = hostOf(config.app_url);
      ses.webRequest.onBeforeRequest((details, cb) => {
        cb(dataSaverActive() && shouldBlockRequest(details, appHost) ? { cancel: true } : {});
      });
      ses.webRequest.onHeadersReceived((details, cb) => {
        cb(dataSaverActive() && shouldBlockByHeaders(details) ? { cancel: true } : {});
      });
      ses.webRequest.onBeforeSendHeaders((details, cb) => {
        if (!dataSaverActive()) return cb({});
        cb({ requestHeaders: Object.assign({}, details.requestHeaders, { 'Save-Data': 'on' }) });
      });
    }
  } catch (e) { /* never block startup over this */ }

  const allowed = (permission) => {
    if (permission === 'media') return !!config.filecamera_enabled;
    if (permission === 'notifications') return !!config.push_enabled && notificationsAllowed();
    return permission === 'geolocation' || permission === 'fullscreen'
      || permission === 'clipboard-read' || permission === 'clipboard-sanitized-write';
  };

  ses.setPermissionRequestHandler(async (wc, permission, callback, details) => {
    let ok = allowed(permission);
    if (ok && permission === 'media' && process.platform === 'darwin') {
      // macOS asks the person once, at the OS level, per device type.
      try {
        const types = (details && details.mediaTypes) || ['video'];
        for (const t of types) {
          if (t === 'video') ok = ok && await systemPreferences.askForMediaAccess('camera');
          if (t === 'audio') ok = ok && await systemPreferences.askForMediaAccess('microphone');
        }
      } catch (e) { ok = false; }
    }
    callback(ok);
  });
  ses.setPermissionCheckHandler((wc, permission) => allowed(permission));

  // The shell (local UI) needs the camera only for the QR scanner overlay.
  session.defaultSession.setPermissionRequestHandler((wc, permission, callback) => {
    callback(permission === 'media' && !!config.filecamera_enabled && !!mainWindow && wc === mainWindow.webContents);
  });
  session.defaultSession.setPermissionCheckHandler((wc, permission) =>
    permission === 'media' && !!config.filecamera_enabled);

  ses.on('will-download', (event, item) => {
    try { trackDownload(item); } catch (e) { /* never block a download */ }
    item.once('done', (e, result) => {
      if (result !== 'completed') return;
      if (Notification.isSupported() && notificationsAllowed()) {
        const n = new Notification({ title: 'Download complete', body: item.getFilename() });
        n.on('click', () => shell.showItemInFolder(item.getSavePath()));
        n.show();
      }
      toast('Downloaded ' + item.getFilename());
    });
  });
}

// ---------------------------------------------------------------------------
// startup
// ---------------------------------------------------------------------------

function start() {
  if (process.platform === 'win32') app.setAppUserModelId(config.package_name);

  app.on('second-instance', (event, argv) => {
    showMainWindow(); // also brings the app back from the tray
    const url = openUrlArg(argv);
    if (url) navigateGuest(url);
  });

  app.on('web-contents-created', (event, contents) => {
    if (contents.getType() === 'webview') attachGuestHandlers(contents);
  });

  app.on('before-quit', () => {
    isQuitting = true; // let the window actually close this time
    if (updater) updater.installOnQuit();
  });
  app.on('window-all-closed', () => { if (!backgroundActive()) app.quit(); });
  app.on('activate', () => {
    if (app.isReady()) showMainWindow(); // dock icon click on macOS
  });

  app.whenReady().then(() => {
    const userData = app.getPath('userData');
    store = new Store(path.join(userData, 'settings.json'));
    updater = new Updater({
      platform: process.platform,
      dir: path.join(userData, 'updates'),
      download: (url, dest, onProgress) => netutil.downloadToFile(url, dest, {
        onProgress,
        onRedirect: (loc) => isTrustedUpdateHost(loc),
      }),
      spawn: (file, args) => {
        const child = require('child_process').spawn(file, args, { detached: true, stdio: 'ignore' });
        child.on('error', () => {});
        child.unref();
      },
      openPath: (file) => shell.openPath(file),
      quit: () => quitForReal(),
      onChange: () => {
        state.updateStatus = updater.status;
        state.updateProgress = updater.progress;
        broadcastState();
      },
      onInstallLaunched: (url) => { try { store.set('update_tried', url); } catch (e) { /* ignore */ } },
    });
    updater.cleanup(null); // installers from a previous run are no longer needed
    setupSession();

    assetCache = new AssetCache({
      dir: path.join(userData, 'assetcache'),
      homeUrl: config.app_url,
      fetcher: netutil.makeAssetFetcher(ses),
    });
    cache = new PageCache({
      dir: path.join(userData, 'webcache'),
      homeUrl: config.app_url,
      fetcher: netutil.makePageFetcher(ses),
      assetCache,
    });
    queue = new OfflineQueue({
      file: path.join(userData, 'offline_queue.jsonl'),
      sender: netutil.makeQueueSender(ses),
      // only ever replay to the app's own site
      allowUrl: (u) => isAllowedTarget(u),
    });
    usage = new UsageTracker(store);
    netQuality = new NetQuality({ initialSlow: store.get('last_net_slow', false) === true });
    netQuality.on('change', onNetQualityChange);
    vault = new CredentialVault(store, safeStorage);
    applock = new AppLock(store, undefined, config.applock_enabled);
    notifier = new Notifier(store, (title, body) => {
      if (!Notification.isSupported()) return;
      const n = new Notification({ title: title || config.app_name, body });
      n.on('click', showMainWindow);
      n.show();
    });

    connectivity = new Connectivity({
      isOsOnline: () => net.isOnline(),
      probe: () => probe(netutil.makeProbeRequester(), config.app_url),
    });
    connectivity.on('change', (online) => {
      broadcastOnlineToGuest();
      broadcastState();
      if (online) {
        toast('Back online');
        resumeInterruptedDownloads();
        if (state.startupSnapshot && isLive(guest) && state.showingCachedFor) {
          state.startupSnapshot = false;
          guest.loadURL(state.showingCachedFor).catch(() => {});
        }
        flushQueue();
        checkForUpdate();
        uploadPendingCrash();
        if (state.view === 'offline-page' && state.lastCacheMissUrl && isLive(guest)) {
          guest.loadURL(state.lastCacheMissUrl).catch(() => {});
        }
      } else {
        cancelQueueRetry();
      }
    });
    connectivity.start();

    state.locked = !!config.applock_enabled && (applock.isEnabled() || applock.needsSetup());
    refreshQueueSize();

    registerIpc();
    buildMenu();
    setupShortcuts();
    notifier.restore();

    createTray();
    createWindow(openUrlArg(process.argv) || config.app_url);

    powerMonitor.on('lock-screen', lockNow);
    // Wake from sleep: the network often needs a moment - re-check reachability.
    powerMonitor.on('resume', () => {
      connectivity.verify().then(() => { if (isOnline()) flushQueue(); });
    });

    // Safety-net tick (the desktop stand-in for the Android WorkManager job):
    // if anything is still queued and we're online, keep trying.
    setInterval(() => {
      if (isOnline() && queue.size() > 0 && !retryTimer) flushQueue();
    }, 60 * 1000).unref();
    setInterval(() => checkForUpdate(), UPDATE_CHECK_MIN_INTERVAL_MS).unref();
    // Light speed check every 45s (one HEAD request) so a slow link is noticed
    // even when nobody is loading pages; skipped while hidden or offline.
    const speedProbe = netutil.makeProbeRequester();
    setInterval(async () => {
      if (!isOnline() || isQuitting || !mainWindow || mainWindow.isDestroyed() || !mainWindow.isVisible()) return;
      const t0 = Date.now();
      try { await speedProbe(config.app_url, 'HEAD', 8000); netQuality.record('probe', Date.now() - t0); } catch (e) { /* offline handling lives in connectivity */ }
    }, 45 * 1000).unref();
    // Re-warm the favourite pages now and then (skipped by itself on Data saver).
    setInterval(() => { prefetchNavTabs(); }, 30 * 60 * 1000).unref();

    if (queue.size() > 0 && isOnline()) flushQueue();
    checkForUpdate();
    uploadPendingCrash();
  });
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  start();
}

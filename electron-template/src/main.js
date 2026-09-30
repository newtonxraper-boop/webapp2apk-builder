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
  clipboard, systemPreferences, ShareMenu, powerMonitor, net, safeStorage,
} = require('electron');
const path = require('path');
const fs = require('fs');

const { loadConfig, hostOf, sameHost, isSameSiteHost, isHttpUrl } = require('./lib/config');
const { Store } = require('./lib/store');
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
let guest = null; // the <webview>'s webContents

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

const state = {
  queueSize: 0,
  lastSyncError: null,
  updateUrl: null,
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
  return store.has('kiosk_override') ? !!store.get('kiosk_override') : !!config.kiosk_enabled;
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
    locked: state.locked,
    lockSetupRequired: !!(applock && applock.needsSetup()),
    kiosk: isKiosk(),
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

function navigateGuest(url) {
  if (isLive(guest) && isAllowedTarget(url)) guest.loadURL(url).catch(() => {});
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
  const last = recentlyCached.get(url) || 0;
  if (Date.now() - last < 20000) return;
  recentlyCached.set(url, Date.now());
  if (recentlyCached.size > 200) recentlyCached.clear();
  cache.fetchAndCache(url).catch(() => {});
}

async function prefetchNavTabs() {
  if (!cache || !isOnline()) return;
  const home = new URL(config.app_url).href;
  const urls = config.nav_items.map((i) => i.url).filter((u) => u !== home && cache.isCacheable(u));
  // two at a time, like the APK's 2-thread prefetch pool
  for (let i = 0; i < urls.length; i += 2) {
    if (!isOnline()) return;
    await Promise.all(urls.slice(i, i + 2).map((u) => cache.fetchAndCache(u).catch(() => {})));
  }
}

// ---------------------------------------------------------------------------
// update check (<site>/version.json - same file the Android build reads)
// ---------------------------------------------------------------------------

async function checkForUpdate() {
  if (!isOnline()) return;
  const last = store.get('last_update_check', 0);
  if (Date.now() - last < UPDATE_CHECK_MIN_INTERVAL_MS) return;
  store.set('last_update_check', Date.now());
  try {
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
    }
  } catch (e) { /* silent, like the APK */ }
}

// ---------------------------------------------------------------------------
// window, menu, kiosk, lock
// ---------------------------------------------------------------------------

function applyKiosk() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const on = isKiosk();
  mainWindow.setKiosk(on);
  if (!on && mainWindow.isFullScreen() && process.platform !== 'darwin') mainWindow.setFullScreen(false);
}

function lockNow() {
  if (applock && applock.isEnabled()) {
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
        { label: 'Back', accelerator: isMac ? 'Cmd+[' : 'Alt+Left', click: () => { if (isLive(guest) && guest.canGoBack()) guest.goBack(); } },
        { label: 'Forward', accelerator: isMac ? 'Cmd+]' : 'Alt+Right', click: () => { if (isLive(guest) && guest.canGoForward()) guest.goForward(); } },
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
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 480,
    minHeight: 360,
    backgroundColor: config.primary_color || '#0f1115',
    title: config.app_name,
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: SHELL_PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false, // the <webview> tag needs this off
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
    params.partition = PARTITION;
  });

  mainWindow.webContents.on('render-process-gone', (e, details) => {
    recordCrash({ type: 'render_process_gone', reason: details.reason });
  });

  mainWindow.loadFile(path.join(__dirname, 'shell.html'), { query: { start: startUrl } });

  mainWindow.on('closed', () => {
    mainWindow = null;
    guest = null;
  });

  // Windows "back" mouse button / media key, macOS trackpad swipe
  mainWindow.on('app-command', (e, cmd) => {
    if (cmd === 'browser-backward' && isLive(guest) && guest.canGoBack()) guest.goBack();
    if (cmd === 'browser-forward' && isLive(guest) && guest.canGoForward()) guest.goForward();
  });
  mainWindow.on('swipe', (e, dir) => {
    if (dir === 'left' && isLive(guest) && guest.canGoBack()) guest.goBack();
    if (dir === 'right' && isLive(guest) && guest.canGoForward()) guest.goForward();
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
}

function attachGuestHandlers(contents) {
  guest = contents;
  contents.on('destroyed', () => { if (guest === contents) guest = null; });

  contents.setWindowOpenHandler(({ url }) => {
    if (isHttpUrl(url) && sameHost(url, config.app_url)) return { action: 'allow' };
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
    if (!sameHost(url, config.app_url)) {
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

  contents.on('did-frame-finish-load', (e, isMainFrame) => {
    if (!isMainFrame) return;
    const url = contents.getURL();
    if (!url || url.startsWith('data:') || url.startsWith('file:')) return;
    if (!state.prefetchDone && isOnline() && sameHost(url, config.app_url)) {
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

function isGuestSender(sender) {
  return isLive(guest) && !!sender && sender.id === guest.id;
}

function settingsSnapshot() {
  let cacheStats = { files: 0, bytes: 0 };
  let assetStats = { files: 0, bytes: 0 };
  try { cacheStats = cache.stats(); } catch (e) { /* ignore */ }
  try { assetStats = assetCache.stats(); } catch (e) { /* ignore */ }
  let hasSavedLogin = false;
  try { hasSavedLogin = !!config.remember_login_enabled && vault.get() !== null; } catch (e) { /* ignore */ }
  return {
    appLock: applock.isEnabled(),
    appLockRequired: !!config.applock_enabled,
    canTouchId: canTouchId(),
    kiosk: isKiosk(),
    notifications: notificationsAllowed(),
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
      store.set('kiosk_override', !!on);
      applyKiosk();
      broadcastState();
      return isKiosk();
    },
    'set-notifications': (on) => {
      store.set('notifications', !!on);
      return notificationsAllowed();
    },
    'lock-set-pin': (pin) => {
      const ok = applock.setPin(typeof pin === 'string' ? pin : '');
      if (ok) state.locked = false; // covers the forced first-run setup case; a no-op if it was already unlocked
      broadcastState();
      return ok;
    },
    'lock-disable': (pin) => applock.disable(typeof pin === 'string' ? pin : ''),
    'lock-verify': (pin) => {
      const r = applock.verify(typeof pin === 'string' ? pin : '');
      if (r.ok) {
        state.locked = false;
        broadcastState();
      }
      return r;
    },
    'lock-touchid': async () => {
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
    'open-update': () => openExternalSafe(state.updateUrl),
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
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
    const url = openUrlArg(argv);
    if (url) navigateGuest(url);
  });

  app.on('web-contents-created', (event, contents) => {
    if (contents.getType() === 'webview') attachGuestHandlers(contents);
  });

  app.on('window-all-closed', () => app.quit());
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0 && app.isReady()) createWindow(config.app_url);
  });

  app.whenReady().then(() => {
    const userData = app.getPath('userData');
    store = new Store(path.join(userData, 'settings.json'));
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
    vault = new CredentialVault(store, safeStorage);
    applock = new AppLock(store, undefined, config.applock_enabled);
    notifier = new Notifier(store, (title, body) => {
      if (!Notification.isSupported()) return;
      const n = new Notification({ title: title || config.app_name, body });
      n.on('click', () => {
        if (mainWindow) {
          if (mainWindow.isMinimized()) mainWindow.restore();
          mainWindow.focus();
        }
      });
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

    state.locked = applock.isEnabled() || applock.needsSetup();
    refreshQueueSize();

    registerIpc();
    buildMenu();
    setupShortcuts();
    notifier.restore();

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

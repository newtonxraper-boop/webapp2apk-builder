'use strict';
/**
 * Loads the real src/main.js against a mocked `electron` module and drives it
 * through the important flows (offline fallback from the cache, the offline
 * page, IPC validation, queueing, flush on reconnect). It can't prove the
 * Electron APIs behave as assumed - only a real run can - but it catches
 * typos, undefined identifiers and wiring mistakes in the orchestration code.
 */
const test = require('node:test');
const assert = require('node:assert');
const Module = require('module');
const path = require('path');
const fs = require('fs');
const { EventEmitter } = require('events');
const { tmpDir } = require('./helpers');
const { PageCache } = require('../src/lib/offline-cache');

const userData = tmpDir('w2a-main-');
const HOME = 'https://shop.example.com/';

// ---- mock electron ------------------------------------------------------------
const osState = { online: true };
const ipcHandlers = {}, ipcOn = {};
const requests = []; // net.request calls
let netBehavior = () => ({ status: 200, headers: {}, body: '' });

class WC extends EventEmitter {
  constructor(type) { super(); this.type = type; this.id = WC.next++; this.loaded = []; this.sent = []; this.destroyed = false; this.url = ''; }
  getType() { return this.type; }
  isDestroyed() { return this.destroyed; }
  send(ch, p) { this.sent.push([ch, p]); }
  async loadURL(u, o) { this.loaded.push({ url: u, opts: o }); this.url = u; }
  async loadFile(f, o) { this.loaded.push({ file: f, opts: o }); this.url = 'file://' + f; }
  getURL() { return this.url; }
  reload() { this.reloaded = true; }
  canGoBack() { return false; } canGoForward() { return false; }
  setWindowOpenHandler(fn) { this.openHandler = fn; }
  executeJavaScript(s) { this.js = (this.js || []).concat(s); return Promise.resolve(); }
  print() {}
  getZoomLevel() { return 0; } setZoomLevel() {}
}
WC.next = 1;

class FakeWindow extends EventEmitter {
  constructor() { super(); this.webContents = new WC('window'); FakeWindow.last = this; this.kiosk = null; }
  loadFile(f, o) { this.loadedShell = { f, o }; }
  setTitle() {} setKiosk(v) { this.kiosk = v; } isFullScreen() { return false; } setFullScreen() {}
  isDestroyed() { return false; } isMinimized() { return false; } focus() {} restore() {}
  static getAllWindows() { return [FakeWindow.last]; }
}

const fakeSession = () => Object.assign(new EventEmitter(), {
  getUserAgent: () => 'Mozilla/5.0 Chrome/126 Electron/31.0.0 Shop/1.0.0 Safari/537',
  setUserAgent(ua) { this.ua = ua; },
  setPermissionRequestHandler(fn) { this.reqHandler = fn; },
  setPermissionCheckHandler(fn) { this.checkHandler = fn; },
});
const sessions = {};
const appEvents = new EventEmitter();
const electronMock = {
  app: Object.assign(appEvents, {
    getPath: () => userData, getName: () => 'Shop', getVersion: () => '1.0.0',
    requestSingleInstanceLock: () => true, whenReady: () => Promise.resolve(), quit() {}, isReady: () => true,
    setAppUserModelId() {}, setUserTasks() {}, dock: { setMenu() {} },
  }),
  BrowserWindow: FakeWindow,
  Menu: { buildFromTemplate: (t) => t, setApplicationMenu(m) { electronMock.menu = m; } },
  shell: { openExternal: async () => {}, showItemInFolder() {} },
  ipcMain: { handle: (n, fn) => { ipcHandlers[n] = fn; }, on: (n, fn) => { ipcOn[n] = fn; } },
  session: { fromPartition: (p) => (sessions[p] = sessions[p] || fakeSession()), defaultSession: fakeSession() },
  dialog: { showSaveDialog: async () => ({ canceled: true }) },
  Notification: class { static isSupported() { return false; } on() {} show() {} },
  clipboard: { text: '', writeText(t) { this.text = t; } },
  systemPreferences: { canPromptTouchID: () => false, askForMediaAccess: async () => true },
  ShareMenu: undefined,
  powerMonitor: new EventEmitter(),
  safeStorage: { isEncryptionAvailable: () => false },
  net: {
    isOnline: () => osState.online,
    request: (o) => {
      requests.push(o);
      const req = new EventEmitter();
      let body = Buffer.alloc(0);
      req.setHeader = () => {}; req.abort = () => {}; req.followRedirect = () => {};
      req.write = (b) => { body = Buffer.concat([body, b]); };
      req.end = () => setImmediate(() => {
        let r;
        try { r = netBehavior(o, body); } catch (e) { req.emit('error', e); return; }
        if (r.throw) { req.emit('error', new Error(r.throw)); return; }
        if (r.redirect) { req.emit('redirect', r.status || 302, 'GET', r.redirect, {}); return; }
        const res = new EventEmitter();
        res.statusCode = r.status; res.headers = r.headers || {};
        req.emit('response', res);
        setImmediate(() => { if (r.body) res.emit('data', Buffer.from(r.body)); res.emit('end'); });
      });
      return req;
    },
  },
};
const realLoad = Module._load;
Module._load = function (request, ...rest) { return request === 'electron' ? electronMock : realLoad.call(this, request, ...rest); };

// Seed the on-disk cache exactly as a previous online session would have.
async function seedCache(url, html) {
  const c = new PageCache({ dir: path.join(userData, 'webcache'), homeUrl: HOME, fetcher: async (u) => ({ status: 200, finalUrl: u, headers: { 'content-type': 'text/html; charset=utf-8' }, body: Buffer.from(html) }) });
  await c.fetchAndCache(url);
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test('main.js boots against the mocked Electron and wires everything up', async () => {
  // point the config at our test shop
  const cfgPath = path.join(__dirname, '..', 'src', 'app_config.json');
  const original = fs.readFileSync(cfgPath, 'utf8');
  fs.writeFileSync(cfgPath, JSON.stringify({ app_name: 'Shop', app_url: HOME, package_name: 'com.acme.shop', push_enabled: true, nav_items: [{ label: 'Orders', url: HOME + 'orders' }] }));
  try {
    await seedCache(HOME + 'orders', '<html><body>CACHED ORDERS</body></html>');
    require('../src/main.js');
    await wait(50);
  } finally {
    fs.writeFileSync(cfgPath, original);
  }

  assert.ok(FakeWindow.last, 'window created');
  assert.ok(electronMock.menu.length >= 3, 'menu built');
  assert.strictEqual(sessions['persist:webapp'].ua.includes('Electron'), false, 'UA cleaned');
  assert.ok(ipcHandlers['shell:invoke'] && ipcOn['w2a:boot'] && ipcHandlers['w2a:enqueue']);
  assert.strictEqual(FakeWindow.last.loadedShell.o.query.start, HOME);

  // shell boot data
  const shellWc = FakeWindow.last.webContents;
  const boot = await ipcHandlers['shell:invoke']({ sender: shellWc }, 'boot');
  assert.strictEqual(boot.config.app_url, HOME);
  assert.strictEqual(boot.state.online, true);
  // ...and refuses calls from anything that isn't the shell
  assert.strictEqual(await ipcHandlers['shell:invoke']({ sender: new WC('webview') }, 'boot'), null);
});

test('offline: a failed page load shows the cached copy with the real URL as its base', async () => {
  const guest = new WC('webview');
  electronMock.app.emit('web-contents-created', {}, guest);
  osState.online = false;
  guest.emit('did-fail-load', {}, -106, 'ERR_INTERNET_DISCONNECTED', HOME + 'orders', true);
  await wait(30);
  assert.strictEqual(guest.loaded.length, 1);
  assert.match(guest.loaded[0].url, /^data:text\/html;charset=utf-8;base64,/);
  assert.match(Buffer.from(guest.loaded[0].url.split(',')[1], 'base64').toString(), /CACHED ORDERS/);
  assert.strictEqual(guest.loaded[0].opts.baseURLForDataURL, HOME + 'orders');

  // the preload boot handshake for that data: page is trusted (base URL is ours) and gets the hooks
  const boot = await new Promise((resolve) => ipcOn['w2a:boot']({ sender: guest, set returnValue(v) { resolve(v); } }, 'data:text/html;base64,AAAA'));
  assert.strictEqual(boot.trusted, true);
  assert.match(boot.script, /installPageHooks/);
  assert.match(boot.script, /"baseUrl":"https:\/\/shop\.example\.com\/orders"/);
  assert.strictEqual(boot.online, false);

  // the shell was told we're offline
  const states = FakeWindow.last.webContents.sent.filter((s) => s[0] === 'shell:state').map((s) => s[1].online);
  assert.ok(states.includes(false));
});

test('offline + nothing cached -> the offline page with the failed URL remembered', async () => {
  const guest = new WC('webview');
  electronMock.app.emit('web-contents-created', {}, guest);
  guest.emit('did-fail-load', {}, -105, 'ERR_NAME_NOT_RESOLVED', HOME + 'never-visited', true);
  await wait(30);
  assert.ok(guest.loaded[0].file && guest.loaded[0].file.endsWith('offline.html'));
  const boot = await new Promise((resolve) => ipcOn['w2a:boot']({ sender: guest, set returnValue(v) { resolve(v); } }, 'file:///app/src/offline.html?primary=%23fff'));
  assert.strictEqual(boot.offlinePage, true);
  assert.strictEqual(boot.trusted, false);
  assert.strictEqual(boot.script, null);
});

test('ignored failures: aborted loads, sub-frames, certificate errors', async () => {
  const guest = new WC('webview');
  electronMock.app.emit('web-contents-created', {}, guest);
  guest.emit('did-fail-load', {}, -3, 'ERR_ABORTED', HOME + 'orders', true);
  guest.emit('did-fail-load', {}, -106, 'x', HOME + 'orders', false);
  guest.emit('did-fail-load', {}, -201, 'ERR_CERT_DATE_INVALID', HOME + 'orders', true);
  await wait(30);
  assert.strictEqual(guest.loaded.length, 0);
});

test('IPC from a non-guest sender is rejected; guest can queue while offline', async () => {
  const guest = new WC('webview');
  electronMock.app.emit('web-contents-created', {}, guest); // becomes the current guest
  const payload = JSON.stringify({ url: HOME + 'api/save', method: 'POST', enctype: 'application/x-www-form-urlencoded', fields: [{ key: 'a', type: 'text', value: '1' }], ts: Date.now() });
  assert.deepStrictEqual(await ipcHandlers['w2a:enqueue']({ sender: new WC('webview') }, payload), { ok: false });
  assert.deepStrictEqual(await ipcHandlers['w2a:enqueue']({ sender: guest }, payload), { ok: true });
  assert.deepStrictEqual(await ipcHandlers['w2a:enqueue']({ sender: guest }, JSON.stringify({ url: 'https://evil.com/x', fields: [] })), { ok: false });
  const st = await ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, 'get-settings');
  assert.strictEqual(st.queueSize, 1);
  assert.ok(fs.readFileSync(path.join(userData, 'offline_queue.jsonl'), 'utf8').includes('api/save'));
});

test('back online: the queue is replayed to the site and the shell is told', async () => {
  netBehavior = (o) => {
    if (String(o.url).endsWith('/api/save')) return { status: 200 };
    return { status: 200, headers: {} }; // reachability probe
  };
  requests.length = 0;
  osState.online = true;
  await wait(3300); // connectivity poll interval is 3 s
  await wait(200);
  const post = requests.find((r) => String(r.url).endsWith('/api/save'));
  assert.ok(post, 'queued POST was replayed');
  assert.strictEqual(post.method, 'POST');
  assert.strictEqual(post.useSessionCookies, true);
  const shellMsgs = FakeWindow.last.webContents.sent;
  assert.ok(shellMsgs.some((m) => m[0] === 'shell:toast' && /saved item sent/.test(m[1])));
  assert.ok(shellMsgs.filter((m) => m[0] === 'shell:state').pop()[1].queueSize === 0);
});

test('features left out of the build are inert and hidden from settings', async () => {
  const call = (name, arg) => ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, name, arg);
  // default config has app lock and kiosk switched off
  assert.strictEqual(await call('lock-set-pin', '4821'), false);
  assert.strictEqual(await call('lock-disable', '4821'), false);
  assert.strictEqual(await call('set-kiosk', true), false);
  const s = await call('get-settings');
  assert.strictEqual(s.appLockAvailable, false);
  assert.strictEqual(s.appLock, false);
  assert.strictEqual(s.kioskAvailable, false);
  assert.strictEqual(s.kiosk, false);
  assert.strictEqual(await call('set-notifications', false), false);
  assert.strictEqual(typeof (await call('clear-cache')), 'number');
});

test('applock_enabled at build time forces PIN setup on first run and blocks disabling later', async () => {
  const cfgPath = path.join(__dirname, '..', 'src', 'app_config.json');
  const original = fs.readFileSync(cfgPath, 'utf8');
  // fresh userData dir so there's no PIN already saved from an earlier test
  const freshUserData = tmpDir('w2a-main-applock-');
  electronMock.app.getPath = () => freshUserData;
  fs.writeFileSync(cfgPath, JSON.stringify({ app_name: 'Shop', app_url: HOME, package_name: 'com.acme.shop', applock_enabled: true }));
  try {
    delete require.cache[require.resolve('../src/main.js')];
    require('../src/main.js');
    await wait(50);
  } finally {
    fs.writeFileSync(cfgPath, original);
  }

  const call = (name, arg) => ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, name, arg);
  const boot = await call('boot');
  assert.strictEqual(boot.state.locked, true, 'app opens locked when app lock is required and no PIN exists yet');
  assert.strictEqual(boot.state.lockSetupRequired, true);
  assert.strictEqual(boot.settings.appLockRequired, true);
  assert.strictEqual(boot.settings.appLock, false);

  assert.strictEqual(await call('lock-set-pin', '7777'), true);
  const after = await call('get-settings');
  assert.strictEqual(after.appLock, true);
  const state2 = await ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, 'boot');
  assert.strictEqual(state2.state.locked, false, 'unlocks automatically once the forced setup PIN is created');
  assert.strictEqual(state2.state.lockSetupRequired, false);

  // and it genuinely cannot be turned off from Settings on this build
  assert.strictEqual(await call('lock-disable', '7777'), false);
  assert.strictEqual((await call('get-settings')).appLock, true);
});

test('remember_login_enabled=false blocks saving/returning a login; forget-login clears one when it is enabled', async () => {
  const cfgPath = path.join(__dirname, '..', 'src', 'app_config.json');
  const original = fs.readFileSync(cfgPath, 'utf8');
  const sharedUserData = tmpDir('w2a-main-remember-');
  // vault needs a working encrypt/decrypt to actually persist anything
  const originalSafeStorage = electronMock.safeStorage;
  electronMock.safeStorage = {
    isEncryptionAvailable: () => true,
    encryptString: (s) => Buffer.from('ENC:' + s),
    decryptString: (b) => b.toString().slice(4),
  };

  function reboot(cfg) {
    fs.writeFileSync(cfgPath, JSON.stringify(Object.assign({ app_name: 'Shop', app_url: HOME, package_name: 'com.acme.shop' }, cfg)));
    electronMock.app.getPath = () => sharedUserData;
    delete require.cache[require.resolve('../src/main.js')];
    require('../src/main.js');
    return wait(50);
  }
  function simulateLogin(guest, u, p) {
    ipcOn['w2a:cred-capture']({ sender: guest }, u, p);
    guest.emit('did-navigate', {}, HOME + 'login'); // onMainFrameCommitted tracks lastPageUrl
    guest.emit('did-navigate', {}, HOME + 'dashboard'); // moving off a login page triggers the save
  }

  try {
    // ---- disabled: nothing is captured or offered, even though vault encryption works fine
    await reboot({ remember_login_enabled: false });
    let guest = new WC('webview');
    electronMock.app.emit('web-contents-created', {}, guest);
    const bootDisabled = await new Promise((resolve) => ipcOn['w2a:boot']({ sender: guest, set returnValue(v) { resolve(v); } }, HOME + 'login'));
    assert.strictEqual(bootDisabled.credentials, false);
    simulateLogin(guest, 'amina@example.com', 'hunter2');
    await wait(20);
    const settingsDisabled = await ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, 'get-settings');
    assert.strictEqual(settingsDisabled.rememberLoginEnabled, false);
    assert.strictEqual(settingsDisabled.hasSavedLogin, false);
    assert.strictEqual(await ipcHandlers['w2a:cred-get']({ sender: guest }), null);

    // ---- re-enabled, same userData dir: proves nothing leaked to disk while it was off
    await reboot({ remember_login_enabled: true });
    guest = new WC('webview');
    electronMock.app.emit('web-contents-created', {}, guest);
    assert.strictEqual(await ipcHandlers['w2a:cred-get']({ sender: guest }), null);

    const bootEnabled = await new Promise((resolve) => ipcOn['w2a:boot']({ sender: guest, set returnValue(v) { resolve(v); } }, HOME + 'login'));
    assert.strictEqual(bootEnabled.credentials, true);
    simulateLogin(guest, 'amina@example.com', 'hunter2');
    await wait(20);
    const saved = await ipcHandlers['w2a:cred-get']({ sender: guest });
    assert.deepStrictEqual(saved, { u: 'amina@example.com', p: 'hunter2' });
    const settingsEnabled = await ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, 'get-settings');
    assert.strictEqual(settingsEnabled.hasSavedLogin, true);

    // ---- Settings -> Forget saved login
    await ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, 'forget-login');
    assert.strictEqual(await ipcHandlers['w2a:cred-get']({ sender: guest }), null);
    const settingsAfterForget = await ipcHandlers['shell:invoke']({ sender: FakeWindow.last.webContents }, 'get-settings');
    assert.strictEqual(settingsAfterForget.hasSavedLogin, false);
  } finally {
    fs.writeFileSync(cfgPath, original);
    electronMock.safeStorage = originalSafeStorage;
  }
});

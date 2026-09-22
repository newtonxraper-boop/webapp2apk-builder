'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { installPageHooks, buildInjection } = require('../src/lib/page-script');

// ---- a tiny fake browser, just enough for the hooks ----------------------
class FakeForm {
  constructor(o) { this.attrs = { method: o.method }; this.action = o.action; this.enctype = o.enctype || 'application/x-www-form-urlencoded'; this.fields = o.fields || []; }
  getAttribute(n) { return n in this.attrs ? this.attrs[n] : null; }
  querySelectorAll() { const a = []; a.forEach = Array.prototype.forEach; return a; }
}
class FakeFormData {
  constructor(form, submitter) { this.list = form.fields.slice(); if (submitter && submitter.name) this.list.push([submitter.name, submitter.value]); }
  forEach(cb) { this.list.forEach(([k, v]) => cb(v, k)); }
}
function makeXhrClass() {
  return class FakeXHR {
    constructor() { this.responseType = ''; }
    open(method, url) { this.opened = [method, url]; }
    setRequestHeader(k, v) { this.h = (this.h || {}); this.h[k] = v; }
    send(body) { this.realSent = body; this.sentReal = true; }
    dispatchEvent(e) { (this.events = this.events || []).push(e.type); }
  };
}
class FakeEvent { constructor(type) { this.type = type; } }

function env(opts) {
  opts = opts || {};
  const listeners = {};
  const queued = [];
  const realFetchCalls = [];
  const win = {
    location: { href: opts.href || 'https://shop.example.com/orders' },
    navigator: { onLine: true },
    HTMLFormElement: FakeForm, FormData: FakeFormData, File: File,
    FileReader: class { readAsDataURL() {} }, URL, URLSearchParams, Response, Request,
    Event: FakeEvent, ProgressEvent: FakeEvent,
    XMLHttpRequest: makeXhrClass(),
    console: { warn() {} },
    fetch: async (...a) => { realFetchCalls.push(a); return new Response('real'); },
  };
  const doc = { addEventListener: (t, fn) => { (listeners[t] = listeners[t] || []).push(fn); } };
  const state = { online: opts.online === undefined ? false : opts.online, notified: [] };
  const bridge = {
    enqueue: async (json) => { queued.push(JSON.parse(json)); return { ok: opts.enqueueOk !== false }; },
    isOnline: () => state.online,
    notifyQueued: () => state.notified.push('queued'),
    notifyQueueFailed: () => state.notified.push('failed'),
    print: () => state.notified.push('print'),
  };
  installPageHooks(win, doc, bridge, { baseUrl: opts.baseUrl });
  const submit = (form, extra) => { const e = Object.assign({ target: form, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } }, extra || {}); (listeners.submit || []).forEach((fn) => fn(e)); return e; };
  return { win, doc, bridge, queued, state, submit, realFetchCalls };
}
const tick = () => new Promise((r) => setTimeout(r, 5));

test('offline POST form is intercepted and queued with its fields and submitter', async () => {
  const e = env();
  const form = new FakeForm({ method: 'post', action: 'https://shop.example.com/api/order', fields: [['qty', '3'], ['note', 'hi']] });
  const ev = e.submit(form, { submitter: { name: 'action', value: 'save' } });
  await tick();
  assert.strictEqual(ev.defaultPrevented, true);
  assert.strictEqual(e.queued.length, 1);
  assert.strictEqual(e.queued[0].url, 'https://shop.example.com/api/order');
  assert.strictEqual(e.queued[0].method, 'POST');
  assert.deepStrictEqual(e.queued[0].fields.map((f) => [f.key, f.value]), [['qty', '3'], ['note', 'hi'], ['action', 'save']]);
  assert.deepStrictEqual(e.state.notified, ['queued']);
});

test('offline GET form (search) is NOT queued - it navigates normally', async () => {
  const e = env();
  const ev = e.submit(new FakeForm({ method: undefined, action: 'https://shop.example.com/search', fields: [['q', 'x']] }));
  await tick();
  assert.strictEqual(ev.defaultPrevented, false);
  assert.strictEqual(e.queued.length, 0);
});

test('a form the page already handled itself (defaultPrevented) is left to the fetch/XHR hooks', async () => {
  const e = env();
  e.submit(new FakeForm({ method: 'post', action: '/x', fields: [] }), { defaultPrevented: true });
  await tick();
  assert.strictEqual(e.queued.length, 0);
});

test('online submit is untouched', async () => {
  const e = env({ online: true });
  const ev = e.submit(new FakeForm({ method: 'post', action: 'https://shop.example.com/a', fields: [['a', '1']] }));
  await tick();
  assert.strictEqual(ev.defaultPrevented, false);
  assert.strictEqual(e.queued.length, 0);
});

test('offline fetch POST: queued with JSON body + content-type, resolves like a success', async () => {
  const e = env();
  const res = await e.win.fetch('/api/items', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Secret': 'no' }, body: '{"a":1}' });
  assert.strictEqual(res.status, 200);
  assert.deepStrictEqual(await res.json(), { queued: true, offline: true });
  assert.strictEqual(e.queued.length, 1);
  assert.strictEqual(e.queued[0].url, 'https://shop.example.com/api/items');
  assert.strictEqual(e.queued[0].enctype, 'raw');
  assert.deepStrictEqual(e.queued[0].headers, { 'content-type': 'application/json' });
  assert.strictEqual(e.queued[0].fields[0].value, '{"a":1}');
  assert.strictEqual(e.realFetchCalls.length, 0);
});

test('offline fetch DELETE with no body is still queued (not silently dropped)', async () => {
  const e = env();
  await e.win.fetch('/api/items/5', { method: 'DELETE' });
  assert.strictEqual(e.queued.length, 1);
  assert.strictEqual(e.queued[0].method, 'DELETE');
});

test('offline fetch with a Request object keeps its method and body', async () => {
  const e = env();
  const req = new Request('https://shop.example.com/api/x', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"z":9}' });
  await e.win.fetch(req);
  assert.strictEqual(e.queued[0].method, 'POST');
  assert.strictEqual(e.queued[0].fields[0].value, '{"z":9}');
});

test('offline fetch GET and all online fetches go straight through', async () => {
  const e = env();
  await e.win.fetch('/api/list');
  assert.strictEqual(e.realFetchCalls.length, 1);
  const on = env({ online: true });
  await on.win.fetch('/api/items', { method: 'POST', body: 'x' });
  assert.strictEqual(on.realFetchCalls.length, 1);
  assert.strictEqual(on.queued.length, 0);
});

test('garbage URLs ("{}", "[object Object]") are refused and reported as failure', async () => {
  const e = env();
  await e.win.fetch('{}', { method: 'POST', body: 'x' });
  await e.win.fetch({ toString() { return '[object Object]'; } }, { method: 'POST', body: 'x' });
  assert.strictEqual(e.queued.length, 0);
  assert.deepStrictEqual(e.state.notified, ['failed', 'failed']);
});

test('offline XHR write is queued and completes with load/loadend + fake 200', async () => {
  const e = env();
  const x = new e.win.XMLHttpRequest();
  let loaded = false;
  x.onload = () => { loaded = true; };
  x.open('POST', '/api/save');
  x.setRequestHeader('Content-Type', 'application/json');
  x.setRequestHeader('X-Nope', '1');
  x.send('{"k":1}');
  await tick(); await tick();
  assert.strictEqual(e.queued.length, 1);
  assert.deepStrictEqual(e.queued[0].headers, { 'content-type': 'application/json' });
  assert.strictEqual(x.status, 200);
  assert.strictEqual(x.readyState, 4);
  assert.strictEqual(JSON.parse(x.responseText).queued, true);
  assert.strictEqual(loaded, true);
  assert.ok(x.events.includes('loadend'));
  assert.strictEqual(x.realSent, undefined);
});

test('online XHR and offline XHR GET pass through to the real send()', () => {
  const on = env({ online: true });
  const x = new on.win.XMLHttpRequest(); x.open('POST', '/a'); x.send('b');
  assert.strictEqual(x.realSent, 'b');
  const off = env();
  const y = new off.win.XMLHttpRequest(); y.open('GET', '/a'); y.send();
  assert.strictEqual(y.sentReal, true);
});

test('cached page (data: URL) resolves relative URLs against the real address', async () => {
  const e = env({ href: 'data:text/html;base64,AAAA', baseUrl: 'https://shop.example.com/orders/list' });
  await e.win.fetch('save', { method: 'POST', body: 'x' });
  assert.strictEqual(e.queued[0].url, 'https://shop.example.com/orders/save');
});

test('window.print is routed to the native dialog; install is idempotent', () => {
  const e = env();
  e.win.print();
  assert.deepStrictEqual(e.state.notified, ['print']);
  const before = e.win.fetch;
  installPageHooks(e.win, e.doc, e.bridge, {});
  assert.strictEqual(e.win.fetch, before);
});

test('buildInjection produces a self-contained script that evaluates', () => {
  const src = buildInjection({ maxFileBytes: 123, baseUrl: 'https://x.com/' });
  const e = env();
  const w = Object.assign({}, e.win, { __w2aQueueInstalled: false, __w2a: e.bridge });
  // Only `window` and `document` exist in scope - proves nothing else is captured.
  assert.doesNotThrow(() => new Function('window', 'document', src)(w, e.doc));
  assert.strictEqual(w.__w2aQueueInstalled, true);
  assert.strictEqual(typeof w.print, 'function');
});

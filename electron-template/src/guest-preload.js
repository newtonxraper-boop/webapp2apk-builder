'use strict';

/**
 * Preload for the <webview> that shows the actual website. Runs in an isolated
 * world before any of the site's own scripts.
 *
 *  - Asks the main process (synchronously, once per page) what this page is
 *    allowed to get. Only pages on the app's own host are "trusted"; anything
 *    else gets nothing at all.
 *  - Exposes the small API surface to the page through contextBridge:
 *      window.DesktopApp                       (desktop-native names)
 *      window.AndroidShare / AndroidPrint / AndroidScanQR / AndroidNotify /
 *      AndroidNfc / AndroidRetry               (same names + signatures the
 *        Android build gives the page, so a site written for the APK's
 *        bridges keeps working unchanged inside the .exe / .dmg)
 *  - Injects the offline-queue hooks (see lib/page-script.js) into the page.
 *  - Auto-fills a saved login (isolated world, so the page's own scripts can
 *    never read the stored credentials).
 *
 * Keep this file self-contained: it must only require('electron'), because
 * sandboxed preloads can't load other local files.
 */

const { contextBridge, ipcRenderer, webFrame } = require('electron');

(function main() {
  let boot = null;
  try {
    boot = ipcRenderer.sendSync('w2a:boot', String(window.location.href).slice(0, 2048));
  } catch (e) {
    return;
  }
  if (!boot || (!boot.trusted && !boot.offlinePage)) return;

  let online = !!boot.online;
  ipcRenderer.on('w2a:online', (_e, value) => { online = !!value; });

  const expose = (name, api) => {
    try { contextBridge.exposeInMainWorld(name, api); } catch (e) { /* already defined */ }
  };

  const retry = () => ipcRenderer.send('w2a:retry');

  if (boot.offlinePage) {
    expose('AndroidRetry', { retry });
    expose('DesktopApp', { isDesktop: true, platform: boot.platform, retry });
    return;
  }

  // ---- keep pages alive: an ordinary link click opens the next page in a new
  // background-kept view, so Back returns to the previous page exactly as it
  // was (no reload). Anything unusual is left to the browser.
  window.addEventListener('click', (e) => {
    try {
      if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
      const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (!a || a.hasAttribute('download')) return;
      const t = (a.getAttribute('target') || '').toLowerCase();
      if (t && t !== '_self' && t !== '_top') return;
      const href = a.href;
      if (!/^https?:/i.test(href)) return;
      const u = new URL(href);
      const cur = new URL(window.location.href);
      if (u.origin !== cur.origin) return;
      if (u.href.split('#')[0] === cur.href.split('#')[0]) return; // same page / hash jump
      e.preventDefault();
      ipcRenderer.send('w2a:open-page', u.href);
    } catch (err) { /* fall back to a normal navigation */ }
  });

  // ---- internal bridge used by the injected page script ----------------
  expose('__w2a', {
    enqueue: (json) => ipcRenderer.invoke('w2a:enqueue', String(json)),
    isOnline: () => online,
    notifyQueued: () => ipcRenderer.send('w2a:queued'),
    notifyQueueFailed: () => ipcRenderer.send('w2a:queue-failed'),
    print: () => ipcRenderer.send('w2a:print'),
  });

  // ---- public API ---------------------------------------------------------
  const shareText = (text, subject) => {
    if (text === undefined || text === null || String(text) === '') return;
    ipcRenderer.send('w2a:share-text', String(text), subject ? String(subject) : '');
  };
  const shareFileBase64 = (base64Data, fileName, mimeType) =>
    ipcRenderer.invoke('w2a:share-file', String(base64Data || ''), String(fileName || 'file'), String(mimeType || 'application/octet-stream'));
  const scheduleNotification = (id, title, body, delaySeconds) =>
    ipcRenderer.send('w2a:notify-schedule', Number(id) || 0, String(title || ''), String(body || ''), Number(delaySeconds) || 0);
  const cancelNotification = (id) => ipcRenderer.send('w2a:notify-cancel', Number(id) || 0);
  const scanQR = () => ipcRenderer.send('w2a:qr-scan');
  const printPage = () => ipcRenderer.send('w2a:print');
  // Taskbar (Windows) / dock (macOS) badge. 0, null or invalid clears it.
  const setBadge = (n) => ipcRenderer.send('w2a:badge', Number(n) || 0);

  expose('DesktopApp', {
    isDesktop: true,
    platform: boot.platform,
    retry,
    share: { text: shareText, fileBase64: shareFileBase64 },
    notify: { schedule: scheduleNotification, cancel: cancelNotification },
    scanQR,
    print: printPage,
    setBadge,
    clearBadge: () => setBadge(0),
    isOnline: () => online,
  });
  expose('AndroidShare', { shareText, shareFileBase64 });
  expose('AndroidPrint', { printPage });
  expose('AndroidScanQR', { scan: scanQR });
  expose('AndroidNotify', { schedule: scheduleNotification, cancel: cancelNotification });
  expose('AndroidRetry', { retry });
  // NFC readers/writers don't exist on desktops - answer "not available" so
  // pages that feature-detect simply hide their NFC UI.
  expose('AndroidNfc', { isAvailable: () => false, writeTextOnNextTap: () => {} });

  // ---- inject the offline-queue hooks -----------------------------------
  const inject = () => {
    if (!boot.script) return;
    try {
      const p = webFrame.executeJavaScript(boot.script);
      if (p && typeof p.catch === 'function') p.catch(() => {});
    } catch (e) { /* never break the page over this */ }
  };
  inject();
  // installPageHooks is idempotent; running it again once the DOM is parsed
  // guarantees it's in place even if the first attempt raced page start-up.
  window.addEventListener('DOMContentLoaded', inject, { once: true });

  // ---- saved-login auto-fill ------------------------------------------------
  if (boot.credentials) {
    const path = (window.location.pathname || '').toLowerCase();
    const skipPath = /register|signup|sign-up|reset|forgot|change|recover/.test(path);
    const run = () => {
      if (skipPath) return;
      const passwords = document.querySelectorAll('input[type=password]');
      if (passwords.length !== 1) return; // sign-up / change-password forms have 2+
      const pw = passwords[0];
      const form = pw.form;
      if (!form) return;
      const pickUser = () =>
        form.querySelector('input[type=email]') ||
        form.querySelector('input[type=text]') ||
        form.querySelector('input:not([type=password]):not([type=hidden]):not([type=submit]):not([type=checkbox])');

      ipcRenderer.invoke('w2a:cred-get').then((saved) => {
        if (!saved) return;
        const userField = pickUser();
        if (!userField || userField.value || pw.value) return;
        const fill = (el, value) => {
          el.value = value;
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        };
        fill(userField, saved.u);
        fill(pw, saved.p);
        const remember = form.querySelector('input[type=checkbox]');
        if (remember && !remember.checked) remember.checked = true;
        setTimeout(() => {
          if (typeof form.requestSubmit === 'function') form.requestSubmit();
          else form.submit();
        }, 50);
      }).catch(() => {});

      form.addEventListener('submit', () => {
        try {
          const userField = pickUser();
          if (userField && pw.value) ipcRenderer.send('w2a:cred-capture', String(userField.value), String(pw.value));
        } catch (e) { /* ignore */ }
      }, true);
    };
    if (document.readyState === 'loading') window.addEventListener('DOMContentLoaded', run, { once: true });
    else run();
  }
})();

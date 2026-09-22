'use strict';

/**
 * Preload for the local shell window (shell.html - the app chrome around the
 * website: banners, tabs, settings, lock screen, QR scanner). Exposes a tiny,
 * fixed API; the shell never gets Node access.
 */

const { contextBridge, ipcRenderer } = require('electron');

const CHANNELS = new Set(['shell:state', 'shell:toast', 'shell:open-settings', 'shell:qr-open']);

contextBridge.exposeInMainWorld('shellApi', {
  invoke: (name, arg) => ipcRenderer.invoke('shell:invoke', String(name), arg),
  on: (channel, callback) => {
    if (!CHANNELS.has(channel) || typeof callback !== 'function') return;
    ipcRenderer.on(channel, (_event, payload) => callback(payload));
  },
});

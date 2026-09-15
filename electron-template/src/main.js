'use strict';

const { app, BrowserWindow, shell } = require('electron');
const path = require('path');

// These get replaced by scripts/generate_desktop_resources.js before the
// build - if you ever see the literal "{{...}}" text on screen, the
// generator step didn't run.
const APP_NAME = '{{APP_NAME}}';
const APP_URL = '{{APP_URL}}';
const PRIMARY_COLOR = '{{PRIMARY_COLOR}}';

let mainWindow = null;

function isSameOriginOrSubpath(targetUrl) {
  try {
    const target = new URL(targetUrl);
    const home = new URL(APP_URL);
    return target.hostname === home.hostname;
  } catch (e) {
    return false;
  }
}

function loadOfflineFallback() {
  if (!mainWindow) return;
  mainWindow.loadFile(path.join(__dirname, 'offline.html'));
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 480,
    minHeight: 360,
    backgroundColor: /^#[0-9A-Fa-f]{6}$/.test(PRIMARY_COLOR) ? PRIMARY_COLOR : '#0f1115',
    title: APP_NAME,
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });

  mainWindow.setTitle(APP_NAME);

  // Links to a different site (mailto:, target=_blank to another domain,
  // etc.) open in the user's normal browser instead of hijacking this
  // window - mirrors how the Android build handles external links.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isSameOriginOrSubpath(url)) {
      return { action: 'allow' };
    }
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!isSameOriginOrSubpath(url)) {
      event.preventDefault();
      shell.openExternal(url);
    }
  });

  mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL, isMainFrame) => {
    // -3 is ERR_ABORTED, which fires on normal navigations/redirects too -
    // treating that as a real failure would flash the offline page on
    // every ordinary link click.
    if (isMainFrame && errorCode !== -3) {
      loadOfflineFallback();
    }
  });

  mainWindow.loadURL(APP_URL);

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
  app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});

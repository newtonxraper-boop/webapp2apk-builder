# Desktop app: offline mode and APK-parity features

The Windows (.exe) and macOS (.dmg) apps are built from the same settings as the Android APK and behave the same way offline.

## Offline (same rules as the APK)
- **Page cache**: every page on the app's own host is saved to disk (gzip, ETag/Last-Modified revalidation, 10-100 MB cap, 500 files max, oldest evicted first). Login/logout/signin/signup/checkout/payment/cart pages are never cached. Only HTML pages up to ~1.4 MB are cached (larger ones can't be shown offline).
- **Offline fallback**: if a page can't load and the app is offline, the cached copy opens (with the real address as its base, so links, cookies and origin behave normally). If nothing is cached, a branded "You're offline" page with *Try again* opens, and it loads by itself when the connection returns.
- **Offline banner** ("No internet connection - click to retry") and a "Back online" notice.
- **Reachability probe + captive-portal protection**: a HEAD request to the app's own domain (GET fallback); a redirect to a different host counts as *not reachable*. A cached response that ended up on another site is never stored.
- **Offline submission queue**: form submits and fetch()/XHR writes made while offline are saved on-device (20 MB cap, double-tap de-duplicated, photos over 4 MB shrunk, JSON content-type preserved) and replayed with the live login session when back online, with a "N items waiting to sync" banner, *Send now*, retry backoff (5/15/30/60 s) and a 60 s safety-net retry. If the app is closed while items are queued, they are sent the next time it opens.
- **Nav-tab prefetch**: the bottom-tab pages are cached in the background after the home page loads.

## Features carried over from the APK
| Android | Desktop |
|---|---|
| Splash, bottom tabs, privacy notice, update banner (`/version.json`) | Same (update banner reads `exe_url`/`windows_url` or `dmg_url`/`mac_url`/`desktop_url`) |
| Share / Refresh / Settings buttons | Same. Share = native share sheet on macOS, copy link on Windows |
| Settings: App lock, Kiosk, Notifications, Offline sync, Storage, Version | Same. App lock = app PIN (+ Touch ID on supported Macs) |
| Kiosk mode | Fullscreen kiosk; toggle in Settings or Ctrl/Cmd+, |
| Credential vault / auto re-login | Same, encrypted with Windows DPAPI / macOS Keychain |
| `AndroidShare`, `AndroidPrint`, `AndroidScanQR`, `AndroidNotify`, `AndroidLocation`, `AndroidRetry` | Same names and callbacks (`onQRScanResult`, `onLocationResult`, `onGeofenceEvent`...) - a site written for the APK bridges works unchanged. Also available as `window.DesktopApp` |
| `AndroidNfc` | Present, always reports "not available" (no NFC on desktops) |
| Home-screen shortcuts | Windows jump-list tasks / macOS dock menu |
| Crash reports | Written to disk, uploaded next launch when online |

## Known differences from Android
- The queue and reminders only run while the app is open (no background service on desktop).
- QR scanning: QR codes everywhere; 1D barcodes only where Chromium has `BarcodeDetector` (macOS).
- Geolocation/geofencing depend on the OS location service being enabled.
- No push (FCM); websites can still use the Web Notification API when `push_enabled` is on.

## Tests
`npm test` (Node 18+) runs the offline logic tests and a main-process smoke test against a mocked Electron.

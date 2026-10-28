# "Sign in with Google" inside the made apps

## Why it failed
Google refuses to sign anyone in inside an app's built-in browser (WebView) - it answers "disallowed_useragent".
So the Android app opens Google in **Chrome** (a Custom Tab). Chrome has its own cookies, so the login finished
in Chrome and the app stayed signed out.

## How it works now (Android)
1. In the app the person taps *Continue with Google*. The app opens Google in Chrome.
2. Google sends them back to **your site** (still in Chrome). The site checks Google, then makes a **one-time token**
   (valid 3 minutes, single use).
3. The site shows "Going back to the app..." and opens `<package-name>://auth?url=https://yoursite/login.php?app_token=...`.
4. The app (intent-filter in `AndroidManifest.xml`, `resolveDeepLinkUrl()` in `MainActivity.java`) opens that address in
   its **own** browser. Only an address on the app's own site is ever opened. The site swaps the token for a normal sign-in.

## What your SITE must do (the app alone cannot do this)
- Recognise the app: Android WebView sends the header `X-Requested-With: <package-name>` on every request.
- When Google sign-in starts from the app, keep the OAuth `state` in your **database** (not the session - Chrome has another
  session), together with the app's package name and a hash of a random secret that you also put in the app's session.
- When Google returns in Chrome: finish the sign-in with Google, store a one-time token (hash it, 3 minutes), and send the person to
  `<package-name>://auth?url=<your login page>?app_token=<token>` (use an `intent://...#Intent;scheme=...;package=...;end` link plus a
  visible "Open the app" button).
- On `?app_token=` inside the app: require the same session secret, delete the token, then sign the person in.
- The School Chat site already does all of this - see `includes/google.php` in `school-chat` (`sc_google_app_*` functions).
  Nothing changes in Google Cloud: the redirect URIs stay `https://yoursite/login.php` and `https://yoursite/register.php`.

## Desktop (Electron: Windows .exe / macOS .dmg)
Same hand-back as Android, using the app's own link scheme instead of an Android intent.

1. The app sends `X-Requested-With: <scheme>` on every page load of your site (`<scheme>` = the package name, lowercase,
   `_` -> `-`, e.g. `com.webapp2apk.schoolchat`). The site reads it exactly like the Android header.
2. When the site redirects to Google's sign-in page (`accounts.google.com`, with a `redirect_uri` on **your** site), the app
   cancels that load and opens the page in the person's **default browser** (`handOffGoogleSignIn()` in `src/main.js`).
3. Google returns to your site in the browser; the site makes the one-time token and opens
   `<scheme>://auth?url=https://yoursite/login.php?app_token=...` (the "Going back to the app..." page).
4. The operating system gives that link to the desktop app (Windows: second launch / `second-instance`; macOS: `open-url`).
   `parseAuthLink()` in `src/lib/protocol.js` accepts it only if the address is on the app's own site, and the app opens it
   in its own window, where the site swaps the token for a normal sign-in.

The installer registers the scheme (`build.protocols` in `electron-template/package.json`, filled in by
`scripts/generate_desktop_resources.js`) and the app also registers it at start-up.

Notes
- Google sign-in that runs entirely in the page (a JavaScript popup, or a `redirect_uri` on another site) is not touched
  and behaves as before.
- A site must support the hand-back on its server for this to work (`includes/google.php` in `school-chat` does).
  Existing installs need the **new build** of the desktop app; the website part works for old and new apps.
- Windows may ask once "Open <app name>?" in the browser - that is the normal prompt for a custom link.

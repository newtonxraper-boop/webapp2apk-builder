package com.webapp2apk.generated;

import android.app.Activity;
import android.webkit.JavascriptInterface;

/**
 * Exposed to the WebView as window.AndroidScanQR. Call from the page as:
 *
 *   AndroidScanQR.scan();
 *   window.onQRScanResult = function(text) { ... };   // called on success
 *   window.onQRScanError  = function(reason) { ... };  // called on cancel/error
 *
 * The actual camera permission check, scanner launch, and result delivery
 * all live in MainActivity (launchQrScanner/deliverQrScanResult) since they
 * need registerForActivityResult, which only an Activity can own - this
 * class is just the thin, stable JS-facing entry point.
 */
final class QrScanBridge {

    private final MainActivity activity;

    QrScanBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void scan() {
        activity.runOnUiThread(activity::launchQrScanner);
    }
}

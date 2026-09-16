package com.webapp2apk.generated;

import android.app.Activity;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * Exposed to the WebView as window.AndroidPrint. MainActivity injects a
 * small script on every page load that overrides window.print() to call
 * AndroidPrint.printPage() instead - so any site that already calls the
 * standard window.print() (e.g. a "Print receipt" button) just works,
 * with zero changes needed on the site's side. Printing itself uses
 * Android's own PrintManager, which renders the WebView's current page
 * exactly as displayed (images, CSS, everything) via a system print
 * dialog - no server-side PDF generation needed.
 */
final class PrintBridge {

    private final Activity activity;
    private final WebView webView;

    PrintBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void printPage() {
        activity.runOnUiThread(() -> {
            try {
                PrintManager printManager = (PrintManager) activity.getSystemService(Activity.PRINT_SERVICE);
                if (printManager == null) return;
                String jobName = activity.getString(R.string.app_name) + " Document";
                PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
                printManager.print(jobName, adapter, new PrintAttributes.Builder().build());
            } catch (Exception ignored) {
                // No printer/print service available on this device - silently
                // do nothing rather than crash the app over an optional feature.
            }
        });
    }
}

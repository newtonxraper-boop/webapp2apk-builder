package com.webapp2apk.generated;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Exposed to the WebView as window.AndroidShare. The floating share button
 * in MainActivity only ever shares "the current page's URL" - useful for
 * "send this page to someone", but many of these web apps have their own
 * in-page share actions (share an invoice total, a farmer's price alert, a
 * generated report) that need to hand Android real text or a real file, not
 * a link back to a page that requires login to view. This bridge is what
 * lets the page's own JS trigger that native share sheet directly.
 */
class ShareBridge {

    private static final long MAX_SHARE_FILE_BYTES = 8L * 1024 * 1024;

    private final Activity activity;

    ShareBridge(Activity activity) {
        this.activity = activity;
    }

    /** AndroidShare.shareText("some text", "optional subject") */
    @android.webkit.JavascriptInterface
    public void shareText(String text) {
        shareText(text, "");
    }

    @android.webkit.JavascriptInterface
    public void shareText(String text, String subject) {
        if (text == null || text.isEmpty()) return;
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            if (subject != null && !subject.isEmpty()) {
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            intent.putExtra(Intent.EXTRA_TEXT, text);
            try {
                activity.startActivity(Intent.createChooser(intent, "Share via"));
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * AndroidShare.shareFileBase64(base64Data, "receipt.pdf", "application/pdf")
     * The page is responsible for keeping the encoded payload under ~8MB -
     * this bridge exists for a single receipt/report/image, not bulk
     * export, and both the JS bridge call itself and base64's ~33% size
     * overhead make anything larger impractical over this channel anyway.
     */
    @android.webkit.JavascriptInterface
    public void shareFileBase64(String base64Data, String fileName, String mimeType) {
        if (base64Data == null || base64Data.isEmpty() || fileName == null || fileName.isEmpty()) return;

        new Thread(() -> {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                if (bytes.length > MAX_SHARE_FILE_BYTES) return;

                File sharedDir = new File(activity.getCacheDir(), "shared");
                if (!sharedDir.exists()) sharedDir.mkdirs();
                // Sanitize to a bare filename - this came from page JS, and
                // FileProvider's own path matching is stricter than the
                // filesystem, so a stray "../" would just fail the write
                // rather than escape sharedDir, but there's no reason to
                // let it through in the first place.
                String safeName = fileName.replaceAll("[/\\\\]", "_");
                File outFile = new File(sharedDir, safeName);

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(bytes);
                }

                Uri uri = FileProvider.getUriForFile(
                        activity, activity.getPackageName() + ".fileprovider", outFile);
                String type = (mimeType == null || mimeType.isEmpty()) ? "*/*" : mimeType;

                activity.runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType(type);
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        activity.startActivity(Intent.createChooser(intent, "Share via"));
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
                // Bad base64, disk full, whatever - a failed share is a
                // no-op from the page's point of view, never a crash.
            }
        }).start();
    }
}

package com.webapp2apk.generated;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import java.net.HttpURLConnection;
import java.net.URL;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DELAY_MS = 1200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // App.onCreate() runs before any Activity.onCreate(), so appConfig is
        // already populated here.
        boolean splashEnabled = App.appConfig.optBoolean("splash_enabled", true);
        long delay = splashEnabled ? SPLASH_DELAY_MS : 0;

        String homeUrl = App.appConfig.optString("app_url", getString(R.string.app_url));
        preconnectToHomeDomain(homeUrl);
        preWarmWebViewEngine();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, delay);
    }

    /**
     * Fires a lightweight HEAD request to the app's own domain during the
     * splash delay, purely to warm up DNS/TCP/TLS ahead of time so the first
     * real page request in MainActivity starts from a head start instead of
     * a cold connection. Result is completely ignored either way.
     */
    private void preconnectToHomeDomain(String homeUrl) {
        new Thread(() -> {
            try {
                Uri uri = Uri.parse(homeUrl);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme == null || host == null) return;

                HttpURLConnection conn = (HttpURLConnection) new URL(scheme + "://" + host + "/").openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {
                // Purely a warm-up attempt - failing here has no effect on the
                // real page load MainActivity will perform normally afterward.
            }
        }).start();
    }

    /**
     * Instantiating a WebView here warms up the shared system WebView/Chromium
     * renderer process ahead of time, shaving a little off the cold-start cost
     * MainActivity would otherwise pay when it creates its own WebView.
     */
    private void preWarmWebViewEngine() {
        try {
            new WebView(getApplicationContext());
        } catch (Exception ignored) {
        }
    }
}

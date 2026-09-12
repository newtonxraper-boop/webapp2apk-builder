package com.webapp2apk.generated;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.net.HttpURLConnection;
import java.net.URL;

public class SplashActivity extends AppCompatActivity {

    private static final long MAX_SPLASH_DELAY_MS = 1200;
    private static final long MIN_SPLASH_DELAY_MS = 500;
    private static final long FAST_CONNECTION_THRESHOLD_MS = 400;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean navigated = false;
    private android.animation.AnimatorSet dotsAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView icon = findViewById(R.id.splashIcon);
        TextView text = findViewById(R.id.splashText);
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).setStartDelay(80).start();
        text.animate().alpha(1f).setDuration(350).setStartDelay(280).start();
        startDotsAnimation();

        // App.onCreate() runs before any Activity.onCreate(), so appConfig is
        // already populated here.
        boolean splashEnabled = App.appConfig.optBoolean("splash_enabled", true);
        String homeUrl = App.appConfig.optString("app_url", getString(R.string.app_url));

        preWarmWebViewEngine();

        if (!splashEnabled) {
            navigateToMain();
            return;
        }

        // Always navigate after the max delay no matter what, so a slow or
        // failed preconnect check never leaves the splash screen stuck.
        mainHandler.postDelayed(this::navigateToMain, MAX_SPLASH_DELAY_MS);

        long startTime = System.currentTimeMillis();
        preconnectToHomeDomain(homeUrl, () -> {
            // A fast, successful connection check means the site is reachable
            // and responsive right now - no need to make the user wait out
            // the full splash duration just for brand visibility.
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= FAST_CONNECTION_THRESHOLD_MS || navigated) return;

            long remaining = Math.max(0, MIN_SPLASH_DELAY_MS - elapsed);
            mainHandler.postDelayed(this::navigateToMain, remaining);
        });
    }

    private void navigateToMain() {
        if (navigated) return;
        navigated = true;
        if (dotsAnimator != null) dotsAnimator.cancel();
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /**
     * A subtle staggered pulse on the three dots below the app name, so the
     * connection-check wait feels active rather than static.
     */
    private void startDotsAnimation() {
        View dot1 = findViewById(R.id.dot1);
        View dot2 = findViewById(R.id.dot2);
        View dot3 = findViewById(R.id.dot3);

        java.util.List<android.animation.Animator> pulses = new java.util.ArrayList<>();
        View[] dots = {dot1, dot2, dot3};
        for (int i = 0; i < dots.length; i++) {
            android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(
                    dots[i], "alpha", 0.3f, 1f, 0.3f);
            pulse.setDuration(900);
            pulse.setStartDelay(i * 150L);
            pulse.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            pulses.add(pulse);
        }

        dotsAnimator = new android.animation.AnimatorSet();
        dotsAnimator.playTogether(pulses);
        dotsAnimator.start();
    }

    /**
     * Fires a lightweight HEAD request to the app's own domain during the
     * splash delay, purely to warm up DNS/TCP/TLS ahead of time so the first
     * real page request in MainActivity starts from a head start instead of
     * a cold connection. onDone is only invoked when the request actually
     * succeeded, and is posted back to the main thread.
     */
    private void preconnectToHomeDomain(String homeUrl, Runnable onDone) {
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
                int status = conn.getResponseCode();
                conn.disconnect();

                if (status > 0) {
                    mainHandler.post(onDone);
                }
            } catch (Exception ignored) {
                // Purely a warm-up/speed check - a failure here just means the
                // splash keeps its normal full duration, nothing else changes.
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

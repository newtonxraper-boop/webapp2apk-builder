package com.webapp2apk.generated;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;

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

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, delay);
    }
}


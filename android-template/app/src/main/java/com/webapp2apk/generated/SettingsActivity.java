package com.webapp2apk.generated;

import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.os.LocaleListCompat;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;

/**
 * Native settings screen (not part of the web app) for the handful of
 * things that only make sense at the device/app level: App Lock, push
 * notification opt-out, offline sync status, and clearing the local page
 * cache. Its header uses the same primary color the rest of the generated
 * app is themed with, so it doesn't look like a bolted-on system screen.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "webapp2apk_prefs";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setTitle("Settings");
        toolbar.setNavigationOnClickListener(v -> finish());

        setupAppLockRow();
        setupNotificationsRow();
        setupSyncRow();
        setupClearCacheRow();
        setupLanguageRow();
        setupVersionText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reflect a queue that may have drained (or grown) while this screen
        // was in the background, without needing a manual refresh.
        refreshSyncStatus();
    }

    private void setupAppLockRow() {
        Switch appLockSwitch = findViewById(R.id.appLockSwitch);
        appLockSwitch.setChecked(AppLockManager.isEnabled(this));

        appLockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                AppLockManager.setEnabled(this, false);
                return;
            }
            if (!AppLockManager.isAvailableOnDevice(this)) {
                Toast.makeText(this,
                        "Set up a fingerprint, face unlock, or screen lock in your phone's settings first",
                        Toast.LENGTH_LONG).show();
                button.setChecked(false);
                return;
            }
            AppLockManager.setEnabled(this, true);
            // The person just proved who they are by turning this on -
            // don't immediately challenge them again on their way out of
            // this screen.
            App.sessionUnlocked = true;
        });
    }

    private void setupNotificationsRow() {
        boolean pushEnabled = App.appConfig.optBoolean("push_enabled", false);
        Switch notificationsSwitch = findViewById(R.id.notificationsSwitch);

        if (!pushEnabled) {
            notificationsSwitch.setEnabled(false);
            notificationsSwitch.setChecked(false);
            TextView subtext = findViewById(R.id.notificationsSubtext);
            subtext.setText("Not available for this app");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean subscribed = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
        notificationsSwitch.setChecked(subscribed);

        notificationsSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, checked).apply();
            String requestId = App.appConfig.optString("request_id", "");
            String topic = requestId.isEmpty() ? "broadcast_all" : "app_" + requestId;
            try {
                FirebaseMessaging messaging = FirebaseMessaging.getInstance();
                if (checked) {
                    messaging.subscribeToTopic(topic);
                    messaging.subscribeToTopic("broadcast_all");
                } else {
                    messaging.unsubscribeFromTopic(topic);
                    messaging.unsubscribeFromTopic("broadcast_all");
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void setupSyncRow() {
        TextView syncNowButton = findViewById(R.id.settingsSyncNowButton);
        refreshSyncStatus();

        syncNowButton.setOnClickListener(v -> new Thread(() -> {
            OfflineQueueSync.FlushResult result = OfflineQueueSync.flush(this);
            runOnUiThread(() -> {
                refreshSyncStatus();
                String message;
                if (result.succeeded > 0) {
                    message = result.succeeded + " change(s) sent";
                } else if (result.dropped > 0) {
                    message = result.dropped + " item(s) couldn't be sent and were discarded";
                } else {
                    message = "Nothing to send right now";
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        }).start());
    }

    private void refreshSyncStatus() {
        TextView statusText = findViewById(R.id.settingsSyncStatusText);
        int pending = OfflineQueueSync.getQueueSize(this);
        if (pending <= 0) {
            statusText.setText("Up to date");
        } else if (pending == 1) {
            statusText.setText("1 change waiting to sync");
        } else {
            statusText.setText(pending + " changes waiting to sync");
        }
    }

    private void setupClearCacheRow() {
        TextView clearCacheButton = findViewById(R.id.settingsClearCacheButton);
        clearCacheButton.setOnClickListener(v -> {
            File cacheDir = new File(getFilesDir(), "webcache");
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            Toast.makeText(this, "Offline cache cleared", Toast.LENGTH_SHORT).show();
        });
    }

    // Must match locales_config.xml (minus "System default", which isn't a
    // real locale - it's LocaleListCompat.getEmptyLocaleList(), meaning
    // "defer to whatever the phone's system language is").
    private static final String[] LANGUAGE_LABELS = {"System default", "English", "Kiswahili", "Français", "Luganda"};
    private static final String[] LANGUAGE_TAGS = {null, "en", "sw", "fr", "lg"};

    private void setupLanguageRow() {
        View languageRow = findViewById(R.id.languageRow);
        reflectCurrentLanguage();

        languageRow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("App language")
                .setItems(LANGUAGE_LABELS, (dialog, which) -> {
                    LocaleListCompat locales = LANGUAGE_TAGS[which] == null
                            ? LocaleListCompat.getEmptyLocaleList()
                            : LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[which]);
                    // This recreates every activity in the task to apply
                    // immediately (AppCompatDelegate's documented behavior)
                    // - including this Settings screen itself, so there's
                    // no separate "restart to apply" step for the person.
                    AppCompatDelegate.setApplicationLocales(locales);
                })
                .show());
    }

    private int currentLanguageIndex() {
        String currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        for (int i = 1; i < LANGUAGE_TAGS.length; i++) {
            if (LANGUAGE_TAGS[i].equals(currentTag)) return i;
        }
        return 0;
    }

    private void reflectCurrentLanguage() {
        TextView valueText = findViewById(R.id.languageValueText);
        valueText.setText(LANGUAGE_LABELS[currentLanguageIndex()]);
    }

    private void setupVersionText() {
        TextView versionText = findViewById(R.id.settingsVersionText);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = PackageInfoCompat.getLongVersionCode(info);
            versionText.setText("Version " + info.versionName + " (" + versionCode + ")");
        } catch (Exception ignored) {
        }
    }
}

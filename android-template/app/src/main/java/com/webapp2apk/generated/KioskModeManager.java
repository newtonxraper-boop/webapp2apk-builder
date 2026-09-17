package com.webapp2apk.generated;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether kiosk mode (screen pinning via Activity.startLockTask()) is
 * turned on right now. The build-time app_config.json "kiosk_enabled" value
 * is only the DEFAULT for apps that have never touched the setting - once
 * the person flips the switch in Settings, that explicit choice always
 * wins, in either direction, even if it disagrees with the build default.
 */
final class KioskModeManager {

    private static final String PREFS_NAME = "webapp2apk_prefs";
    private static final String KEY_OVERRIDE = "kiosk_enabled_override";

    private KioskModeManager() {
    }

    static boolean isEnabled(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_OVERRIDE)) {
            return prefs.getBoolean(KEY_OVERRIDE, false);
        }
        return App.appConfig.optBoolean("kiosk_enabled", false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OVERRIDE, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}

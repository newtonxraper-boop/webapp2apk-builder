package com.webapp2apk.generated;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.biometric.BiometricManager;

/**
 * Whether App Lock (fingerprint / face / device PIN gate on launch and
 * resume) is turned on, and whether the current device is even capable of
 * it. The on/off flag itself isn't sensitive - it's just a UI preference -
 * so it lives in normal SharedPreferences rather than CredentialVault's
 * encrypted store. The actual unlock is never handled by this app at all;
 * it's fully delegated to the OS via BiometricPrompt in LockActivity.
 */
final class AppLockManager {

    private static final String PREFS_NAME = "webapp2apk_prefs";
    private static final String KEY_ENABLED = "app_lock_enabled";

    private static final int ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private AppLockManager() {
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** True if the device has at least one usable fingerprint/face/PIN/pattern/password set up. */
    static boolean isAvailableOnDevice(Context context) {
        int result = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    static int allowedAuthenticators() {
        return ALLOWED_AUTHENTICATORS;
    }

    /** True if the gate should be shown right now: enabled, capable, and not already unlocked this session. */
    static boolean needsUnlock(Context context) {
        return isEnabled(context) && !App.sessionUnlocked && isAvailableOnDevice(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}

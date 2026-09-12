package com.webapp2apk.generated;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Stores the user's login credentials encrypted at rest via a key held in
 * the Android Keystore - not plain text, and not something another app (or
 * anyone poking around the file system) can read back out without that
 * hardware-backed key. The point is purely to let the app silently
 * re-submit a login if the server ever forces a fresh one, so the person
 * only has to type their password again if they explicitly log out -
 * logout is what calls clear() below.
 */
final class CredentialVault {

    private static final String PREFS_NAME = "secure_credentials";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";

    private CredentialVault() {
    }

    private static SharedPreferences prefs(Context context) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    static void save(Context context, String username, String password) {
        if (username == null || password == null || username.isEmpty() || password.isEmpty()) return;
        try {
            prefs(context).edit()
                    .putString(KEY_USERNAME, username)
                    .putString(KEY_PASSWORD, password)
                    .apply();
        } catch (Exception ignored) {
            // Best-effort - a failure here just means auto-login won't be
            // available next time, never a crash.
        }
    }

    /** Returns {username, password}, or null if nothing is saved. */
    static String[] get(Context context) {
        try {
            SharedPreferences p = prefs(context);
            String u = p.getString(KEY_USERNAME, null);
            String pw = p.getString(KEY_PASSWORD, null);
            if (u == null || pw == null) return null;
            return new String[]{u, pw};
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context context) {
        try {
            prefs(context).edit().clear().apply();
        } catch (Exception ignored) {
        }
    }
}

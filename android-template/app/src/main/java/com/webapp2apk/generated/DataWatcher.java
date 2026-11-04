package com.webapp2apk.generated;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Notices the moment the phone gets mobile data or Wi-Fi (including right
 * after the phone is switched on) and asks the website for new
 * notifications via NotifySyncWorker. Registered once per app process;
 * BootReceiver starts the process after a restart.
 */
final class DataWatcher {

    private static ConnectivityManager.NetworkCallback callback;

    private DataWatcher() {
    }

    static synchronized void start(Context context) {
        if (callback != null) return;
        final Context app = context.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    NotifySyncWorker.runNow(app);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    // data bundle topped up / network finished validating
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        NotifySyncWorker.runNow(app);
                    }
                }
            };
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception e) {
            callback = null;
        }
    }
}

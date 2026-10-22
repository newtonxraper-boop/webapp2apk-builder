package com.webapp2apk.generated;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings background mode and the update/refresh schedules back after the
 * phone restarts, and after the app has just been replaced by an update
 * (installing an update stops the app's process).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            KeepAliveService.start(context);
            UpdateWorker.scheduleIfNeeded(context);
            PeriodicRefreshWorker.scheduleIfNeeded(context);
        } catch (Exception ignored) {
        }
    }
}

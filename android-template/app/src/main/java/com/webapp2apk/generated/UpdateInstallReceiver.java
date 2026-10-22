package com.webapp2apk.generated;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Receives the result of the PackageInstaller session started by
 * UpdateManager.install(). If Android needs the person to confirm the update
 * (always the case for the first update, and on Android 11 and older), the
 * confirmation screen is opened right away when the app is in use, or
 * offered as a notification when it is not - a background app can't pop an
 * activity open by itself.
 */
public class UpdateInstallReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.webapp2apk.generated.UPDATE_INSTALL_RESULT";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (App.isInForeground()) {
                try {
                    context.startActivity(confirm);
                    return;
                } catch (Exception ignored) {
                    // fall through to the notification
                }
            }
            PendingIntent tap = PendingIntent.getActivity(context, 1, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            UpdateManager.notifyUser(context, context.getString(R.string.app_name) + " update ready",
                    "Tap to install the new version", tap);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateManager.cancelNotification(context);
        }
        // Any failure: the downloaded file is kept, so the banner offers
        // "tap to install" again; UpdateManager stops retrying on its own
        // after a few attempts so a broken update can never loop.
    }
}

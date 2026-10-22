package com.webapp2apk.generated;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * "Stay active in the background" - the Android counterpart of the desktop
 * app's tray mode. While it runs, Android treats the app as a user-visible
 * foreground service, so it is far less likely to be killed once the person
 * leaves the app, and the website's own scripts (message polling, timers,
 * notifications) keep running instead of being frozen.
 *
 * The cost is Android's mandatory ongoing notification, which is shown as a
 * minimal, silent one. It can be switched off in Settings.
 */
public class KeepAliveService extends Service {

    public static final String K_BACKGROUND = "background_enabled";
    private static final String CHANNEL_ID = "keep_alive";
    private static final int NOTIF_ID = 7411;

    public static boolean isEnabled(Context c) {
        SharedPreferences p = c.getApplicationContext()
                .getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(K_BACKGROUND, App.appConfig.optBoolean("background_enabled", true));
    }

    public static void setEnabled(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_BACKGROUND, enabled).apply();
        if (enabled) start(c); else stop(c);
    }

    /** Starts the service if the setting is on. Must be called while the app is visible, or from a boot / app-updated receiver. */
    public static void start(Context c) {
        if (!isEnabled(c)) return;
        try {
            ContextCompat.startForegroundService(c.getApplicationContext(),
                    new Intent(c.getApplicationContext(), KeepAliveService.class));
        } catch (Exception ignored) {
            // Android refused a background start - it will start next time the app is opened
        }
    }

    public static void stop(Context c) {
        try {
            c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), KeepAliveService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Running in background", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Running in the background")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(tap)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

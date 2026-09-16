package com.webapp2apk.generated;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Fired by AlarmManager when a window.AndroidNotify.schedule(...) timer
 * comes due (see LocalNotifyBridge). Uses its own notification channel,
 * separate from Firebase push ("App notifications"), so the person can
 * mute one without muting the other in system Settings.
 */
public class LocalNotificationReceiver extends BroadcastReceiver {

    static final String EXTRA_ID = "local_notify_id";
    static final String EXTRA_TITLE = "local_notify_title";
    static final String EXTRA_BODY = "local_notify_body";

    private static final String CHANNEL_ID = "webapp2apk_local_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra(EXTRA_ID, (int) System.currentTimeMillis());
        String title = intent.getStringExtra(EXTRA_TITLE);
        String body = intent.getStringExtra(EXTRA_BODY);
        if (title == null) title = "";
        if (body == null) body = "";

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, id, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(context, R.color.accent_color))
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            manager.notify(id, builder.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS was never granted - nothing else to do here,
            // this is a background receiver with no UI to fall back to.
        }
    }
}

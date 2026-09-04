package com.webapp2apk.generated;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "webapp2apk_notifications";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        String title = "New notification";
        String body = "";

        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) {
                title = remoteMessage.getNotification().getTitle();
            }
            if (remoteMessage.getNotification().getBody() != null) {
                body = remoteMessage.getNotification().getBody();
            }
        } else if (!remoteMessage.getData().isEmpty()) {
            title = remoteMessage.getData().getOrDefault("title", title);
            body = remoteMessage.getData().getOrDefault("body", body);
        }

        showNotification(title, body);
    }

    private void showNotification(String title, String body) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "App notifications", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        // Best-effort unread counter for a numeric launcher badge - support for
        // an actual NUMBER (rather than just a dot) varies by launcher/OEM and
        // isn't something any single API guarantees across all phones, but
        // NotificationCompat.setNumber() is respected by several of them.
        SharedPreferences prefs = getSharedPreferences("webapp2apk_prefs", MODE_PRIVATE);
        int unreadCount = prefs.getInt("unread_notification_count", 0) + 1;
        prefs.edit().putInt("unread_notification_count", unreadCount).apply();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setNumber(unreadCount)
                .setContentIntent(pendingIntent);

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }
}

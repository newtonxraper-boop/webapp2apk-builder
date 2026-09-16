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
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import java.util.List;

/**
 * Fired by Play Services' GeofencingClient on every geofence enter/exit
 * registered via window.AndroidLocation.startGeofence. Two things happen
 * on every transition, in this order:
 *
 *   1. A system Notification, always - this is the part that's reliable
 *      even if Android has fully killed the app's process, since a
 *      BroadcastReceiver like this one still gets woken up for it.
 *   2. If MainActivity happens to be alive right now (tracked via a
 *      static reference it sets/clears itself in onResume/onPause), the
 *      event is also forwarded live to the page's window.onGeofenceEvent -
 *      a "nice to have" for when the app is already open, not something
 *      to depend on for reliability.
 */
public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "webapp2apk_geofence_alerts";

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()) return;

        int transition = event.getGeofenceTransition();
        boolean entered = transition == Geofence.GEOFENCE_TRANSITION_ENTER;
        boolean exited = transition == Geofence.GEOFENCE_TRANSITION_EXIT;
        if (!entered && !exited) return;

        List<Geofence> triggering = event.getTriggeringGeofences();
        if (triggering == null) return;

        for (Geofence geofence : triggering) {
            String id = geofence.getRequestId();
            showNotification(context, id, entered);

            MainActivity active = MainActivity.getActiveInstance();
            if (active != null) {
                active.deliverGeofenceEvent(id, entered);
            }
        }
    }

    private void showNotification(Context context, String geofenceId, boolean entered) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Location alerts", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int requestCode = geofenceId.hashCode();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, requestCode, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = entered ? "You're nearby" : "You've left the area";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(context, R.color.accent_color))
                .setContentTitle(title)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            manager.notify(requestCode, builder.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS was never granted - nothing else to do here.
        }
    }
}

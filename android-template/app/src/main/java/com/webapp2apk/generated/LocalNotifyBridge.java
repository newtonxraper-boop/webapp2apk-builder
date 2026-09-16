package com.webapp2apk.generated;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * Exposed to the WebView as window.AndroidNotify. Call from the page as:
 *
 *   AndroidNotify.schedule(1, "Cart waiting", "Come back and check out!", 3600);
 *   AndroidNotify.cancel(1);
 *
 * Unlike the Firebase push notifications this app may also have (which
 * need your server to send something), these fire entirely from the
 * device itself on a timer - no server, no internet connection required
 * at delivery time. Good for reminders, cart-abandonment nudges, or
 * "come back tomorrow" style prompts a page schedules right before the
 * person closes the app.
 *
 * Uses AlarmManager.set() (inexact timing, no special permission needed)
 * rather than setExactAndAllowWhileIdle() - a reminder firing a few
 * minutes off schedule is fine, and this avoids Android 13+'s separate
 * "Alarms & reminders" permission screen entirely. Scheduled alarms do
 * NOT survive a device reboot (no boot receiver is registered) - if that
 * matters for your use case, ask about adding one.
 */
final class LocalNotifyBridge {

    private final Context appContext;

    LocalNotifyBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @JavascriptInterface
    public void schedule(int id, String title, String body, int delaySeconds) {
        if (delaySeconds < 0) delaySeconds = 0;
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(appContext, LocalNotificationReceiver.class);
        intent.putExtra(LocalNotificationReceiver.EXTRA_ID, id);
        intent.putExtra(LocalNotificationReceiver.EXTRA_TITLE, title != null ? title : "");
        intent.putExtra(LocalNotificationReceiver.EXTRA_BODY, body != null ? body : "");

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                appContext, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAt = System.currentTimeMillis() + (delaySeconds * 1000L);
        try {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } catch (SecurityException ignored) {
            // Extremely unlikely for a non-exact alarm, but never crash the
            // app over an optional reminder failing to schedule.
        }
    }

    @JavascriptInterface
    public void cancel(int id) {
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        Intent intent = new Intent(appContext, LocalNotificationReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                appContext, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(pendingIntent);
    }
}

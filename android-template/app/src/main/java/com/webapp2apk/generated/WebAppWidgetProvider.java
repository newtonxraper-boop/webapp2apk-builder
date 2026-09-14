package com.webapp2apk.generated;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * A minimal home-screen widget: tapping it opens the app straight into the
 * WebView (skipping the splash screen, since the process may already be
 * warm), and its subtitle line reflects the same online/offline + pending
 * sync state as the in-app banner - useful at a glance for a field agent or
 * shop owner deciding whether it's worth opening the app before they have
 * signal back.
 *
 * The OS only guarantees updates on its own schedule (webapp_widget_info.xml
 * asks for 30 min, the minimum it will actually honor), so MainActivity and
 * OfflineQueueSync also call {@link #pushUpdate(Context)} directly whenever
 * connectivity or the queue count actually changes, which is what keeps the
 * widget feeling live rather than stale for half an hour at a time.
 */
public class WebAppWidgetProvider extends AppWidgetProvider {

    private static final String PREFS_NAME = "webapp2apk_prefs";
    private static final String KEY_LAST_ONLINE = "widget_last_online";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateOne(context, appWidgetManager, id);
        }
    }

    private void updateOne(Context context, AppWidgetManager appWidgetManager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_webapp);

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags);
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean lastOnline = prefs.getBoolean(KEY_LAST_ONLINE, true);
        int pending = OfflineQueueSync.getQueueSize(context);

        String status;
        if (!lastOnline) {
            status = pending > 0 ? "Offline \u00b7 " + pending + " pending" : "Offline";
        } else if (pending > 0) {
            status = "Syncing " + pending + "\u2026";
        } else {
            status = "Online \u00b7 tap to open";
        }
        views.setTextViewText(R.id.widgetStatus, status);

        appWidgetManager.updateAppWidget(widgetId, views);
    }

    /** Persists the latest online/offline state so the widget can read it
     *  without the app process needing to be alive. Called from
     *  MainActivity's own connectivity callback right next to where it
     *  updates the in-app banner. */
    public static void setLastOnline(Context context, boolean online) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAST_ONLINE, online).apply();
        pushUpdate(context);
    }

    /** Forces every placed instance of this widget to redraw immediately,
     *  rather than waiting for the OS's own (30 min minimum) update tick. */
    public static void pushUpdate(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName provider = new ComponentName(context, WebAppWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(provider);
            if (ids != null && ids.length > 0) {
                context.sendBroadcast(new Intent(context, WebAppWidgetProvider.class)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
            }
        } catch (Exception ignored) {
            // No widget placed, or the OS widget host isn't available right
            // now - either way this is a purely cosmetic feature, never
            // worth surfacing an error for.
        }
    }
}

package com.webapp2apk.generated;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * "Phone just got data -> ask the website what's new -> show notifications."
 *
 * Calls the site's own /notify.php (the same endpoint the web pages use)
 * with the login cookie the app's WebView already holds, so no extra
 * tokens or server changes are needed. Runs:
 *   - instantly when data/Wi-Fi comes back (DataWatcher),
 *   - after every phone restart (BootReceiver),
 *   - every ~15 minutes as a safety net (WorkManager's minimum).
 * Skips while the app is on screen (the page shows its own pop-ups).
 * Uses the same notification tag as the Firebase push, so a message that
 * arrives both ways shows once.
 */
public class NotifySyncWorker extends Worker {

    private static final String PREFS = "notify_sync";
    private static final String K_AFTER = "after";
    private static final String K_AFTER_M = "after_m";
    private static final String CHANNEL_ID = "webapp2apk_messages";
    private static final String UNIQUE_NOW = "notify_sync_now";
    private static final String UNIQUE_PERIODIC = "notify_sync_periodic";

    public NotifySyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Run one check as soon as there is a network. Safe to call often. */
    public static void runNow(Context context) {
        try {
            Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(NotifySyncWorker.class)
                    .setConstraints(c)
                    .setInitialDelay(1, TimeUnit.SECONDS)
                    .build();
            // KEEP: ten "network available" events in a row = one check.
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
        }
    }

    public static void scheduleIfNeeded(Context context) {
        try {
            Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(NotifySyncWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(c)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            if (App.isInForeground()) return Result.success();

            String appUrl = ctx.getString(R.string.app_url);
            if (appUrl == null || appUrl.isEmpty() || appUrl.startsWith("{{")) return Result.success();
            Uri u = Uri.parse(appUrl);
            if (u.getScheme() == null || u.getHost() == null) return Result.success();
            String origin = u.getScheme() + "://" + u.getAuthority();

            String cookie = CookieManager.getInstance().getCookie(origin);
            if (cookie == null || cookie.isEmpty()) return Result.success();   // not logged in yet

            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long after = p.getLong(K_AFTER, -1);
            long afterM = p.getLong(K_AFTER_M, -1);

            String body = fetch(origin + "/notify.php?after=" + after + "&am=" + afterM, cookie);
            if (body == null) return Result.retry();
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("success", false)) return Result.success();   // logged out / server hiccup

            SharedPreferences.Editor ed = p.edit();
            ed.putLong(K_AFTER, json.optLong("latest_id", after));
            ed.putLong(K_AFTER_M, json.optLong("latest_mention", afterM));
            ed.apply();

            if (after < 0) return Result.success();   // first run only remembers "where we are"

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return Result.success();
            ensureChannel(nm);

            show(ctx, nm, json.optJSONArray("messages"), origin);
            show(ctx, nm, json.optJSONArray("mentions"), origin);

            JSONObject call = json.optJSONObject("incoming");
            if (call != null) {
                String who = call.optString("name", "Someone");
                String kind = "video".equalsIgnoreCase(call.optString("call_type")) ? "video" : "voice";
                notifyOne(ctx, nm, "call_" + call.optString("call_uid"), who, "Incoming " + kind + " call",
                        origin + "/chat.php?open=" + Uri.encode(call.optString("conversation_id")));
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void show(Context ctx, NotificationManager nm, JSONArray arr, String origin) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String conv = m.optString("conversation_id", "");
            String name = m.optString("name", "New message");
            String text = m.optString("text", "");
            int count = m.optInt("count", 1);
            String title = count > 1 ? name + " (" + count + ")" : name;
            notifyOne(ctx, nm, "conv_" + conv, title, text, origin + "/chat.php?open=" + Uri.encode(conv));
        }
    }

    private void notifyOne(Context ctx, NotificationManager nm, String tag, String title, String text, String url) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        open.setData(Uri.parse(url));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, tag.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(ctx, R.color.accent_color))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            nm.notify(tag, 1, b.build());   // same tag + id as the Firebase push -> no duplicates
        } catch (SecurityException ignored) {
            // notification permission not granted
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private static String fetch(String urlStr, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream is = conn.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

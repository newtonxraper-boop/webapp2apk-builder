package com.webapp2apk.generated;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * OfflineQueueWorker only runs when there's actually something queued to
 * send. This is the complementary background job: on a periodic schedule
 * (independent of whether the app is open, or has anything pending), it
 * does two small things while the device happens to have connectivity -
 * (1) a lightweight reachability probe against the web app's own domain,
 * which both warms Android's own connectivity validation for that network
 * and keeps the widget's "online/offline" status honest between app opens,
 * and (2) pushes a widget redraw so the tile doesn't silently go stale for
 * up to 30 minutes (the OS's own widget update minimum) while the app is
 * closed. It deliberately does NOT try to prefetch/cache full pages here -
 * that stays a foreground-triggered job (MainActivity's own offline cache)
 * so it never burns a user's mobile data bundle in the background without
 * them having actually opened the app that day.
 */
public class PeriodicRefreshWorker extends Worker {

    private static final String UNIQUE_WORK_NAME = "periodic_refresh";

    public PeriodicRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        boolean online = probeReachability(context);
        WebAppWidgetProvider.setLastOnline(context, online);
        return Result.success();
    }

    private boolean probeReachability(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        // Trust the OS's own validation first (cheap, no network call of
        // our own needed) - only fall through to an actual HTTP probe when
        // the OS itself is uncertain, e.g. still validating a
        // just-connected network.
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return true;
        }
        return NetworkReachability.probe(context);
    }

    /**
     * Schedules (or leaves alone, if already scheduled) the recurring job.
     * KEEP policy so calling this on every app launch never resets or
     * duplicates the existing schedule.
     */
    public static void scheduleIfNeeded(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PeriodicRefreshWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
}

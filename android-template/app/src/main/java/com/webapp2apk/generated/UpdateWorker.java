package com.webapp2apk.generated;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the app up to date while it is closed or in the background:
 *  - "check" runs every few hours (survives app kill and reboot): looks for a
 *    newer build, downloads it (on Wi-Fi unless the person allowed mobile
 *    data) and tells them it is ready.
 *  - "install" runs a couple of minutes after the app leaves the screen: if
 *    an update is ready it installs it, like the desktop app does when it is
 *    closed. On Android 12+ this can be fully unattended; otherwise the
 *    person gets a "tap to install" notification.
 */
public class UpdateWorker extends Worker {

    private static final String UNIQUE_CHECK = "app_update_check";
    private static final String UNIQUE_INSTALL = "app_update_install";
    private static final String KEY_INSTALL_ONLY = "install_only";

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        if (!UpdateManager.isAutoUpdateEnabled(c)) return Result.success();

        if (getInputData().getBoolean(KEY_INSTALL_ONLY, false)) {
            installIfIdle(c);
            return Result.success();
        }

        try {
            if (UpdateManager.getReadyFile(c) == null) {
                UpdateManager.Info info = UpdateManager.check(c);
                if (info == null) return Result.success();
                if (!UpdateManager.canAutoDownloadNow(c)) {
                    UpdateManager.announceAvailable(c);
                    return Result.success();
                }
                try {
                    UpdateManager.download(c, info, pct -> {
                        if (pct % 5 == 0) UpdateManager.notifyProgress(c, pct);
                    });
                } finally {
                    UpdateManager.cancelProgress(c);
                }
                UpdateManager.announceReady(c);
            }
            installIfIdle(c);
            return Result.success();
        } catch (UpdateManager.Rejected e) {
            return Result.success(); // unusable file - UpdateManager won't offer it again
        } catch (Exception e) {
            // a dropped connection keeps its partial download and resumes on the retry
            return getRunAttemptCount() < 5 ? Result.retry() : Result.success();
        }
    }

    private static void installIfIdle(Context c) {
        if (App.isInForeground()) return; // the person is using the app - the banner handles it
        File ready = UpdateManager.getReadyFile(c);
        if (ready == null) return;
        long ver = c.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .getLong(UpdateManager.K_READY_VERSION, 0);
        if (UpdateManager.attemptsExhausted(c, ver)) return;
        try {
            UpdateManager.install(c, ready);
        } catch (Exception e) {
            UpdateManager.announceReady(c); // e.g. "install unknown apps" not granted yet
        }
    }

    /** Safe to call on every launch - KEEP means an existing schedule is never reset. */
    public static void scheduleIfNeeded(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(UpdateWorker.class, 3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_CHECK, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** Called when the app leaves the screen: install a ready update shortly after. */
    public static void scheduleInstallSoon(Context context) {
        if (UpdateManager.getReadyFile(context) == null) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateWorker.class)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putBoolean(KEY_INSTALL_ONLY, true).build())
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_INSTALL, ExistingWorkPolicy.REPLACE, request);
    }

    public static void cancelInstallSoon(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_INSTALL);
    }
}
